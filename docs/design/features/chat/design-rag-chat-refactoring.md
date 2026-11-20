# rag-chat 模块重构设计（设计模式与职责重划）

> 版本：v1.0｜日期：2026-11-20｜范围：rag-backend/rag-chat
> 关联文档：[设计模式重构方案](../../refactoring/design-pattern-refactoring.md)｜[总体设计](../../design-overview.md)｜[详细设计](../../design-detailed.md)
> 原则：对外 HTTP 契约与 SSE 行为不变；小步重构，每批保持可编译、可测试；不为用模式而用模式。

---

## 1. 现状复杂度热点

### 1.1 关键类与规模

| 类 | 行数（重构前） | 说明 |
|----|---------------|------|
| `RagChatService` | 1877 | 对话主链路上帝类，27 个构造依赖 |
| `ContextCompressionService` | 485 | 已按责任链拆分，保留不动 |
| `LlmJudgeService` | 510 | 在线评估，独立职责，保留不动 |
| `ChatWindowService` | 380 | 窗口 CRUD，职责清晰，保留不动 |
| `PromptTemplateService` | 289 | 模板渲染，独立职责，保留不动 |
| `ChatWorkflowService` | 163 | 聊天侧工作流事件适配 |
| `ChatCancellationStore` | 154 | 跨实例取消标记 |
| `ChatStreamCancellation` | 133 | 单次生成取消句柄 |

### 1.2 职责混杂点

1. **`RagChatService` 承担 8 类职责**：SSE 生命周期、会话生命周期、检索编排、Prompt 装配、上下文预算、流式故障转移与工具循环、终态落库、成本/配额结算、在线评估、引用与正确率计算。
2. **`doRagStreamChat` 约 315 行**，内嵌 5 条路线（工作流 / 语义缓存 / Agent / 无结果 / LLM），共享可变局部变量 `messages`、`modelChain`、`contextBudget`、`hitRate`，阅读与修改都需要同时理解全部分支。
3. **`streamChatWithTools` + `onCompleteResponse` 约 160 行**：递归工具循环、取消检查、SSE 写失败、轮次上限、token 累计交织在同一回调里，分支深度高。
4. **`FailoverContext` 22 参构造器**：典型的数据泥团；`int[] toolRoundCount`、`long[] tokenUsageAcc` 以可变数组在请求线程与流式回调线程之间传递，语义隐式。
5. **横切逻辑重复**：`safeSend/safeComplete` 在 `RagChatService` 与 `ChatWorkflowService` 各一份；「插入 assistant 消息 + 更新会话计数」也在两处重复。
6. **`sendFinalEvents` 约 64 行**：SSE 协议下发、正确率、检索指标、消息落库、成本回填、语义缓存写入、judge 触发全部耦合在一个方法。
7. **引用口径分散**：`[Sn]` 编号、来源卡片去重、正确率计算依赖同一套「按文档保留最高分」规则，但实现散落在多个私有方法里，修改时容易不同步。

---

## 2. 模式选型

| 热点 | 选型 | 解决的问题 | 放弃的替代方案 |
|------|------|-----------|---------------|
| 上帝类、27 个依赖 | **Facade + Extract Class** | 入口只做校验、配额、建流、线程调度；其余职责下沉为可独立测试的协作类 | 继续用私有方法拆分——方法数量会继续膨胀，依赖仍然全部堆在同一个类上 |
| `doRagStreamChat` 5 路深层分支 | **Pipeline + Strategy（有序步骤链）** | 每条路线成为实现同一接口的步骤，按固定顺序执行；步骤返回「是否终态」即可短路，新增路线只加一个步骤类 | 通用事件总线 / 可配置 DSL——路由只有 5 条且优先级稳定，配置化会降低可读性；Spring Event 异步化——SSE 是请求内同步流，异步总线会引入时序与取消复杂度 |
| `FailoverContext` 22 参构造器 | **Builder + 参数对象** | 用 `ChatTurnContext` 承载一次对话的请求/派生/流式状态，构造过程可读；避免位置参数错配 | 继续扩构造器；或改成可变 POJO 直接 set——缺少必填约束，易漏字段 |
| `int[]` / `long[]` 共享可变状态 | **状态对象（`ChatTurnState`）** | 工具轮次与 token 累计有明确语义和原子操作，替代「数组下标即字段」的隐式约定 | 继续用数组——可读性差，无法表达并发语义 |
| 终态落库三态（COMPLETED / STOPPED / FAILED） | **提取类 `ChatAnswerStreamer` 内的统一终态写入** | 终态唯一性、取消回调、成本结算集中在一处 | Template Method——三态差异只是 status 与「是否保留空内容」，抽象基类反而增加层级 |
| `safeSend` / 消息落库重复 | **提取类** | `ChatSsePublisher`、`ChatMessageStore` 成为单一出口，普通 RAG / Agent / 工作流三条链路共用 | 继续各写一份——行为容易漂移 |
| 引用与正确率口径分散 | **提取纯函数类 `ChatCitationAssembler`** | 编号、来源卡片、正确率共用同一套去重与匹配规则，可独立单测 | 留在服务里——纯函数被 IO 依赖包裹，难以验证 |

