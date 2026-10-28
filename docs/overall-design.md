# WGH RAG System — 总体设计文档

> 版本：v1.0｜日期：2026-10-28｜反向工程生成（源码逆向输出）

---

## 1. 文档概述

### 1.1 文档目的

本文档从现有源码反向工程提炼系统总体架构设计，用于新成员快速理解系统、架构演进参考、技术评审基线。

### 1.2 系统定位

**一套可独立部署的 RAG 增强型智能问答平台**，覆盖完整链路：知识库管理 → 向量检索 → 工具调用 → 分层上下文压缩 → Agent 自动规划执行 → 可视化工作流编排 → LLM 流式对话 → RAG 效果评估 → 分享会话。

### 1.3 技术栈总览

| 层次 | 技术 | 版本 | 选型理由 |
|------|------|------|---------|
| 前端 | Vue 3 + Vite + Pinia + Element Plus | 3.x / 5.x / 2.x | 社区成熟、组件丰富、SSE 流式友好 |
| 前端画布 | @vue-flow/core | 1.x | 原生 Vue DAG 画布，适配工作流编辑器 |
| 后端 | Spring Boot 3 + Java 21 | 3.x / 21 | 生态成熟、多模块编译友好、虚拟线程 |
| 数据 | PostgreSQL 16 + pgvector | 16+ | 向量存储与业务数据统一，复用连接池，零额外运维 |
| ORM | MyBatis-Plus | 3.5.x | 逻辑删除、分页、LambdaWrapper |
| LLM | LangChain4j | 1.19.0 | Java 生态最完整的 LLM SDK，ToolSpecification / EmbeddingStore 抽象 |
| 鉴权 | JWT (HS256) | - | 无状态、多租户友好、预留 OIDC |
| SSE | Spring SseEmitter | - | 流式响应原生支持，Agent / 工作流实时推送 |

---

## 2. 系统总体架构

### 2.1 分层架构图

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│  前端层（Vue 3 + Element Plus + VueFlow）                                        │
│                                                                                  │
│  ChatPlayground.vue ─── ShareChat.vue ─── ChatWindows.vue                        │
│  WorkflowEditor.vue ─── WorkflowList.vue ─── ToolManagement.vue                 │
│  KnowledgeBase.vue ─── LlmModel.vue ─── EmbeddingModel.vue ─── Login.vue        │
│                                                                                  │
│  组件：AgentPlanTree（计划树）、Layout（布局）、api/index.js（axios + SSE）       │
└───────────────────────────────┬──────────────────────────────────────────────────┘
                                │ HTTP REST (Result JSON) + SSE fetch (event stream)
                                │
┌───────────────────────────────▼──────────────────────────────────────────────────┐
│  Controller 层（rag-controller 模块，REST API 统一入口）                          │
│                                                                                  │
│  AuthController · ChatController · AgentController · WorkflowController          │
│  KnowledgeBaseController · LlmModelController · WorkflowTriggerController       │
│                                                                                  │
│  统一前置：JwtFilter 校验 Token → SecurityConfig 放行规则 → CORS → 统一异常处理    │
└───────────────────────────────┬──────────────────────────────────────────────────┘
                                │ 方法调用
                                │
┌───────────────────────────────▼──────────────────────────────────────────────────┐
│  业务模块层（Maven 多模块，各自独立职责）                                         │
│                                                                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │  rag-chat   │  │ rag-agent   │  │ rag-workflow│  │  rag-tool   │            │
│  │             │  │             │  │             │  │             │            │
│  │ RagChatSvc  │  │ AgentOrch   │  │ WorkflowEng │  │ ToolExecEng │            │
│  │ CompressSvc │  │ Planner     │  │ NodeExec    │  │ 4 类 Exec   │            │
│  │ EvalSvc     │  │ StepExec    │  │ VarResolver │  │ McpMgr      │            │
│  │ ShareSvc    │  │ ReflectEng  │  │ TriggerSch  │  │ SkillSvc    │            │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘            │
│         │                │                │                │                    │
│         └────────────────┼────────────────┼────────────────┘                    │
│                          │ 共同依赖                                                │
│  ┌───────────────────────▼──────────────────────────────────────────────────┐   │
│  │  公共服务层                                                               │   │
│  │  rag-llm（ModelCacheManager + 多厂商工厂）                                │   │
│  │  rag-kb（RagRetrieveService + 切分策略工厂）                              │   │
│  │  rag-config（RagProperties / AgentProperties / PgVectorProperties）       │   │
│  └───────────────────────┬──────────────────────────────────────────────────┘   │
└───────────────────────────┼───────────────────────────────────────────────────┘
                            │ MyBatis-Plus Mapper
                            │
