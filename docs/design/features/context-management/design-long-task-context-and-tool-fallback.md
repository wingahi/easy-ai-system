# 长任务上下文管理与工具调用兜底 · 技术方案

> 版本：v1（2026-11-18）
> 范围：`rag-chat`、`rag-agent`、`rag-agent-multi`、`rag-workflow`、`rag-tool`、`rag-llm`
> 性质：方案文档，不含实现代码；引用代码均给出文件路径与行号范围。

***

## 0. 结论摘要

1. **当前的「上下文预算」只是一段检索片段裁剪器，不是上下文管理器。** `RagContextBudgeter` 只决定「检索结果里留几条 chunk」，且仅在单轮 RAG 主链路上真正拿到模型窗口；Agent、多 Agent、工作流检索节点都走 `trimWithDefaults`，用固定 8192 兜底窗口算预算，与真实模型窗口（可能是 8k，也可能是 128k）无关。历史消息、工具返回结果、前序节点输出**完全不受该预算约束**。
2. **真正会超窗或被静默截断的位置有 6 类**：多轮历史（按条数/轮次裁剪，不按 Token）、工具结果（单条 4000 字符，且不参与总预算）、Agent 前序步骤上下文（每步 500 字摘要但步数不设上限）、多 Agent 汇总（worker 产出全文拼接）、工作流 `lastOutput` / `{{nodeId.text}}`（全文渲染进下一节点 prompt）、工作流 `context_json`（全文落库）。
3. **工具调用侧没有兜底层。** 现状是「超时 + SSRF + 结果截断 + 把异常文本回给模型」。没有重试、没有失败分类、没有降级、没有熔断、没有幂等保护，也没有与模型故障转移的叠加顺序约定。
4. **落地方向**：新增两个收敛点——`ContextAssembler`（统一按预算分配 system / 历史 / 检索 / 工具结果 / 状态块名额）与 `ToolInvoker`（统一工具兜底链）；两者都**复用**现有 `RagContextBudgeter`、`TokenEstimator`、`BlockingModelFailover` / `StreamingModelFailover`、`SsrfGuard`，不推翻现有结构。
5. **版本建议**：当前版本做 P0（口径修复 + Token 级历史上限 + 工具结果入预算 + 工具重试/熔断），P1 做统一抽象与 Agent/工作流接入，P2 才做检索式记忆、map-reduce 分段摘要、多 Agent 黑板。

***

## 1. 现状与缺口

### 1.1 现状总览

| 链路                                             | 上下文来源                                              | 受 `RagContextBudgeter` 约束 | 超窗 / 截断的现状                                        |
| ---------------------------------------------- | -------------------------------------------------- | ------------------------- | ------------------------------------------------- |
| 单轮 RAG（`RagChatService`）                       | system + 历史 + 检索 chunk + 当前问题                      | 仅「检索 chunk」受约束            | 历史只按轮次压缩；模型窗口取自 `modelChain.get(0)`               |
| 聊天工具循环（同一 `RagChatService`）                    | 上述 + 每轮 `AiMessage` + `ToolExecutionResultMessage` | 否                         | 单条工具结果 4000 字符；轮次上限 5；总 Token 无上限                 |
| Agent 多步（`AgentOrchestrator` / `StepExecutor`） | 步骤 prompt + 前序步骤摘要 + 步骤内检索                         | 仅「步骤内检索」受约束，且用默认窗口        | 每步摘要 500 字；步骤 prompt 无总预算；工具结果同上                  |
| 多 Agent（`rag-agent-multi`）                     | agent system + 输入 + 可选检索 + 工具结果                    | 仅「agent 内检索」受约束，且用默认窗口    | worker 产出转给 manager 时 500 字；汇总时全文拼接               |
| 工作流（`WorkflowEngine`）                          | `RunContext.vars` + `nodeOutputs`                  | 仅 RETRIEVAL 节点受约束         | LLM 节点渲染 `{{nodeId.text}}` 全文；`context_json` 全文落库 |
| 工具执行（`rag-tool`）                               | 不涉及上下文，但产出进入上下文                                    | 否                         | 4000 字符截断；无重试/熔断/降级                               |

### 1.2 单轮 RAG 主链路