模式使用边界：

- 不引入 EventBus / MQ：SSE 与同步调用已满足当前链路。
- 不为 Entity 引入 Builder：MyBatis-Plus 仍使用 JavaBean。
- 不改 `StreamingModelFailover`：故障转移状态机已在 rag-llm，chat 只实现 Listener 适配。
- 不改压缩责任链：`ContextCompressionService` 已完成责任链化，不在本次范围。

---

## 3. 目标结构与职责

```
RagChatService（Facade）
  ├─ 窗口/分享链接校验、图片校验、配额、会话确保、SSE/Cancellation 创建、线程调度
  └─ ChatTurnOrchestrator
       ├─ ChatTurnStep（Strategy 接口，order 升序，返回 true 即终态）
       │    ├─ ChatWindowBindingStep    解析 kbIds / 评估方案 / Agent 开关
       │    ├─ WorkflowChatStep         窗口绑定工作流 → 交给 ChatWorkflowService
       │    ├─ SemanticCacheChatStep    语义缓存命中 → 直接返回缓存答案
       │    ├─ RetrievalChatStep        检索、Prompt/工具/预算前置、检索指标下发
       │    ├─ AgentChatStep            Agent 规划执行，失败降级为普通 RAG
       │    ├─ NoResultChatStep         无检索/无工具/无图片的兜底话术
       │    └─ LlmChatStep              组装请求并启动流式故障转移
       └─ ChatAnswerStreamer
            ├─ StreamingModelFailover.Listener 适配（切链事件、取消、错误）
            ├─ 工具调用循环（轮次、SSE 工具事件、工具结果预算）
            ├─ 最终事件（sources / accuracy / metrics / done / judge）
            └─ 终态落库（COMPLETED / STOPPED / FAILED）+ 成本配额结算

ChatTurnContext（Builder）：一次对话的请求输入、派生上下文与流式状态
ChatTurnState：工具轮次、输入/输出 token 累计

支撑类：
ChatSessionStore          会话确保/创建/分享复用/自动标题
ChatMessageStore          用户消息、助手终态消息、检索指标回填、会话记忆
ChatPromptAssembler       系统提示、历史压缩与预算、RAG 用户消息、多模态消息
ChatContextBudgetService  RAG chunk 裁剪、map-reduce、工具结果预算、Prompt Token 估算
ChatCostSettler           token/成本回填与配额累加
ChatToolLoader            窗口级工具加载与启用过滤
ChatEvaluationService     LLM-as-Judge 异步评估
ChatCitationAssembler     [Sn] 编号、来源卡片、正确率（纯函数）
ChatSsePublisher          SSE 事件统一出口与断开容错
```

依赖方向：`RagChatService → ChatTurnOrchestrator → 步骤/Streamer → 支撑类`，支撑类之间不反向依赖入口；`ChatCancellationStore`、`ChatStreamCancellation` 保持原样。

### 3.1 service 包按职责分组

`com.wgh.rag.chat.service` 只保留对外入口与跨模块公共 API，内部协作类下沉到子包：