┌───────────────────────────▼───────────────────────────────────────────────────┐
│  数据层（PostgreSQL 16 + pgvector）                                             │
│                                                                                 │
│  wgh_llm_model · wgh_embedding_model                                            │
│  wgh_knowledge_base · wgh_knowledge_file                                          │
│  wgh_chat_window · wgh_chat_session · wgh_chat_message                           │
│  wgh_tool                                                                        │
│  wgh_agent_plan · wgh_agent_step                                                │
│  wgh_workflow · wgh_workflow_version · wgh_workflow_trigger                     │
│  wgh_workflow_run · wgh_workflow_node_run                                       │
│                                                                                 │
│  pgvector 扩展：embedding 向量列（复用 HikariCP 连接池）                         │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 分层职责与边界

| 层 | 模块 | 核心职责 | 不做的事 |
|---|------|---------|---------|
| 前端 | rag-frontend | UI 渲染、SSE 事件驱动状态复位、表单校验 | 不含业务逻辑（全部在后端） |
| Controller | rag-controller | 参数校验、Result 包装、SSE 通道建立、路由分发 | 不含业务决策 |
| 业务模块 | rag-chat / rag-agent / rag-workflow / rag-tool | 各自独立业务逻辑，互不调用（通过公共服务层共享） | 不直接连 HTTP |
| 公共服务 | rag-llm / rag-kb / rag-config | 被多个业务模块复用的能力（LLM 调用 / 检索 / 配置） | 不含业务编排 |
| 数据 | PostgreSQL | 持久化 + pgvector 向量检索 | 不含业务逻辑 |

**关键设计约束**：业务模块之间**禁止直接依赖**，都通过公共服务层（rag-llm、rag-kb）获取底层能力。例如 rag-agent 和 rag-chat 都调用 ToolExecutionEngine、RagRetrieveService、ModelCacheManager，但彼此之间不直接引用。

### 2.3 模块依赖矩阵

```
rag-chat ──→ rag-tool（工具调用）
rag-chat ──→ rag-agent（窗口启用 Agent 时走 Agent 链路）
rag-chat ──→ rag-kb（RAG 检索）
rag-chat ──→ rag-llm（LLM 调用）
rag-chat ──→ rag-config（配置读取）

rag-agent ──→ rag-tool（步骤内工具调用）
rag-agent ──→ rag-kb（步骤内 RAG 检索）
rag-agent ──→ rag-llm（Planner / Executor / Critic 各独立模型）
rag-agent ──→ rag-config（AgentProperties）

rag-workflow ──→ rag-tool（TOOL 节点执行）
rag-workflow ──→ rag-kb（RETRIEVAL 节点执行）
rag-workflow ──→ rag-llm（LLM 节点执行）
rag-workflow ──→ rag-config（运行参数）

rag-tool ──→ rag-llm（Skill 子模型）
rag-tool ──→ rag-config（Skill base-path）

rag-common ←── 所有模块（Result/JWT/Security/MyBatisPlus）
```

---

## 3. 核心业务链路

### 3.1 普通 RAG 对话链路

```
用户输入 → ChatController.stream()
  │
  ├─ 解析 body：windowId + sessionId + message
  │
  ├─ 加载 ChatWindow（含知识库绑定、模型、评估方案）
  │
  ├─ [分层压缩] RagChatService → CompressionService
  │    history > max-history-messages(20) → 触发三层压缩
  │    近期 4 轮保留原文 + 中期 6 轮 LLM 摘要 + 远期关键事实
  │    结果持久化到 wgh_chat_session，后续请求复用
  │
  ├─ [RAG 检索] RagRetrieveService.searchMultiple
  │    按 kbIds 逐个检索 → 按 embedding modelId 分组避免维度混用
  │    默认 min_similarity=0.55 过滤低质结果
  │    计算 recall_rate / hit_rate / accuracy_rate
  │    SSE 推送: retrieve → sources
  │
  ├─ [LLM 生成] ModelCacheManager.getStreamingChatModel()
  │    组装 System + Context Compression + Retrieved chunks + User message
  │    同步 ReAct 循环（最多 5 轮 Tool Calling）
  │    SSE 推送: content × N → tool_call_start → tool_call_result
  │
  ├─ [评估异步] LLM-as-Judge（不阻塞 SSE）
  │    Faithfulness + Answer Relevancy
  │    自动选 2 个不同 provider 的 Critic 模型（排除聊天模型）
  │
  ├─ [持久化] wgh_chat_message（用户消息 + 助手消息 + 三率指标 + sources）
  │
  └─ SSE 推送: accuracy → done
```