- 检索发生在 [RagChatService.java:320-331](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L320-L331)，返回的 `retrievedMatches` 同时被用于「检索指标」「上下文拼接」「来源卡片」「正确率计算」。
- 检索指标在 [RagChatService.java:342-347](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L342-L347) 用**裁剪前**的列表计算 `coverageRate` / `hitRate`。
- 预算裁剪发生在 [RagChatService.java:512-524](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L512-L524)，`applyRagContextBudget` 会**原地修改** `retrievedMatches`。
- 预算公式在 [RagContextBudgeter.java:78-89](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/util/RagContextBudgeter.java#L78-L89)：`min(硬上限, floor(模型窗口 × 输入占比) - 已占用 prompt Tokens)`，下限为单条最少保留值。
- 已占用 prompt 的估算在 [RagChatService.java:1249-1267](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1249-L1267)，另加 64 Tokens 模板开销（[RagChatService.java:1202](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1202)）。
- 默认参数：硬上限 6000、兜底窗口 8192、输入占比 0.6、单条最少 120 Tokens（[RagContextBudgeter.java:26-33](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/util/RagContextBudgeter.java#L26-L33)），对应配置项见 [RagProperties.java:88-97](../../../../rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java#L88-L97)。

**结论**：这是全系统唯一一处「拿到了真实模型窗口并按 Token 裁剪」的地方，但它只保护检索片段，且与指标口径已经出现错位（见 §5.2 C1）。

### 1.3 历史消息与压缩

- 历史装配在 [RagChatService.java:1159-1199](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1159-L1199)：先全量查出会话消息，再交给压缩服务。
- 压缩入口在 [RagChatService.java:1185-1196](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1185-L1196)：启用分层压缩走 `ContextCompressionService.compressHistory`，禁用则按 `maxHistoryMessages` 条数截断。
- 分层压缩的层次与责任链见 [ContextCompressionService.java:31-58](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/ContextCompressionService.java#L31-L58)，三级为「近期原文 / 中期摘要 / 远期事实」，Prompt 版本为 `v1`（[ContextCompressionService.java:64-65](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/ContextCompressionService.java#L64-L65)）。
- 分层参数：近期 4 轮、中期 6 轮、远期事实 400 Tokens、摘要输出上限 600 Tokens、摘要超时 20 秒（[RagProperties.java:75-86](../../../../rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java#L75-L86)）。

缺口在于：**`maxHistoryTokens`（[RagProperties.java:70-71](../../../../rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java#L70-L71)）声明后在全仓库没有任何读取点**。也就是说历史压缩只按「条数 / 轮次」控制，一句话里塞十万字也照样进 prompt。压缩阈值 `maxHistoryMessages=20` 是条数阈值，不是 Token 阈值。

### 1.4 Agent 多步

- 编排入口在 [AgentOrchestrator.java:88-123](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/service/AgentOrchestrator.java#L88-L123)，最大步数取 `AgentProperties.maxSteps`（默认 15，[AgentProperties.java:12-20](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/config/AgentProperties.java#L12-L20)）。
- 跨步骤上下文是一个 `StringBuilder`，每步成功后追加 `resultSummary`（[AgentOrchestrator.java:396-401](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/service/AgentOrchestrator.java#L396-L401)），`resultSummary` 在 [StepExecutor.java:282-298](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/StepExecutor.java#L282-L298) 被硬截断为 500 字。
- 步骤 prompt 组装在 [AgentOrchestrator.java:493-517](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/service/AgentOrchestrator.java#L493-L517)：`用户原始目标 + 全部前序步骤摘要 + 当前步骤定义`，**没有任何 Token 预算**。15 步 × 500 字 ≈ 7500 字，加上步骤检索上下文，很容易越过 8k 窗口。
- 步骤内检索在 [StepExecutor.java:92-116](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/StepExecutor.java#L92-L116)，用的是 `trimWithDefaults`，即固定 8192 兜底窗口。
- Reflection 重试会把反馈继续拼进 `stepPrompt`（[AgentOrchestrator.java:370-375](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/service/AgentOrchestrator.java#L370-L375)），重试次数取窗口 `agentMaxSteps` 与 5 的较小值，**反馈文本不截断**。
- HITL 恢复时重新聚合全部历史步骤摘要（[AgentOrchestrator.java:155-167](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/service/AgentOrchestrator.java#L155-L167)），同样无预算。

### 1.5 多 Agent

- 上下文容器 [MultiAgentContext.java:17-33](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/context/MultiAgentContext.java#L17-L33) 具备 `data` / `vars` / `loopIterations` / `history` 四块，并整体快照持久化（[MultiAgentContext.java:192-200](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/context/MultiAgentContext.java#L192-L200)）。
- 单个 Agent 执行在 [AgentInstanceExecutor.java:94-121](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/AgentInstanceExecutor.java#L94-L121)，检索同样走 `trimWithDefaults`。
- **`hasMemory` 分支是个空壳**：[AgentInstanceExecutor.java:131-135](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/AgentInstanceExecutor.java#L131-L135) 取出了 `ctx.getHistory(agentId)` 却没有拼进 `messages`，注释也写明「初始仅放 system+user」。历史记忆在单 Agent 内实际未生效。
- 层级协作里，manager 规划摘要传给 worker 时截断 500 字（[CrewCoordinator.java:186-194](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/crew/CrewCoordinator.java#L186-L194)），但 manager 汇总时把**所有 worker 全文**拼接（[CrewCoordinator.java:198-209](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/crew/CrewCoordinator.java#L198-L209)），worker 越多越容易超窗。
- `truncate` 统一为 500 字（[CrewCoordinator.java:426-428](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/crew/CrewCoordinator.java#L426-L428)）。
- **多 Agent 没有接入故障转移链**：[AgentInstanceExecutor.java:137](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/AgentInstanceExecutor.java#L137) 直接 `modelCacheManager.getChatModel(...)`，与 chat / agent / workflow 的 `BlockingModelFailover` 口径不一致。

### 1.6 工作流

- 运行上下文 [RunContext.java:14-46](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/dto/RunContext.java#L14-L46) 保存 `vars`（含 `lastOutput`）、`nodeOutputs`、`loopIterations`、`pendingNodeIds`、`safeMode`、调试游标。
- 每个节点输出整体写入 `nodeOutputs` 并把 `text` 写进 `lastOutput`（[WorkflowEngine.java:613-623](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/WorkflowEngine.java#L613-L623)），随后整份 `RunContext` 序列化进 `wgh_workflow_run.context_json`。
- LLM 节点在 [LlmNodeExecutor.java:47-92](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/executor/LlmNodeExecutor.java#L47-L92) 只做变量渲染（`systemPrompt` / `prompt`）后直接调用模型，**没有输入预算、没有历史、没有工具**。`{{nodeId.text}}` 或 `{{lastOutput}}` 一旦指向 LLM 节点输出，就是全文进 prompt。
- RETRIEVAL 节点在 [RetrievalNodeExecutor.java:48-58](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/executor/RetrievalNodeExecutor.java#L48-L58) 先按 `topK` 截断，再用 `trimWithDefaults(matches, 0)` 裁剪——`promptTokens` 传 0，意味着预算按满额 6000 计算，不考虑下游 prompt 已占用。
- TOOL 节点在 [ToolNodeExecutor.java:53-57](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/executor/ToolNodeExecutor.java#L53-L57) 直接取工具文本作为 `text`，无兜底。
- AGENT 节点的轻量实现里，前序步骤结果同样是摘要拼接（[AgentNodeExecutor.java:119-151](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/executor/AgentNodeExecutor.java#L119-L151)），且显式不传工具。
- 硬上限只有「节点执行次数 1000」「子流程嵌套 3 层」（[WorkflowEngine.java:43-44](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/WorkflowEngine.java#L43-L44)），与 Token 无关。

### 1.7 工具执行

- 常量集中在 [ToolConstants.java:9-23](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/constant/ToolConstants.java#L9-L23)：建连 5 秒、默认超时 10 秒、结果最多 4000 字符、聊天工具轮次最多 5。
- HTTP 执行在 [HttpToolExecutor.java:72-153](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/executor/HttpToolExecutor.java#L72-L153)：首跳 SSRF 校验、手动跟随重定向并逐跳校验、响应体按 2MB 上限读取、结果超 4000 字符截断并追加 `...[truncated]`（[HttpToolExecutor.java:139-141](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/executor/HttpToolExecutor.java#L139-L141)）。
- 所有异常统一转成 `Error: <类名> - <message>` 文本回给模型（[HttpToolExecutor.java:145-152](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/executor/HttpToolExecutor.java#L145-L152)）。
- 工具分发在 [ToolExecutionEngine.java:95-118](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/executor/ToolExecutionEngine.java#L95-L118)，未知工具也返回 `Error: Tool '...' is not available...`（[ToolExecutionEngine.java:127-134](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/executor/ToolExecutionEngine.java#L127-L134)）。
- 全仓库 `rag-tool` / `rag-llm` 中没有任何 `retry` / `backoff` 实现。

### 1.8 模型故障转移现状

- 流式版本 [StreamingModelFailover.java:88-118](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/failover/StreamingModelFailover.java#L88-L118)：首内容前失败切下一个模型，已产出内容后失败不切链。
- 同步版本 [BlockingModelFailover.java:57-95](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/failover/BlockingModelFailover.java#L57-L95)：由调用方在拿到首个响应后 `markStarted()`，此后失败不再切链，避免工具副作用重复执行。
- 链解析 [ModelFailoverService.java:29-52](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/service/ModelFailoverService.java#L29-L52)：窗口 `route_group` → 模型自身 `route_group` → 单模型。
- 已有预算可观测指标：`RAG_CONTEXT_TOKENS{kind=budget|original|kept}`、`RAG_CONTEXT_DROPPED{kind=chunk|truncated}`（[RagMetrics.java:116-127](../../../../rag-backend/rag-common/src/main/java/com/wgh/rag/common/metrics/RagMetrics.java#L116-L127)）。

**模型级切链已有，工具级没有。** 这两者的边界必须显式定义，否则会出现「工具 404 → 以为模型不行 → 切模型 → 新模型重复发起同样的错误调用」的浪费。

### 1.9 缺口清单

| 编号  | 缺口                                         | 位置                                                                                         | 影响                         |
| --- | ------------------------------------------ | ------------------------------------------------------------------------------------------ | -------------------------- |
| G1  | 预算只在单轮 RAG 生效，Agent/多 Agent/工作流用固定 8192 窗口 | `RagContextBudgeter` 的 `trimWithDefaults` 调用点                                              | 大窗口模型被过度裁剪，小窗口模型仍然超窗       |
| G2  | `maxHistoryTokens` 配置从未读取                  | `RagProperties.java:70-71`                                                                 | 历史只按条数控，长消息可撑爆窗口           |
| G3  | 工具结果不参与总预算                                 | `RagChatService.java:847-849`、`StepExecutor.java:258-259`、`AgentInstanceExecutor.java:197` | 5 轮 × 多工具 × 4000 字符可远超模型窗口 |
| G4  | Agent 跨步骤上下文无预算                            | `AgentOrchestrator.java:396-401`                                                           | 15 步长任务必然膨胀                |
| G5  | 多 Agent 汇总全文拼接                             | `CrewCoordinator.java:198-209`                                                             | worker 数量线性放大              |
| G6  | 工作流 LLM 节点无输入预算，`context_json` 全文落库        | `LlmNodeExecutor.java:54-62`、`WorkflowEngine.java:613-623`                                 | 超窗 + 数据库膨胀                 |
| G7  | Agent 分支 `sources` 用未裁剪的预检索列表              | `RagChatService.java:437-439`                                                              | 展示的来源与实际喂给模型的上下文不一致        |
| G8  | 多 Agent 未接入故障转移                            | `AgentInstanceExecutor.java:137`                                                           | 单模型不可用即整体失败                |
| G9  | 多 Agent `hasMemory` 未注入历史                  | `AgentInstanceExecutor.java:131-135`                                                       | 「长期记忆」能力名不副实               |
| G10 | 工具无重试/熔断/降级                                | `HttpToolExecutor.java:72-153`                                                             | 瞬时抖动直接失败，且失败文本回灌模型放大 token |
| G11 | 工具失败以字符串前缀判定                               | `StepExecutor.java:250`、`RagChatService.java:828` 用 `startsWith("Error:")`                 | 工具正常返回以 `Error:` 开头时误判失败   |
| G12 | 检索指标用裁剪前列表，来源用裁剪后列表                        | `RagChatService.java:342-347` vs `512-524`                                                 | 同一响应内指标与来源口径不一致            |

***

## 2. 长任务上下文管理：主流方案对照

### 2.1 滑动窗口 + 滚动摘要（rolling summary）

**机制**：只保留最近 N 轮原文，更早的对话由 LLM 压成一段持续演进的摘要，摘要随每轮追加更新。

**适用条件**：对话型长任务，语义连续性要求中等，且「结论 / 决策」比「原始措辞」更重要。

**成本**：每轮或每 K 轮一次摘要调用；摘要本身 300–600 Tokens；有摘要漂移风险（多代摘要会丢失细节）。

**失效场景**：用户回指具体原文（「你刚才引用的第三条原文是什么」）、需要精确数字 / 条款编号、需要逐字复述。

**什么时候不该用**：任务本质是「对同一份长文档反复取原文」时，摘要会主动丢掉你正需要的东西——此时应用检索式记忆而非摘要。

**本系统落地层**：`RagChatService` 单轮层已有雏形（`ContextCompressionService` 的近期保留 + 中期摘要 + 远期事实），缺的是「Token 阈值触发」与「摘要预算纳入总预算」。**不建议**在 `AgentOrchestrator` 里再做一层滚动摘要，Agent 的跨步状态更适合结构化 scratchpad。

### 2.2 分层记忆：短期会话 / 长期向量记忆 / 结构化状态

**机制**：把「记忆」拆成三类职责不同的存储：

- 短期：当前会话消息（原文 + 摘要）；
- 长期：把重要事实 / 结论写进向量库，需要时按 query 召回；
- 结构化状态：scratchpad（当前已知事实）、todo list（待办）、plan state（计划与进度）——**不进 LLM 自由文本，而是键值 / 表格形式显式维护**。

**适用条件**：跨会话、跨任务的长期助手；或单任务内步骤很多、需要稳定的「当前状态」锚点。

**成本**：需要额外的存储模型与写入时机设计；结构化状态需要定义 schema，改 schema 有迁移成本。

**失效场景**：一次性问答、任务生命周期短于「记忆写入成本」的场景；结构化状态设计过细会退化成「自己实现一个数据库」。

**什么时候不该用**：Agent 步数 < 5、任务在一轮内完成的场景，引入 scratchpad 只会增加 prompt 体积与维护负担。

**本系统落地层**：

- 短期：`ContextCompressionService`（已有）；
- 长期：目前**没有**「写入向量库并在聊天时召回」的链路，需要新建（复用 `EmbeddingStore` 抽象与 pgvector 表）；
- 结构化状态：`AgentPlan` / `AgentStep` 表已经是天然的 plan state（[AgentOrchestrator.java:103-117](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/service/AgentOrchestrator.java#L103-L117)），缺的是「把它渲染成受预算约束的紧凑状态块」，而不是把 `resultSummary` 平铺拼接。

### 2.3 检索式记忆

**机制**：历史消息、中间产物（工具结果、节点输出、子任务产出）写入向量库并打上 `sessionId / taskId / stepId` 元数据；需要时按当前 query 召回 Top-K，与当前上下文拼接。

**适用条件**：任务产物总量远大于窗口（长文档、几十次工具调用、多 Agent 并行产出）；用户会回指早期内容。

**成本**：写入 + embedding 调用成本；召回质量依赖切分与元数据；存在「召回到过期中间产物」的风险。

**失效场景**：需要完整时序推理（「第一步到第五步分别做了什么」）；产物本身很短，直接塞进上下文更便宜。

**什么时候不该用**：任务总上下文 < 窗口的 30% 时，检索式记忆纯属增加延迟与失败面。

**本系统落地层**：`AgentStep.observation` / `resultSummary`、`WorkflowNodeRun.output_json` 已经在库里，**具备天然的写入源**；缺的是「按 sessionId/taskId 建向量索引 + 召回接口」。建议放在 P2，并严格按 session 隔离。

### 2.4 结构化压缩：要点 / 表格 / 键值对

**机制**：不生成自然语言摘要，而是要求模型输出固定结构（JSON / 表格），只保留可复用字段。相比摘要，信息密度更高、可校验、可增量更新。

**适用条件**：工具返回的 JSON / 列表、多 Agent 并行产出的对比结果、需要跨步骤精确引用的实体与数值。

**成本**：需要为每类产物定义 schema；模型可能输出非法 JSON，需要结构修复与降级。

**失效场景**：产物是叙事性文本（如分析报告正文），强制结构化会丢论证逻辑。

**什么时候不该用**：探索型任务，前期还不知道哪些字段重要时，过早固定 schema 会逼模型丢信息。

**本系统落地层**：

- 工具结果：在 `ToolInvoker` 内做「结果归一化」——JSON 走字段抽取 + 摘要，纯文本走要点抽取；
- Agent：`resultSummary` 由「前 500 字」改为「按 `outputType` 生成结构化摘要」（`PlanDTO.StepDTO.outputType` 已有 `DATA / ANALYSIS / REPORT / ACTION / FINAL_ANSWER`）；
- 工作流：节点输出可声明 `outputSchema`，LLM 节点只渲染被引用字段而非整段 `text`。

### 2.5 分段执行 + 子任务委派（sub-agent / map-reduce）

**机制**：把长输入切成多段，每段独立调用模型产出中间结果，再由汇总调用合并（map-reduce）；或把子任务委派给独立上下文的子 Agent，只回传结论。

**适用条件**：单次输入远超窗口（整篇长文档总结）、任务可自然分片（按章节 / 按数据源）、子任务之间弱耦合。

**成本**：调用次数与 Token 总量显著上升；需要对中间结果做持久化（否则失败无法续跑）；汇总阶段仍有超窗风险。

**失效场景**：强顺序依赖的任务（第 N 步必须看到第 N-1 步全文）；需要全局一致性推理的任务。

**什么时候不该用**：只是在窗口边缘轻微超出（超出 10%–20%）时，优先做裁剪与压缩，map-reduce 的成本远高于收益。

**本系统落地层**：

- `RagContextBudgeter.Result.singleChunkSaturated()`（[RagContextBudgeter.java:63-66](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/util/RagContextBudgeter.java#L63-L66)）已经把「单条 chunk 撑满预算」识别出来了，目前只打 WARN（[RagChatService.java:1238-1242](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1238-L1242)），这就是 map-reduce 的天然触发点；
- Agent 侧对应「子任务委派给子 Agent」，但注意现有 `AgentNodeExecutor` 已有一套轻量实现，扩展时应复用而不是再造。

### 2.6 缓存友好型上下文布局

**机制**：把**稳定前缀**（system prompt、工具定义、固定知识、会话约定）放在最前且逐字节稳定，把**可变尾部**（当前问题、检索片段、工具结果）放在最后，命中 provider 的 prefix / prompt cache（OpenAI、DeepSeek、通义等均支持自动前缀缓存）。

**适用条件**：同一窗口的高频连续对话、Agent 多步共享同一 system prompt、工具定义很多（工具 schema 往往是最大固定开销）。

**成本**：几乎为零，但需要纪律：前缀内不能放时间戳、随机 ID、按请求变化的内容。

**失效场景**：每轮 system prompt 都不同（如把动态检索结果拼进 system）；模型或网关不支持前缀缓存；窗口内容本身就很短。

**什么时候不该用**：单轮一次性请求，缓存命中率接近 0；或者前缀本身占比很小，收益可忽略。

**本系统落地层**：

- 现有布局基本正确：`SystemMessage` 在最前（[RagChatService.java:1163-1164](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1163-L1164)），检索内容拼在**当前 user 消息**里（[RagChatService.java:516-523](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L516-L523)），属于尾部变动；
- 风险点：`buildSystemPrompt` 注入了 `userName` 等动态变量（[RagChatService.java:1096-1108](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1096-L1108)），会降低跨用户缓存命中，但对「同一用户同一窗口」不构成破坏；
- **不要**为了缓存把检索片段移进 system prompt——那会破坏跨轮缓存（每轮检索结果不同），是本末倒置。

### 2.7 硬预算控制器

**机制**：给定模型的可用输入预算，按优先级给各类内容分配名额（system / 历史 / 检索 / 工具结果 / 状态块），超限时按优先级裁剪，并输出可观测指标（预算、实际占用、裁剪量、裁剪原因）。

**适用条件**：所有长链路；尤其是「内容来源多、单类内容不可控」的场景（工具密集、多 Agent、工作流）。

**成本**：需要统一的内容抽象（带类型、优先级、Token 成本、可裁剪性）；裁剪策略需要与业务语义对齐，否则会裁掉关键依据。

**失效场景**：预算估算严重失真（`TokenEstimator` 是字符启发式，[TokenEstimator.java:1-34](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/util/TokenEstimator.java#L1-L34)，中文 1 字 ≈ 1 token、其他 4 字符 ≈ 1 token，对代码 / JSON / 英文长文会低估）。

**什么时候不该用**：只有一个内容来源、且已被上游严格限制长度时，再包一层控制器是冗余抽象。

**本系统落地层**：这是**最该先做**的一层。落点是一个跨模块的 `ContextAssembler`：

- `RagChatService` 单轮：分配 system / 历史 / 检索 / 工具结果；
- `AgentOrchestrator` 多步：分配「原始目标 / 前序状态块 / 当前步骤定义 / 步骤检索 / 步骤内工具结果」；
- `WorkflowEngine` 多节点：分配「system / 显式引用变量 / lastOutput / 节点检索」；
- `LlmNodeExecutor` 单节点：对渲染后的 prompt 做最终裁剪与告警。

***

### 2.8 方案对照表

| 策略          | 主落地层                | 实现成本  | Token 收益 | 主要风险      | 当前版本建议            |
| ----------- | ------------------- | ----- | -------- | --------- | ----------------- |
| 滑动窗口 + 摘要   | `RagChatService`    | 低（已有） | 中        | 摘要漂移、丢原文  | **已有**，补 Token 阈值 |
| 分层记忆（结构化状态） | `AgentOrchestrator` | 中     | 高        | schema 维护 | **P1**            |
| 分层记忆（长期向量）  | 跨模块                 | 高     | 高        | 召回污染、隔离   | P2                |
| 检索式记忆       | Agent / 工作流         | 高     | 高        | 写入成本、召回噪声 | P2                |
| 结构化压缩       | 工具层 / Agent         | 中     | 高        | JSON 合法性  | **P1**            |
| 分段执行 / 子任务  | 单轮 RAG / Agent      | 中高    | 高        | 调用成本、汇总超窗 | P1（仅触发式）          |
| 缓存友好布局      | 全链路                 | 低     | 中（成本）    | 前缀不稳定则失效  | **P0**（纪律约束）      |
| 硬预算控制器      | 全链路                 | 中     | 高        | 估算误差      | **P0**            |

***

## 3. 工具调用兜底

### 3.1 失败分类

| 类别      | 典型信号                                      | 是否可重试    | 正确处理                   |
| ------- | ----------------------------------------- | -------- | ---------------------- |
| 网络 / 超时 | `HttpTimeoutException`、`ConnectException` | 可（幂等时）   | 指数退避重试                 |
| 限流      | 429、`Retry-After`                         | 可        | 尊重 `Retry-After`，退避更保守 |
| 鉴权失败    | 401 / 403                                 | 不可       | 直接失败并提示配置问题，不重试        |
| 参数非法    | 400、参数校验错误                                | 不可       | 结构修复（让模型改参数重试一次）       |
| 结果过大    | 触发 2MB / 4000 字符上限                        | 不重试      | 分页 / 字段裁剪 / 摘要         |
| 结果为空    | 200 但空体                                   | 一次       | 换等效工具或调整参数             |
| 结果非结构化  | HTML 当 JSON、乱码                            | 不重试      | 归一化 + 降级为文本摘要          |
| 参数幻觉    | 模型编造了不存在的工具参数 / 工具名                       | 不可       | 回灌明确错误 + 一次结构修复        |
| SSRF 拒绝 | `SsrfBlockedException`                    | **绝对不可** | 直接失败并记录安全事件            |

### 3.2 兜底策略

1. **重试（指数退避 + 抖动 + 幂等键）**
   仅对「网络 / 超时 / 429 / 502 / 503 / 504」重试；默认最多 2 次重试（共 3 次尝试），基数 300ms，抖动 ±30%，总耗时纳入单次工具超时预算。非幂等方法（POST/PUT/PATCH/DELETE）默认不重试，除非工具配置显式声明幂等并生成幂等键。
2. **降级**
   两级：① 等效工具降级（同一 `toolType` 下配置 `fallbackToolId`）；② 能力降级（工具不可用时，把「工具不可用」作为明确的 Observation 回给模型，让它改用知识库或直接说明限制）。**不要**在工具失败时自动切换 LLM 模型——模型换不了 404。
3. **结果裁剪与分页**
   把「盲目截断 4000 字符」升级为「结构化结果信封」：`{total, returned, truncated, nextCursor, content}`。截断时保留头尾关键片段并标注截断位置；支持 `cursor` 分页让模型主动取下一页。**截断必须显式可见**，避免模型把残缺 JSON 当完整数据。
4. **结构修复（二次调用让模型修正参数）**
   仅在「参数非法 / 参数幻觉」时触发，且**只允许一次**。把工具的原始错误（含期望 schema）作为 Observation 回给模型，要求它重新发起调用。必须有次数上限，否则会形成「模型反复改参数」的死循环。
5. **人工确认（HITL）**
   适用于有副作用且失败原因不可自动判定的操作（写操作、付费、删除）。现有 Agent HITL（`HitlGate`）与工作流 HITL 节点可直接复用；工具侧只需增加「该工具有副作用」的元数据标记。
6. **熔断与半开**
   按 `toolId` 维度统计连续失败次数，超过阈值（建议 5）后打开熔断 30 秒，期间直接返回「工具暂时不可用」而不发起真实请求；半开状态下放行 1 个探针请求，成功则关闭。这是防止「工具故障 + 重试」把延迟与出站流量放大数倍的关键。
7. **缓存与去重**
   仅对幂等只读工具（GET / 明确声明只读的 MCP 工具）做短期缓存，键为 `toolId + 归一化参数`，TTL 建议 60 秒；同一轮 ReAct 内重复调用相同参数直接命中缓存。写操作绝不缓存。
8. **超时预算分配**
   两层预算：单次调用超时（现有 `timeoutMs`，上限 30 秒）与单任务总工具时间预算（建议 120 秒）。总预算耗尽后，剩余工具调用直接返回「预算耗尽」，让模型基于已有信息收尾，而不是无限等待。
9. **并行扇出后取先到**
   同一轮多个互相独立的工具调用可并行执行；对「等效工具」可同时发起并取首个成功结果（hedged request）。注意：并行只对只读工具开放，写操作必须串行以保证可解释性。

### 3.3 与现有模型故障转移的边界和叠加顺序

**边界**：

- 模型故障转移解决「模型供应商不可用 / 返回 5xx / 首 token 前超时」；
- 工具兜底解决「工具目标不可用 / 参数错误 / 结果不可用」。

**叠加顺序（推荐）**：

```
一次用户请求
  └─ 模型尝试（BlockingModelFailover / StreamingModelFailover）
       └─ 工具调用
            ├─ 1. 熔断检查（打开则短路）
            ├─ 2. 缓存命中检查（只读）
            ├─ 3. 单次调用（受单次超时 + 总预算约束）
            ├─ 4. 失败分类
            │     ├─ 可重试 → 退避重试（≤2 次）
            │     ├─ 参数类 → 结构修复（≤1 次）
            │     └─ 不可恢复 → 返回结构化错误
            └─ 5. 结果归一化 / 裁剪 / 分页
       └─ 若「首个模型响应前」整体失败 → 切下一个模型
       └─ 若「已产出内容 / 已执行工具后」失败 → 不切链（保持现有语义）
```

**关键结论：先工具重试，再考虑切模型；且工具已执行后绝不切模型。** 理由：

- 工具 404 / 401 / 参数错误切模型毫无收益，新模型只会重复同样的错误调用；
- 现有 `BlockingModelFailover` 已经在 `markStarted()` 之后不切链（[BlockingModelFailover.java:66-77](../../../../rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/failover/BlockingModelFailover.java#L66-L77)），这个语义必须保留——否则写操作会被执行两次；
- 只有在「模型首响应前失败」时切模型才有意义，此时工具尚未执行，不存在副作用。

### 3.4 安全边界

- **SSRF 已做**（[SsrfGuard.java:20-31](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/security/SsrfGuard.java#L20-L31)、[103-138](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/security/SsrfGuard.java#L103-L138)、[143-176](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/security/SsrfGuard.java#L143-L176)），但**重试会放大攻击面**：每次重试都是一次新的出站请求，且重定向逐跳校验意味着一次调用可能产生多次 DNS 解析与连接。对策：
  - `SsrfBlockedException` **不重试**（确定性拒绝，重试无意义且放大探测）；
  - 增加「单任务出站请求总数上限」（建议 50），防止重试 × 扇出把系统变成打点器；
  - 熔断按 `toolId` 而非按 host，避免攻击者用大量不同 host 绕过熔断。
- **结果截断会丢关键信息**：现有 4000 字符硬截断（[HttpToolExecutor.java:139-141](../../../../rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/executor/HttpToolExecutor.java#L139-L141)）可能把 JSON 截成非法结构，模型会基于残缺数据编造结论。对策：结构化信封 + 显式 `truncated` 标记 + 分页游标；对 JSON 结果优先做字段级裁剪（保留 `id/name/status` 等关键字段）而不是字符级截断。
- **错误文本回灌**：当前失败以 `Error: ...` 字符串回给模型，模型可能把它当成正常数据继续推理。应改为结构化错误（`{errorClass, retryable, message}`），并在 prompt 中约定「errorClass 存在时不得编造结果」。

***

## 4. 分场景选型与设计

> 每个场景给出「最小可行（当前版本可做）」与「进阶（后续版本）」两档，并明确当前版本选哪一档。

### 4.1 单轮知识库问答（现有主链路）

| 项          | 内容                                                                                                                                                         |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 触发条件       | 窗口未启用 Agent、未绑定工作流，走 `RagChatService` 普通路线                                                                                                                 |
| 上下文策略（MVP） | 保持现有「分层压缩 + 检索片段预算」，补齐三件事：① 历史按 Token 上限截断；② `sources` / 准确率与模型实际上下文使用同一份裁剪后列表；③ 检索指标口径与来源口径一致                                                             |
| 工具兜底（MVP）  | 接入 `ToolInvoker`：只读工具重试 + 熔断 + 结构化错误；写操作不重试                                                                                                                |
| 预算与阈值      | 沿用 `contextMaxTokens=6000`、`contextWindowRatio=0.6`、`fallbackWindowTokens=8192`、`minChunkTokens=120`；新增 `historyMaxTokens=4000`、`toolResultMaxTokens=1200` |
| 失败兜底       | 检索为空 → 现有兜底话术；模型首响应前失败 → 现有切链；工具失败 → 结构化错误 + 模型自行收尾                                                                                                        |
| 可观测        | `RAG_CONTEXT_TOKENS{kind}`、`RAG_CONTEXT_DROPPED{kind}`、新增 `context_trim_total{segment}`、工具重试/熔断计数                                                          |
| 版本选择       | **MVP**；进阶（缓存友好前缀固化 + map-reduce 触发）留到 P1/P2                                                                                                               |

### 4.2 长文档多轮追问（同一文件反复深入）

| 项          | 内容                                                                                                                                                   |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| 触发条件       | 同一会话内连续多轮针对同一文件 / 同一章节追问；表现为检索命中集中在同一 `fileId`                                                                                                       |
| 上下文策略（MVP） | ① 会话级「文档锚点」：识别出主导 `fileId` 后，在上下文中固定保留该文档的**章节大纲 + 已引用 chunk 的 \[Sn] 索引**，避免每轮重新检索到不同片段；② 历史压缩阈值下调（长文档场景摘要更重要）；③ 检索 Top-K 提升但受预算强约束，优先同文档同章节邻近 chunk |
| 上下文策略（进阶）  | 该文档预先生成「章节级摘要树」，追问时先定位章节再取原文；单条 chunk 撑满预算时触发 map-reduce 分段摘要                                                                                        |
| 工具兜底       | 一般无工具；若配置了文档解析类工具，按只读工具重试                                                                                                                            |
| 预算与阈值      | `historyMaxTokens` 建议 3000（比通用场景更低）；`retrieve.topK` 提升到 8–10，但 `contextMaxTokens` 不提高，靠预算裁剪保证                                                        |
| 失败兜底       | 锚点文档被删除 / 重建 → 清空锚点并回退为普通检索，不能直接报错                                                                                                                   |
| 可观测        | 「同文档命中率」「锚点复用率」「单 chunk 饱和率」                                                                                                                         |
| 版本选择       | **MVP 的 ①③**；摘要树与 map-reduce 留到 P2                                                                                                                   |

**什么时候不该用文档锚点**：会话中用户频繁跨文档对比时，锚点会压制其他文档的召回，应在检测到多文档意图时自动解除锚点。

### 4.3 Agent 多步规划执行（十几步以上，含 HITL）

| 项          | 内容                                                                                                                                                                                                                       |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 触发条件       | 窗口 `agentEnabled=1`，Planner 产出步数 ≥ 6，或执行中出现 Reflection 重试 / 动态重规划                                                                                                                                                        |
| 上下文策略（MVP） | ① 把 `StringBuilder` 平铺摘要改为**结构化状态块**：`已完成步骤（stepId / 标题 / 一句话结论 / 关键产物引用）` + `当前计划剩余步骤`，整体受 `agentStepContextMaxTokens` 约束；② 每步 `resultSummary` 从「前 500 字」改为按 `outputType` 生成结构化摘要；③ 前序步骤的完整产物不进入 prompt，改为落库 + 按需检索（P2） |
| 上下文策略（进阶）  | 计划状态外置为「可增量更新的 JSON」，每步只回传 diff；子任务委派给独立上下文的子 Agent，只回传结论                                                                                                                                                                |
| 工具兜底（MVP）  | 同 §4.1，但额外要求：每步工具轮次上限（现有 `maxToolRoundsPerStep=5`）+ 单步工具总时间预算                                                                                                                                                            |
| 预算与阈值      | `agentStepContextMaxTokens=2000`；`agentStepSummaryMaxTokens=200`；Reflection 反馈截断到 300 Tokens；步骤内检索仍 6000 硬上限但改用真实模型窗口                                                                                                    |
| 失败兜底       | Reflection 连续不过 → 现有 re-plan；re-plan 失败 → plan FAILED；HITL 暂停 → 现有 `HitlGate`                                                                                                                                            |
| 可观测        | 每步 prompt Tokens、状态块 Tokens、re-plan 次数、HITL 暂停次数、步骤级时延                                                                                                                                                                   |
| 版本选择       | **MVP**；子 Agent 委派留到 P1 末 / P2                                                                                                                                                                                           |

**什么时候不该用结构化状态块**：步骤之间需要逐字传递长文本（如「把第 2 步生成的完整 SQL 原样传给第 3 步」）时，结构化摘要会破坏任务；此时应显式声明「该步骤产物全文传递」，并只对这一条走白名单式放行。

### 4.4 多 Agent 协作（多个执行器并行产出，结果合并）

| 项          | 内容                                                                                                                                                                              |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 触发条件       | `executionMode` 为 `CREW_*` / `GRAPH_ROUTED` / `GROUP_CHAT_*`                                                                                                                    |
| 上下文策略（MVP） | ① worker 产出统一走「结构化结果卡片」（结论 / 证据引用 / 置信度），合并时只拼卡片不拼全文；② 汇总 prompt 受 `multiAgentSummaryMaxTokens` 约束，超出时按 worker 重要度裁剪；③ 修复 `hasMemory` 未注入历史的问题——按 `agentId` 取最近 N 轮（受 Token 约束） |
| 上下文策略（进阶）  | 共享黑板（blackboard）：worker 写结构化条目，manager 按需读取；并行产出写向量库供后续召回                                                                                                                       |
| 工具兜底       | 同 §4.1；并行 worker 的出站请求总预算需按并发数分摊                                                                                                                                                |
| 预算与阈值      | 单 worker 输入 ≤ 4000 Tokens、单 worker 产出 ≤ 1500 Tokens、汇总 ≤ 6000 Tokens；worker 数 > 5 时强制启用结果卡片模式                                                                                   |
| 失败兜底       | 单个 worker 失败不阻断整体（标记 degraded）；全部失败才整体失败；**需补齐故障转移链**（当前 `AgentInstanceExecutor` 未接入）                                                                                           |
| 可观测        | 每 worker Tokens、合并裁剪率、degraded worker 数、并行墙钟时间                                                                                                                                  |
| 版本选择       | **MVP**；blackboard 留到 P2                                                                                                                                                        |

**什么时候不该用结果卡片**：worker 之间的产出需要逐字比对（如多版本文本 diff）时，卡片会抹掉差异；此时应保留原文并显式标注「diff 模式」。

### 4.5 工作流长链路（多节点串行 + 检索节点 + 工具节点）

| 项          | 内容                                                                                                                                                                                                              |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 触发条件       | 窗口绑定 `workflowId`，或独立工作流运行                                                                                                                                                                                      |
| 上下文策略（MVP） | ① LLM 节点增加输入预算：对渲染后的 `systemPrompt + prompt` 按节点模型窗口裁剪，超限告警并记录裁剪量；② 节点输出落库时增加大小上限（`nodeOutputMaxBytes`），超限只落摘要 + 全量存入对象存储 / 附件；③ `lastOutput` 改为「最近一个有意义的文本输出」，避免被大 JSON 覆盖                                     |
| 上下文策略（进阶）  | 节点声明 `outputSchema`，下游只能引用声明字段；子流程上下文隔离（子流程结束只回传显式声明输出）                                                                                                                                                         |
| 工具兜底       | TOOL 节点接入 `ToolInvoker`；`safeMode=true` 时保持现有写操作拦截（[ToolNodeExecutor.java:38-41](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/executor/ToolNodeExecutor.java#L38-L41)），且**安全模式下一律不重试写操作** |
| 预算与阈值      | 单节点 prompt ≤ 0.7 × 模型窗口；`nodeOutputMaxBytes=64KB`；`workflowContextJsonMaxBytes=256KB`；节点执行次数沿用 1000                                                                                                             |
| 失败兜底       | 节点 `onError=CONTINUE` 时写入结构化失败输出；HITL 暂停沿用现有断点恢复                                                                                                                                                                |
| 可观测        | 节点级 prompt Tokens / 输出 Tokens / 裁剪次数、`context_json` 大小分位、单节点耗时                                                                                                                                                  |
| 版本选择       | **MVP**；outputSchema 与子流程隔离留到 P1                                                                                                                                                                                |

**什么时候不该用节点输出上限**：节点产出是不可分割的二进制引用（如生成文件 ID）时，应只落引用不落正文；上限策略要按节点类型区分，不能一刀切。

### 4.6 工具密集任务（一次任务几十次工具调用，返回体可能很大）

| 项          | 内容                                                                                                  |
| ---------- | --------------------------------------------------------------------------------------------------- |
| 触发条件       | 单任务工具调用次数 > 10，或单次结果 > 4000 字符，或出现重复参数调用                                                            |
| 上下文策略（MVP） | ① 工具结果入总预算：每轮只保留「最近 N 条完整结果 + 更早结果的要点摘要」；② 结构化信封 + 分页游标；③ 重复参数调用命中短期缓存（只读）                          |
| 上下文策略（进阶）  | 工具结果写入任务级向量库，模型通过「检索工具结果」间接访问历史；map-reduce 汇总大量结果                                                   |
| 工具兜底（MVP）  | 完整兜底链：熔断 → 缓存 → 单次调用 → 失败分类 → 退避重试（≤2）→ 结构修复（≤1）→ 结构化错误；单任务出站请求上限 50、总工具时间预算 120 秒                  |
| 预算与阈值      | `toolResultMaxTokens=1200`（单条进入 prompt 上限）、`toolRoundHistoryMaxTokens=3000`、`toolCallMaxPerTask=50` |
| 失败兜底       | 预算耗尽 → 返回「预算耗尽，请基于已有信息收尾」；熔断打开 → 返回「工具暂不可用」，不阻塞模型回答                                                 |
| 可观测        | 每类失败计数、重试成功率、熔断打开次数、单任务工具耗时分布、结果截断率                                                                 |
| 版本选择       | **MVP**；任务级向量记忆留到 P2                                                                                |

**什么时候不该用结果缓存**：工具返回带实时性语义（股价、库存）时，60 秒缓存也可能给出错误结论；此类工具应由工具配置声明 `cacheable=false`。

### 4.7 模型切换 / 故障转移期间的长任务

| 项          | 内容                                                                                                                     |
| ---------- | ---------------------------------------------------------------------------------------------------------------------- |
| 触发条件       | `modelChain.size() > 1` 且发生 `model_switch`，或跨步骤 / 跨节点切换了不同模型（如 Planner 与 Executor 不同模型）                                |
| 上下文策略（MVP） | ① **上下文快照一致**：切链前后使用同一份 `messages` / `stepPrompt`，不做重写；② 按「当前尝试模型」的窗口重新计算预算，而不是按链首模型；③ 切链后如需重放，只重放「稳定前缀 + 当前问题」，工具结果保留 |
| 上下文策略（进阶）  | 模型能力画像（窗口 / 是否支持工具 / 是否支持视觉），切链时自动降级能力（如视觉模型 → 文本模型时丢弃图片并说明）                                                           |
| 工具兜底       | 切链时若工具已执行，**禁止重放工具**（现有 `markStarted` 语义已保证）；若工具未执行，按新模型重新生成调用                                                         |
| 预算与阈值      | 预算必须基于 `attempt.model().getMaxContextTokens()` 实时计算；链内模型窗口差异 > 2 倍时，强制按最小窗口规划                                          |
| 失败兜底       | 全部模型失败 → 现有 `onAllAttemptsFailed` 文案；切链后再次失败且已产出内容 → 如实报错不重发                                                           |
| 可观测        | `model_switch` 次数、切换原因分布、切换后成功率、切换后平均延迟增量                                                                              |
| 版本选择       | **MVP**；模型能力画像留到 P2                                                                                                    |

**什么时候不该用「按当前模型重算预算」**：链内模型窗口一致时，重算无收益且增加复杂度，可缓存首个预算结果。

***

## 5. 落地建议

### 5.1 优先级清单

**P0（当前版本，低风险高收益，可在一个迭代内完成）**

| 项                  | 复用 / 新增                                     | 说明                           |
| ------------------ | ------------------------------------------- | ---------------------------- |
| 历史 Token 上限生效      | 复用 `RagProperties.Context.maxHistoryTokens` | 在历史裁剪处按 Tokens 而非条数截断        |
| `sources` / 指标口径统一 | 复用现有列表                                      | Agent 分支改用裁剪后列表；指标与来源使用同一份数据 |
| 工具结果入上下文预算         | 复用 `RagContextBudgeter`                     | 把工具结果当作一种「可裁剪内容」参与总额分配       |
| 工具重试 + 熔断 + 结构化错误  | 新增轻量 `ToolInvoker`                          | 不改变 `ToolExecutor` 接口，包一层    |
| 工作流 LLM 节点输入预算     | 复用 `RagContextBudgeter`                     | 渲染后裁剪 + 告警                   |
| 缓存友好布局纪律           | 无代码改动                                       | 约定 system 前缀稳定，动态内容只进尾部      |

**P1（下一迭代，需要新抽象）**

| 项                     | 复用 / 新增                                 | 说明                  |
| --------------------- | --------------------------------------- | ------------------- |
| 统一 `ContextAssembler` | 新增（跨模块，建议放 `rag-llm` 或新建 `rag-context`） | 承载「按类型 + 优先级分配预算」   |
| Agent 结构化状态块          | 新增（`rag-agent`）                         | 替换平铺摘要              |
| 多 Agent 结果卡片 + 故障转移接入 | 复用 `BlockingModelFailover`              | 修复 G8 / G9          |
| 工具结果结构化信封 / 分页        | 新增（`rag-tool`）                          | 与 `ToolInvoker` 一起  |
| 工作流节点输出上限与摘要落库        | 改表 / 改落库逻辑                              | `context_json` 大小控制 |

**P2（后续版本，需要存储与索引改造）**

| 项               | 说明                                   |
| --------------- | ------------------------------------ |
| 检索式记忆           | 新建会话/任务级向量索引，按 `sessionId/taskId` 隔离 |
| map-reduce 分段摘要 | 由 `singleChunkSaturated()` 触发        |
| 多 Agent 共享黑板    | 新增黑板存储与读写接口                          |
| 模型能力画像          | `LlmModel` 增加能力字段 + 切链降级策略           |

**需要改表的项**：会话级文档锚点（可存 `wgh_chat_session` 新列）、任务级记忆（新表）、节点输出摘要（`wgh_workflow_run` / `wgh_workflow_node_run` 新列）、工具兜底配置（`wgh_tool` 增加 `idempotent` / `fallback_tool_id` / `cacheable` 等列）。**配置类改动**：`wgh.rag.context.*` 扩展、`wgh.tool.fallback.*` 新增、`wgh.agent.context.*` 新增、`wgh.workflow.context.*` 新增。

### 5.2 与现有实现的冲突点

| 编号  | 冲突点                     | 现状                                                                                                                                                                                                                                                                                                                                                                                                                      | 建议                                                   |                          |
| --- | ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- | :----------------------- |
| C1  | `sources` 取预检索原始列表，绕过预算 | Agent 分支 `buildSourcesList(retrievedMatches)`（[RagChatService.java:437](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L437)），该列表未被 `applyRagContextBudget` 处理                                                                                                                                                                                                                         | 与普通路线统一：Agent 分支也基于步骤内裁剪后的结果生成来源，或明确标注「展示来源不参与模型上下文」 |                          |
| C2  | Agent 分支上下文无 `[Sn]` 编号  | 普通路线在 `extractRagContext` 里分配 `[Sn]`（[RagChatService.java:1269-1291](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1269-L1291)），`StepExecutor` 的 `extractRagContext` 只有「来源: kb - file」没有编号（[StepExecutor.java:317-335](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/StepExecutor.java#L317-L335)）                                                                           | 提取公共的引用编号生成器，Agent / 多 Agent / 工作流复用                 |                          |
| C3  | 工具结果不受上下文预算约束           | 三处直接 `messages.addAll(toolResults)`（[RagChatService.java:847-849](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L847-L849)、[StepExecutor.java:258-259](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/StepExecutor.java#L258-L259)、[AgentInstanceExecutor.java:197](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/AgentInstanceExecutor.java#L197)） | 由 `ContextAssembler` 统一分配，工具结果按优先级可裁剪                |                          |
| C4  | `maxHistoryTokens` 未生效  | 配置存在（[RagProperties.java:70-71](../../../../rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java#L70-L71)），无读取点                                                                                                                                                                                                                                                                                      | 补读取点，或在文档中明确废弃该配置                                    |                          |
| C5  | 工作流 LLM 节点全文渲染          | `VariableResolver.render` 后直接进 prompt（[LlmNodeExecutor.java:54-62](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/executor/LlmNodeExecutor.java#L54-L62)）                                                                                                                                                                                                                                           | 渲染后按模型窗口裁剪 + 对 `{{nodeId.text}}` 支持 \`{{nodeId.text  | maxTokens=800}}\` 之类显式上限 |
| C6  | `context_json` 全文落库     | 节点输出整体序列化（[WorkflowEngine.java:613-623](../../../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/WorkflowEngine.java#L613-L623)）                                                                                                                                                                                                                                                                              | 增加大小上限与摘要落库策略                                        |                          |
| C7  | 检索指标与来源口径不一致            | 指标用裁剪前（[RagChatService.java:342-347](../../../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L342-L347)），来源用裁剪后                                                                                                                                                                                                                                                                                 | 二者都用裁剪后，或都保留裁剪前并显式标注差异                               |                          |
| C8  | 多 Agent 无故障转移           | [AgentInstanceExecutor.java:137](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/AgentInstanceExecutor.java#L137) 直接取 ChatModel                                                                                                                                                                                                                                                                | 接入 `BlockingModelFailover`，与 Agent / 工作流口径一致         |                          |
| C9  | 工具成功判定靠字符串前缀            | `startsWith("Error:")`（[StepExecutor.java:250](../../../../rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/StepExecutor.java#L250)）                                                                                                                                                                                                                                                                                           | 改为结构化结果信封，用显式字段判定                                    |                          |
| C10 | 多 Agent 记忆未注入           | `hasMemory` 分支空实现（[AgentInstanceExecutor.java:131-135](../../../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/AgentInstanceExecutor.java#L131-L135)）                                                                                                                                                                                                                                                  | 注入最近 N 轮且受 Token 约束，否则删除该开关避免误导                      |                          |

### 5.3 验收口径

**怎么判断这套方案有效**（建议全部埋点，按周对比）：

| 指标                | 定义                                                           | 目标                    |
| ----------------- | ------------------------------------------------------------ | --------------------- |
| 超窗次数              | provider 返回 `context_length_exceeded` / `max_tokens` 类错误的请求数 | 上线后 0（当前无法统计，需先埋点）    |
| 裁剪率               | `1 - keptTokens / originalTokens`，按 segment 类型分              | 检索段 < 40%，工具结果段 < 60% |
| 单 chunk 饱和率       | `singleChunkSaturated()` 命中次数 / 总请求                          | 长文档场景 < 5%            |
| 工具重试成功率           | 重试后成功次数 / 重试总次数                                              | > 70%                 |
| 工具失败可解释率          | 返回结构化错误（含 `errorClass`）的失败占比                                 | 100%                  |
| 端到端时延             | P50 / P95 / P99                                              | 引入重试后 P95 增幅 < 20%    |
| Token 成本          | 每任务 input+output Tokens                                      | 长任务（≥10 步）下降 ≥ 25%    |
| `context_json` 大小 | P95 字节数                                                      | < 256KB               |
| 故障转移有效性           | 切链后成功率                                                       | > 80%                 |
| HITL 触发率          | HITL 暂停次数 / 长任务数                                             | 不异常升高（升高说明工具兜底不足）     |

**采样方法**：先在 dev 环境用固定评测集（现有 `eval` 模块）跑基线，再在生产埋点做连续 7 天对比；单次 LLM 打分不作为验收依据，需多种子多次采样取中位数。

***

## 6. 附录

### 6.1 配置项建议汇总

> 标注「已有」的为当前仓库已存在；标注「建议新增」的为本文提案，落地时需同步 `application*.yml` 与 `RagProperties`。

| 配置键                                             | 默认值       | 状态     | 用途                      |
| ----------------------------------------------- | --------- | ------ | ----------------------- |
| `wgh.rag.context.context-max-tokens`            | `6000`    | 已有     | 检索片段硬上限                 |
| `wgh.rag.context.context-window-ratio`          | `0.6`     | 已有     | 输入侧占模型窗口比例              |
| `wgh.rag.context.fallback-window-tokens`        | `8192`    | 已有     | 模型未配置窗口时兜底              |
| `wgh.rag.context.min-chunk-tokens`              | `120`     | 已有     | 单条最少保留                  |
| `wgh.rag.context.max-history-messages`          | `20`      | 已有     | 压缩触发条数                  |
| `wgh.rag.context.max-history-tokens`            | `4000`    | 已有但未生效 | 历史 Token 上限             |
| `wgh.rag.context.history-max-tokens`            | `4000`    | 建议新增   | 进入 prompt 的历史上限（可复用上一条） |
| `wgh.rag.context.tool-result-max-tokens`        | `1200`    | 建议新增   | 单条工具结果进入 prompt 的上限     |
| `wgh.rag.context.tool-round-history-max-tokens` | `3000`    | 建议新增   | 多轮工具结果累计上限              |
| `wgh.rag.context.reserve-output-tokens`         | `1024`    | 建议新增   | 输出预留，避免输入吃满窗口           |
| `wgh.agent.step-context-max-tokens`             | `2000`    | 建议新增   | Agent 前序状态块上限           |
| `wgh.agent.step-summary-max-tokens`             | `200`     | 建议新增   | 单步结构化摘要上限               |
| `wgh.workflow.node-prompt-max-ratio`            | `0.7`     | 建议新增   | LLM 节点 prompt 占窗口比例     |
| `wgh.workflow.node-output-max-bytes`            | `65536`   | 建议新增   | 节点输出落库上限                |
| `wgh.workflow.context-json-max-bytes`           | `262144`  | 建议新增   | `context_json` 上限       |
| `wgh.multi-agent.worker-output-max-tokens`      | `1500`    | 建议新增   | 单 worker 产出上限           |
| `wgh.multi-agent.summary-max-tokens`            | `6000`    | 建议新增   | 汇总 prompt 上限            |
| `wgh.tool.fallback.enabled`                     | `true`    | 建议新增   | 工具兜底总开关                 |
| `wgh.tool.retry.max-attempts`                   | `2`       | 建议新增   | 最大重试次数（不含首次）            |
| `wgh.tool.retry.backoff-ms`                     | `300`     | 建议新增   | 退避基数                    |
| `wgh.tool.retry.jitter-ratio`                   | `0.3`     | 建议新增   | 抖动比例                    |
| `wgh.tool.retry.idempotent-only`                | `true`    | 建议新增   | 仅幂等工具重试                 |
| `wgh.tool.circuit.failure-threshold`            | `5`       | 建议新增   | 熔断阈值                    |
| `wgh.tool.circuit.open-seconds`                 | `30`      | 建议新增   | 熔断打开时长                  |
| `wgh.tool.cache.ttl-seconds`                    | `60`      | 建议新增   | 只读工具结果缓存                |
| `wgh.tool.task.max-requests`                    | `50`      | 建议新增   | 单任务出站请求总数上限             |
| `wgh.tool.task.max-total-ms`                    | `120000`  | 建议新增   | 单任务工具总时间预算              |
| `wgh.tool.ssrf.max-redirects`                   | `3`       | 已有     | SSRF 重定向上限              |
| `wgh.tool.ssrf.max-response-bytes`              | `2097152` | 已有     | 响应体上限                   |

### 6.2 关键伪代码

**ContextAssembler（统一预算分配）**

```text
assemble(modelWindow, segments):
    budget = min(hardCap, floor(modelWindow * ratio) - reserveOutput)
    # segments 按 priority 升序（数字越小越先满足）
    for seg in sort(segments, by=priority):
        seg.cost = estimate(seg.content)
        if seg.cost <= remaining:
            keep(seg); remaining -= seg.cost
        else:
            if seg.trimmable:
                trimmed = trim(seg, remaining)
                keep(trimmed); recordDrop(seg, original - trimmed)
                remaining -= trimmed.cost
            else:
                recordDrop(seg, seg.cost)   # 不可裁剪则整段丢弃并告警
    metrics.observe(budget, keptTokens, droppedTokens, bySegmentType)
    if singleSourceSaturated():
        metrics.singleChunkSaturated()     # map-reduce 触发信号
    return assembledContent
```

优先级建议（数字越小越先满足）：`system(10) → 当前问题(20) → 结构化状态块(30) → 检索片段(40) → 近期工具结果(50) → 历史摘要(60) → 远期事实(70) → 早期工具结果(80)`。

**ToolInvoker（工具兜底链）**

```text
invoke(tool, args, budget):
    if circuitOpen(tool.id): return structuredError("CIRCUIT_OPEN", retryable=false)
    if tool.readonly and cacheHit(tool.id, args): return cached
    if budget.requestsExhausted() or budget.timeExhausted():
        return structuredError("BUDGET_EXHAUSTED", retryable=false)

    attempt = 0
    while attempt <= maxAttempts:
        try:
            raw = execute(tool, args, timeout=min(perCallTimeout, budget.remaining))
            normalized = normalize(raw)            # JSON 字段抽取 / 文本要点
            if normalized.truncated: normalized.cursor = nextCursor(raw)
            if tool.readonly: cache.put(tool.id, args, normalized, ttl)
            circuit.recordSuccess(tool.id)
            return normalized
        catch e:
            kind = classify(e)                     # NETWORK/RATE_LIMIT/AUTH/BAD_PARAM/SSRF/...
            if kind == SSRF: circuit.recordFailure(tool.id); return structuredError(kind, false)
            if !retryable(kind) or !tool.idempotent or attempt == maxAttempts:
                circuit.recordFailure(tool.id)
                return structuredError(kind, retryable(kind))
            sleep(backoff(attempt) * jitter())
            attempt++
```

**预算分配示意（单轮 RAG）**

```text
totalInputBudget = floor(modelWindow * 0.6)
reserve           = 1024                       # 输出 + 估算误差
usable            = totalInputBudget - reserve
system            = estimate(systemPrompt)      # 约 300–800
history           = min(remaining, historyMaxTokens)
retrieval         = min(remaining, contextMaxTokens, usable - system - history)
toolResults       = min(remaining, toolRoundHistoryMaxTokens)
```

### 6.3 与既有文档的关系

- 本文不替代 `design-detailed.md` 中的 RAG 主链路描述，只补充「多来源上下文的预算分配」与「工具兜底」两块；
- 上下文压缩的既有设计（`ContextCompressionService` 三级责任链）保持不变，本文只把它的输出纳入统一预算；
- 模型故障转移的既有语义（首内容前切链、已产出后不切）保持不变，本文只补充「工具级兜底与模型级切链的叠加顺序」。

***

## 7. 落地记录（2026-11-18）

本节只记录实现进度与提交号，不修改前文设计与方案内容。以下均为 `main` 上的本地提交（未 push）。

### 7.1 P0 — 已完成（6/6）

| 项 | 提交 | 落地说明 |
| --- | --- | --- |
| 历史 Token 上限生效 | `0ed790c` | `HistoryContextBudgeter` 按 Tokens 硬裁剪，默认 4000 |
| `sources` / 指标口径统一 | `27fce99`、`7a6070f` | 指标、来源、上下文使用同一份裁剪后列表 |
| 工具结果入上下文预算 | `a458197` | 单条 1200 / 累计 3000 Tokens，从最旧折叠 |
| 工具重试 + 熔断 + 结构化错误 | `3a52ab6` | `ToolInvoker` 失败分类、幂等重试、按 toolId 熔断、任务预算 |
| 工作流 LLM 节点输入预算 | `f27704c` | `WorkflowContextProperties` + `LlmNodeExecutor` 渲染后裁剪 |
| 缓存友好布局纪律 | 无代码改动 | 作为实现约定执行 |

### 7.2 P1 — 已完成（5/5）

| 项 | 提交 | 落地说明 |
| --- | --- | --- |
| 统一 `ContextAssembler` | `03ccb1c` | 新建 `rag-context` 模块，按类型 + 优先级分配预算 |
| 工具结果结构化信封 / 分页 | `c0679f8`（测试构造器适配 `e7318fa`） | `rag-tool` 结构化结果信封与分页 |
| Agent 结构化状态块 | `7c8e98b` | 结构化状态块替换 `StringBuilder` 平铺摘要，受 `agentStepContextMaxTokens` 约束 |
| 多 Agent 结果卡片 + 故障转移接入 | `2afd8ae` | 结果卡片、复用 `BlockingModelFailover`、Agent 记忆注入 |
| 工作流节点输出上限与摘要落库 | `9be4a10` | 节点输出与 `context_json` 落库上限、摘要截断 |

### 7.3 P2 — 已完成（4/4）

| 项 | 提交 | 落地说明 |
| --- | --- | --- |
| 检索式记忆 | `239c490` | 会话级检索式记忆；当前仅 `SESSION` 作用域 |
| map-reduce 分段摘要 | `5bf6ce2` | 单条 chunk 饱和时触发分段摘要 |
| 多 Agent 共享黑板 | `a70753d` | 共享黑板读写接口 |
| 模型能力画像 | `d6cdee3` | `LlmModel` 能力画像 + 切链降级策略 |

### 7.4 配套提交

| 提交 | 内容 |
| --- | --- |
| `8be84db` | 文件大小上限统一提升到 200MB |
| `baba00c` | 测试证据截图退出 Git 跟踪 |

### 7.5 遗留缺口（不在本方案范围内）

- 检索式记忆只接入 `SESSION` 作用域，任务级 / 跨会话记忆未接；无 TTL 清理，记忆会持续累积；记忆向量表按首次写入的 embedding 维度建立，更换维度需重建。
- map-reduce 分段摘要只在普通聊天链路生效，Agent / 多 Agent / 工作流链路未接；暂无集成测试。
- 多 Agent 黑板接口已就绪，但 GroupChat 汇总未使用黑板，仍按对话消息汇总。
- 模型能力画像后端已落地，管理端模型表单未暴露能力档位与 function calling 字段。