| 包 | 类 | 分组依据 |
|----|----|---------|
| `service`（根） | `RagChatService`、`ChatWindowService`、`ChatImageService`、`FeedbackService`、`PromptTemplateService`、`ChatWorkflowService`、`ChatCancellationStore`、`SkillLlmClientImpl` | 被 Controller / public-access / 其它模块直接依赖的稳定 API，或跨链路的窗口/模板/反馈能力；移动会破坏模块边界，故保留在根包 |
| `service.session` | `ChatSessionStore`、`ChatMessageStore` | 会话与消息持久化，二者共享会话/消息 Mapper 与会话计数语义，放在一起 |
| `service.prompt` | `ChatPromptAssembler`、`ChatCitationAssembler`、`ChatContextBudgetService`、`ContextCompressionService` | Prompt 装配、引用/正确率、上下文预算、历史压缩共用同一份「裁剪后上下文」口径，属于同一职责域 |
| `service.flow` | `ChatTurnOrchestrator`、`ChatTurnStep`、7 个步骤（含 `WorkflowChatStep`，调用根包 `ChatWorkflowService`）、`ChatTurnContext`、`ChatTurnState`、`ChatMetricContext`、`ChatAnswerStreamer` | 批次三建立的有序步骤链与终态执行器共享可变上下文，拆包会迫使上下文字段全部公开；因此整组保留在一个包内，作为「对话执行流」 |
| `service.stream` | `ChatSsePublisher`、`ChatStreamCancellation` | 与具体上下文无关的 SSE 下发与取消设施，可被任意链路复用 |
| `service.window` | `ChatToolLoader`、`ChatWindowSettings` | 窗口级工具加载与窗口 JSON 配置解析，服务于窗口绑定步骤与 Prompt 装配 |
| `service.cost` | `ChatCostSettler` | 成本回填与配额 Token 累加，独立于会话/流式状态 |
| `service.evaluation` | `ChatEvaluationService` | LLM-as-Judge 在线评估；与 `com.wgh.rag.chat.evaluation` 的领域模型（`EvaluationScheme` 等）区分：前者是执行服务，后者是评估协议 |

分组原则：

1. **按变化原因分组**，而不是按类名后缀分组；同一职责域内的类一起变更。
2. **批次三结构不拆散**：`ChatAnswerStreamer` 与 `ChatTurnContext` 强耦合，放在 `flow`，不为了「流式」名义把上下文改成大面积 public 字段。
3. **外部可见 API 不下沉**：被其它模块直接 import 的类保留在根包，避免跨模块改包名。
4. **测试随被测包走**：`ModelFailoverStreamTest` 移到 `flow` 测试包，断言与用例不变；其余测试仅补 import。


---

## 4. 实施批次与可验证边界

### 批次 1：提取支撑类（行为零变化）

- 新增 `ChatSsePublisher`、`ChatSessionStore`、`ChatMessageStore`、`ChatCitationAssembler`、`ChatPromptAssembler`、`ChatContextBudgetService`、`ChatCostSettler`、`ChatToolLoader`、`ChatEvaluationService`。
- `RagChatService` 改为委托，删除对应私有方法与不再使用的依赖；`ChatWorkflowService` 复用 SSE 出口与消息落库。
- 可验证边界：`test-compile` 通过；所有原有测试构造方式同步更新；不改变任何 SSE 事件、落库字段与预算结果。

### 批次 2：抽取流式执行器与上下文

- 新增 `ChatTurnContext`（Builder）、`ChatTurnState`、`ChatAnswerStreamer`。
- 删除 `FailoverContext` 与 `RagChatService` 内的故障转移监听、工具循环、终态落库实现。
- 可验证边界：`ModelFailoverStreamTest` 改为直接驱动 `ChatAnswerStreamer`，覆盖首 token 前切链、全失败、已产出内容后失败、重复错误回调、客户端断连、显式取消、正常完成；`test-compile` 通过。

### 批次 3：抽取编排与路线步骤

- 新增 `ChatTurnOrchestrator` 与 7 个 `ChatTurnStep` 实现。
- `RagChatService` 收缩为 Facade；`doRagStreamChat` 删除。
- 可验证边界：步骤顺序固定为 Binding(50) → Workflow(100) → SemanticCache(200) → Retrieval(300) → Agent(400) → NoResult(500) → Llm(600)；`test-compile` 通过；人工核对每个步骤的短路条件与原分支一致。

### 批次 4：service 包按职责分组

- 根包保留外部可见 API（Facade、窗口/图片/反馈/模板/工作流/取消存储）；内部协作类下沉到 `session`、`prompt`、`flow`、`stream`、`window`、`cost`、`evaluation`。
- 批次三的步骤链、上下文、状态与终态执行器整组保留在 `flow`，不因分包把强耦合结构打散。
- 可验证边界：`test-compile` 通过；测试仅补充 import/包路径，现有断言不变。