### 3.2 Agent 自动规划链路

```
用户输入（窗口 agent_enabled=true）
  │
  ▼
ChatController.stream() → RagChatService.detectAgentEnabled()
  │
  ▼
AgentOrchestrator.orchestrate()
  │
  ├─ ① Planner.generate()
  │    LLM（GOAP prompt + 可用工具列表 + 可用知识库）→ Plan JSON
  │    结构化解析 → AgentStep DTO 列表（stepId, dependsOn, requiresTools, hitl）
  │    落库：wgh_agent_plan + wgh_agent_step（全量 PENDING）
  │    SSE: plan_created { steps, maxSteps }
  │
  ├─ ② 拓扑排序 + ready_steps 计算
  │    按 dependsOn 依赖图，ready = 所有 dependsOn 步骤已 SUCCESS
  │
  ├─ ③ 逐步执行循环
  │    for step in ready_steps:
  │      ├─ SSE: step_start { stepIndex, title }
  │      ├─ StepExecutor.execute(step, context)
  │      │    ├─ 构建 Step Prompt（前置步骤 result_summary 注入）
  │      │    ├─ 内部 ReAct 循环（工具调用 + RAG 检索 + LLM 输出）
  │      │    └─ 返回 StepResult（observation, toolCalls, ragSources, thought）
  │      ├─ ReflectionEngine.evaluate(step)
  │      │    ├─ Critic LLM 打分（Relevance / Correctness / Completeness）
  │      │    ├─ score < threshold → retry（最多 N 次）
  │      │    └─ SSE: step_reflection { score, feedback, retryCount, pass }
  │      ├─ step.hitl=true && window.agent_hitl_enabled=true
  │      │    └─ SSE: step_hitl_pending → plan_paused → PAUSED 状态落库
  │      │         用户确认 → REST /api/agent/steps/{id}/hitl-confirm
  │      │         注入 userInput → SUCCESS → 继续执行
  │      ├─ step FAILED → 可触发 Planner 动态重规划
  │      ├─ 落库：wgh_agent_step status + result_summary
  │      └─ SSE: step_success / step_failed
  │
  ├─ ④ 汇总最终答复
  │    所有步骤 result_summary 汇总 → Final Answer LLM 生成
  │    落库：wgh_chat_message 助手消息（补写）
  │
  └─ SSE: plan_complete / plan_failed
```

### 3.3 工作流执行链路

```
POST /api/workflows/{id}/run  body: { input }
  │
  ▼
WorkflowService.run()
  │
  ├─ ① 图校验（GraphValidator）
  │    必须 1 START、≥1 END、START 无入边、END 无出边
  │    除回边外无环（DFS 检测）
  │    条件/循环节点 sourceHandle 合法
  │
  ├─ ② 创建运行实例
  │    wgh_workflow_run（status=RUNNING, workflow_version=当前版本快照）
  │    wgh_workflow_node_run（全量 PENDING）
  │    初始化 run.context_json（nodeOutputs={}, loopIterations={}）
  │
  ├─ ③ 调度引擎（WorkflowEngine）
  │    frontier = [startNodeId]
  │    pendingInEdges[target] = 入边总数
  │    while frontier 非空:
  │      ├─ 取 nodeId → pendingInEdges[target]--
  │      ├─ pendingInEdges[target] <= 0 → 入队（fan-in 汇聚点）
  │      ├─ 线程池提交 NodeExecutor.execute(node, context)
  │      │    ├─ VariableResolver 渲染模板
  │      │    ├─ 按 nodeType 分发：LLM/RETRIEVAL/TOOL/CONDITION/LOOP/HITL/WORKFLOW...
  │      │    ├─ 写回 context.nodeOutputs[nodeId]
  │      │    └─ 持久化 node_run（RUNNING → SUCCESS/FAILED）
  │      ├─ SSE: node_start → node_output / node_error
  │      ├─ 分支选择：CONDITION 走 true/false 边，LOOP 走 loop/done 边
  │      ├─ HITL: PauseSignal → PAUSED 状态落库 → 等待 hitl-confirm
  │      └─ pendingInEdges 递减后继节点
  │
  └─ workflow_run.status = COMPLETED / FAILED / CANCELLED
     SSE: workflow_complete / workflow_failed / workflow_cancelled
```

### 3.4 工具调用链路