---

## 5. 必须保持的行为不变式

1. **SSE 协议**：事件名与载荷结构不变——`start`、`retrieve`、`content`、`sources`、`done`、`accuracy`、`metrics`、`model_switch`、`tool_call_start`、`tool_call_result`、`judge`、`error`、`cache`；仍通过 `SseEmitter` 输出单行 JSON。工作流事件继续映射为聊天协议。
2. **唯一终态**：一次生成只允许一个终态落库——正常完成 `COMPLETED`、用户停止/断连 `STOPPED`、已产出内容后失败 `FAILED`；由 `ChatStreamCancellation.markFinalized()` 与取消 hook 的 CAS 抢占保证，不重复插入 assistant 消息。
3. **故障转移顺序**：严格按 `ModelRouter.resolveChain(...)` 返回顺序尝试；首 token 前失败切下一个，已产出内容后失败不切链、不回滚、不重发；全部失败下发明确业务错误并关闭流；`model_switch` 事件语义不变（首模型、切链原因）。
4. **上下文预算优先级**：检索 chunk 按分数（rerank/RRF/余弦）降序保留，受「硬上限 / 模型窗口 × 输入占比 − 已占用 Prompt」与单条最小 Token 约束；仅当最高分单条 chunk 撑满预算时才触发 map-reduce 分段摘要；工具结果执行「单条上限 + 多轮累计上限」；历史消息先分层压缩，再按 Token 硬裁剪。
5. **来源与指标同源**：Prompt 拼接、`sources` 卡片、`accuracy` / `metrics` 必须基于同一份预算裁剪后的 chunk 列表；引用编号按文档首次出现顺序分配，同文档保留最高分。
6. **多实例取消**：显式停止写入会话级 Redis 标记，流式实例轮询消费；客户端断连/正常完成只置位本地取消，不写共享标记；新一轮生成开始时清除遗留标记。
7. **对外 HTTP 契约**：`streamChat` / `streamShareChat` / `appendAssistantMessage` 的方法语义与 Controller 调用方式不变；配额检查仍在请求线程执行。

---

## 6. 风险与未覆盖项

| 风险 / 未覆盖项 | 说明与缓解 |
|----------------|-----------|
| 行为回归 | 本次约束不新增测试，仅做编译验证；既有 `ModelFailoverStreamTest`、`RagChatServiceTest` 等作为回归基线，但仍建议后续补跑全量测试 |
| 路线重构的异常边界 | Agent 失败需继续降级普通 RAG；检索异常需冒泡到外层统一错误事件。步骤链通过「返回 false 继续、抛异常冒泡」保持原语义，需人工复核 |
| 线程模型未变 | 仍为「每个请求一个新线程」；未引入虚拟线程或调度中心，避免与后续调度中心方案耦合 |
| 多实例 SSE 粘性 | 取消标记已跨实例，但 SSE 连接仍是单实例持有；负载均衡需保证同一会话流不切换实例，属于部署层约束 |
| 未纳入本次范围 | `LlmJudgeService`、`ChatWindowService`、`PromptTemplateService` 保持不动；`ContextCompressionService` 仅随 Prompt 分组调整包位置，逻辑不变；`rag-context` 预算引擎接入不在本次范围 |
| 日志可观测性 | 部分日志前缀从 `RagChatService` 移到支撑类；事件名、指标名与关键字段保持不变 |

---

## 7. 落地进度（与方案分开维护）

| 批次 | 状态 | 验证 |
|------|------|------|
| 批次 1 支撑类提取 | 已完成 | `mvn -pl rag-chat -am -DskipTests test-compile` 通过 |
| 批次 2 流式执行器 | 已完成 | `mvn -pl rag-chat -am -DskipTests test-compile` 通过；`ModelFailoverStreamTest` 断言不变 |
| 批次 3 编排与路线步骤 | 已完成 | `mvn -pl rag-chat -am -DskipTests test-compile` 通过；步骤顺序 Binding(50) → Workflow(100) → SemanticCache(200) → Retrieval(300) → Agent(400) → NoResult(500) → Llm(600) |
| 批次 4 service 包分组 | 已完成 | `mvn -DskipTests test-compile`（rag-backend 全量）通过；测试仅补 import/包路径，断言不变 |

> 本节仅记录执行状态，不改变前六节的方案内容。本次约束为编译验证，未运行测试、未执行 git add/commit/push。