```
Agent StepExecutor / 工作流 TOOL 节点 / 普通 RAG ReAct
  │
  ▼
ToolExecutionEngine.execute(request, toolRegistry)
  │
  ├─ 1. 查找 ToolConfig（按 toolId 从 DB 加载）
  │
  ├─ 2. 按 toolType 分发到对应 Executor
  │    ├─ HTTP → HttpToolExecutor
  │    │    渲染占位符 {param} + 环境变量 ${ENV} → RestTemplate 调用
  │    ├─ MCP → McpToolExecutor
  │    │    McpClientManager 管理 stdio/Streamable HTTP 连接 → 调用 tool
  │    ├─ SKILL → SkillToolExecutor
  │    │    inline 模式: promptTemplate 直接传给 LLM（用 executorConfig.subModelId）
  │    │    directory 模式: 运行时读 SKILL.md → 解析 front matter → 追加 references/
  │    └─ FUNCTION_CALLING → FunctionCallingToolExecutor
  │         反射调用 handlerClass.method()
  │
  ├─ 3. 通用后处理
  │    结果 > 4000 字符 → 截断
  │    工具调用循环 > 5 轮 → 强制终止
  │
  └─ ToolInvocationRecord（status, durationMs, resultText）
```

---

## 4. 部署架构

### 4.1 单实例部署（开发/测试）

```
┌──────────────────────────────────────────────────────┐
│  Docker Compose                                      │
│  ├─ PostgreSQL 16 + pgvector  (端口 5432)            │
│  │   库: wgh_rag（15 张表 + 40+ 索引 + 种子数据）      │
│  │                                                    │
│  ├─ rag-app:8080  (Spring Boot)                      │
│  │   JWT 默认 secret / admin 账号                     │
│  │                                                    │
│  └─ vite dev:5173  (npm run dev)                     │
│      代理 http://localhost:8080                       │
└──────────────────────────────────────────────────────┘
```

### 4.2 生产部署（扩展方向）

| 组件 | 当前 | 生产扩展 |
|------|------|---------|
| rag-app | 单实例 | 多实例（SSE 粘性会话 / Redis Session 需新增） |
| PostgreSQL | 单实例 | 主从 + pgvector 读写分离 |
| 前端 | vite dev | 静态资源 → Nginx / CDN |
| 触发器 CRON | ThreadPoolTaskScheduler（单实例） | 多实例需分布式锁（Redisson / ShedLock） |
| Webhook | 直连 | Nginx 反代 + 限流 |

### 4.3 环境配置矩阵

| Profile | DB 连接 | JWT Secret | LLM Key | 用途 |
|---------|---------|-----------|---------|------|
| dev | 默认 localhost:5432 | 内置 | demo key | 本地开发 |
| test | docker compose | 内置 | 真实 key | 集成测试 |
| prod | 环境变量注入 | 环境变量（必须） | 环境变量 | 生产部署 |

所有 `wgh.rag.*` / `wgh.agent.*` / `wgh.workflow.*` 配置均可通过环境变量覆盖。

---

## 5. 非功能性设计

### 5.1 可靠性

| 设计点 | 实现方式 |
|--------|---------|
| Agent 降级 | Planner 超时/非法 JSON → 降级为普通 Tool Calling；StepExecutor 工具不可用 → 跳过 Action |
| Agent 状态恢复 | 所有状态落库 AgentPlan/AgentStep，进程重启后 RUNNING 步骤标记 FAILED |
| 工作流状态恢复 | 持久化 context_json（nodeOutputs + loopIterations + pendingNodeIds），hitl-confirm 可从断点续跑 |
| SSE 断开 | 发送异常仅记 warn，不误触发「降级为普通 RAG」；前端通过 state REST 轮询恢复 |
| pgvector 降级 | PgVectorEmbeddingStore 连接失败 → 跳过向量化，不阻断应用启动（降级开关） |

### 5.2 安全

| 设计点 | 实现方式 |
|--------|---------|
| JWT 鉴权 | HS256 签名，`SecurityConfig` 放行白名单（login / 分享页 public 部分） |
| SQL 注入 | MyBatis-Plus 参数化查询，禁止字符串拼接 SQL |
| XSS | 后端不返回原始 HTML，前端 Vue 自动转义；content 事件 JSON 包装传输文本 |
| 权限隔离 | 分享页按登录用户名隔离 userIdentifier，跨身份读不到对方会话 |
| 工具执行权限 | 窗口级 toolIds 白名单，每个聊天窗口只能调用配置的工具 |
| 逻辑删除 | 所有删除操作走 deleted 字段，物理删除需明确需求 |
| HITL 防越权 | HITL 步骤在暂停前不发 step_start，前端收到 step_hitl_pending 需按 payload 补建（含 DB 主键 id，否则 confirm 接口 404） |

### 5.3 性能

| 设计点 | 实现方式 |
|--------|---------|
| LLM 模型缓存 | ModelCacheManager 按 modelId 缓存 ChatModel/EmbeddingModel 实例，配置变更时 invalidate |
| 向量复用 | pgvector 复用 Spring HikariCP 连接池，不独立建连接 |
| 压缩复用 | 分层压缩结果持久化到 chat_session，窗口未滑动出压缩范围时直接复用 |
| 独立线程池 | 工作流运行、知识库文件解析、触发器调度各有独立 ThreadPoolTaskExecutor，禁止裸线程 |
| 向量分组 | 跨 KB 检索按 embedding modelId 分组，避免不同维度向量混用 |
| 工具截断 | 结果 > 4000 字符自动截断，避免上下文溢出 |
| 上下文压缩 | > 20 条历史才触发三层压缩，避免小对话不必要 LLM 调用 |

### 5.4 可观测性

| 设计点 | 实现方式 |
|--------|---------|
| SSE 实时事件 | Agent 10 类 + 工作流 8 类 + 工具 2 类事件，全部带精确 payload |
| 节点级 trace | 工作流 node_run 持久化 input/output/error/duration；Agent step 持久化 thought/observation/reflection |
| RAG 三率指标 | recall_rate / hit_rate / accuracy_rate 实时计算写入 chat_message |
| 运行历史抽屉 | 工作流列表页可展开查看节点级明细（含 iteration 轮次、耗时） |
| 版本快照 | 工作流每次保存自动快照，可回滚（生成新版本） |
| 前端刷新恢复 | Agent RUNNING 状态 2 秒轮询；工作流 PAUSED 状态自动弹 HITL 确认框 |

---

## 6. 扩展性设计

| 扩展方向 | 预留位置 | 当前状态 |
|---------|---------|---------|
| 多 Agent 协作 | rag-agent 模块可增量扩展 AgentInstance + MessageBus | 未实现（架构评估完成，按需开发） |
| 工作流节点类型 | NodeExecutor 按 nodeType switch，新增节点类型加 case + config 字段 | 已预留 TOOL/WORKFLOW 节点，未来可加 Webhook/Subgraph |
| Embedding 模型热切换 | ModelCacheManager 已有 invalidate 入口 | 已支持 |
| Skill 独立 Agent | SkillToolExecutor 已有 subModelId 字段 | 已支持 |
| OIDC 鉴权 | SecurityConfig 注释已标记扩展点 | 预留 |
| 生产级可观测性 | 当前仅日志 + DB 记录；可接入 LangSmith / ELK | 未集成 |
| 分布式调度 | CRON 触发器用 ThreadPoolTaskScheduler（单实例）；多实例需 ShedLock | 未实现 |
| 向量索引优化 | pgvector 自动建表 + 可选 IVF 索引；大数据量可加 HNSW | 预留 |
| 灰度发布 | 工作流版本快照已就绪；流量分配未实现 | 预留 |

---

## 附录 A：术语表

| 术语 | 定义 |
|------|------|
| SSE | Server-Sent Events，基于 HTTP 的单向流式推送协议，本系统 Agent/工作流/工具全部用此 |
| HITL | Human-In-The-Loop，人工确认节点，Agent 步骤或工作流节点执行到此暂停等待用户输入 |
| ReAct | Reason + Act 交替推理（Thought → Action → Observation），本系统工具调用循环的核心模式 |
| Plan-and-Execute | 先规划后执行（Planner 生成 DAG → 逐步执行），Agent 模块核心范式 |
| Reflection | 执行后自我反思（Critic LLM 打分 → 不合格重试），Agent 质量保证机制 |
| fan-out/fan-in | 并行分支展开 + 汇聚点等待所有上游完成，工作流引擎调度核心 |
| pgvector | PostgreSQL 官方向量扩展，本系统向量存储的唯一实现（复用 HikariCP 连接池） |
| PendingInEdges | fan-in 汇聚点的入边等待计数器，全部减到 0 才执行 |
| nextTick | Vue 异步渲染队列，用于避开 el-dialog teleport 竞争导致的 HITL 弹窗 display:none 问题 |

---

> **文档修订（2026-10-28）**：测试体系与质量门禁更新 —— 单元测试 374 项、功能 e2e 113 项、压测 7 场景 8/8 全绿；测试代码统一英文命名 + 中文注释并纳入 CI 静态检查。本次改动不涉及本文档描述的架构与接口设计变更，完整数据见 `docs/test-report-20261028.md`。
