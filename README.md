# WGH RAG System

一套可独立部署的 **Retrieval-Augmented Generation (RAG) 问答系统**，覆盖 **知识库管理 → 向量检索 → 工具调用 → 分层上下文压缩 → Agent 自动规划执行 → LLM 流式对话 → RAG 效果评估 → 分享会话** 完整链路。

## 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue 3 + Vite + Pinia + Vue Router + Element Plus + SCSS |
| 后端 | Spring Boot 3.x · Java 21 · Maven 多模块 |
| 数据 | PostgreSQL 16+ + pgvector 向量扩展 · MyBatis-Plus（逻辑删除 + 分页） |
| LLM | LangChain4j 1.19.0 · OpenAI / DASHSCOPE / DeepSeek / 通义 等（OpenAI 兼容协议） |
| 向量 | pgvector（PostgreSQL 内置向量扩展，复用 HikariCP 连接池） |
| 工具 | LangChain4j Tool Calling · MCP（stdio / Streamable HTTP）· HTTP · 本地反射调用 · Skill |
| Agent | 规划-执行-反思循环（Planner / StepExecutor / ReflectionEngine / AgentOrchestrator）+ HITL 人工确认 |
| 工作流 | 自研 DAG 引擎：9 类节点、并行 fan-out/fan-in、子流程嵌套、版本快照、定时/Webhook 触发、模板市场 |
| 鉴权 | JWT (HS256)，预留 OIDC 扩展 |
| 评估 | MRR / NDCG 检索指标 + LLM-as-Judge (faithfulness / answer_relevancy) |

## 模块结构

```
wgh-rag-system/
├── rag-backend/              # 后端（Maven 多模块）
│   ├── rag-app/              # 启动模块 + application*.yml + 集成测试
│   ├── rag-common/           # Result/PageResult、JWT、异常、MyBatisPlus、SecurityConfig
│   ├── rag-config/           # 自定义配置：RagProperties、PgVectorProperties
│   ├── rag-controller/       # REST API 层（Auth / Chat / KnowledgeBase / LlmModel / Agent）
│   ├── rag-agent/            # Agent 自动规划：Planner 规划 → StepExecutor 执行 → Reflection 反思 → 编排与持久化
│   │   ├── planner/          #   Planner（目标拆解为有序步骤，标记 hitl / 依赖 / 所需工具）
│   │   ├── reflection/       #   ReflectionEngine（LLM 打分 + 反馈，低于阈值触发重试）
│   │   └── service/          #   AgentOrchestrator（执行调度、SSE 事件、暂停/恢复/取消/重试/跳过）
│   ├── rag-kb/               # 知识库：上传 → 解析 → 切分 → 向量化 → 检索
│   ├── rag-llm/              # LLM 模型管理：DB 动态配置 + 多厂商兼容 + 缓存工厂
│   ├── rag-chat/             # 对话：会话/窗口管理、分层上下文压缩、流式 RAG、评估、分享
│   ├── rag-tool/             # 工具框架：ToolExecutor SPI + 四类执行器 + ToolDefinitionParser
│   │   ├── executor/         #   HttpToolExecutor / McpToolExecutor / SkillToolExecutor / FunctionCallingToolExecutor
│   │   ├── definition/       #   ToolDefinitionParser（OpenAI function JSON → ToolSpecification）
│   │   ├── demo/             #   OrderQueryHandler（FUNCTION_CALLING 演示 handler）
│   │   └── service/          #   ToolConfigService / SkillFileService
│   ├── rag-workflow/         # 工作流可视化编排：DAG 引擎 + 9 类节点 + 并行 fan-out/fan-in + 版本/触发器/模板
│   │   ├── engine/           #   WorkflowEngine（frontier 并发调度 + pendingInEdges fan-in 汇聚）
│   │   ├── node/             #   9 类节点执行器（START/END/LLM/RETRIEVAL/TOOL/WORKFLOW/CONDITION/LOOP/HITL/VARIABLE）
│   │   ├── trigger/          #   触发器调度（ThreadPoolTaskScheduler CRON + Webhook token）
│   │   ├── template/         #   WorkflowTemplateRegistry（内置 3 个模板）
│   │   ├── service/          #   WorkflowService / VersionService / TriggerService
│   │   └── mapper/           #   Workflow / WorkflowRun / WorkflowNodeRun / WorkflowVersion / WorkflowTrigger
│   ├── sql/                  # schema.sql（完整 DDL + 索引）+ data.sql（种子数据）+ init.sql（一键入口）
│   ├── scripts/              # 端到端测试脚本（test-agent-e2e.sh / test-agent-hitl-e2e.sh / test-workflow-e2e.sh）
│   └── pom.xml
└── rag-frontend/             # Vue 3 前端
    └── src/
        ├── api/index.js      # axios 封装 + SSE 流式
        ├── components/       # AgentPlanTree（执行计划树：步骤状态 / 反思分数 / HITL 确认）
        ├── router/           # 路由 + 守卫（/share/:token 需登录）
        ├── views/login/      # 登录（支持 redirect 回跳）
        ├── views/kb/         # 知识库管理 + 检索测试
        ├── views/chat/       # 调试对话 + 分享对话
        ├── views/chat-admin/ # 对话窗口管理
        ├── views/models/     # LLM / Embedding 模型配置
        ├── views/tools/      # 工具管理（含 SKILL 目录扫描 + 在线编辑 + 调试）
        └── views/workflow/   # 工作流编排：WorkflowList（列表 + 版本 + 触发器 + 运行历史 + 模板市场）/ WorkflowEditor（DAG 画布 + 节点配置 + 运行调试面板 + HITL 确认）
```

## 核心特性

### 1. 知识库与 RAG 链路

- 多格式文件解析：PDF / DOCX / DOC / XLSX / XLS / CSV / TXT / MD
- 多种切分策略：AUTO（自动路由）/ RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM
- pgvector 向量检索：默认 `min_similarity=0.55`，按 embedding 模型 ID 分组避免维度混用
- 文件级幂等：重新分割 / 删除文件时同步清理对应向量，避免重复数据
- 实时计算 **召回率（recall_rate）/ 命中率（hit_rate）/ 正确率（accuracy_rate）** 三率指标，写入 `wgh_chat_message` 并随 SSE 下发
- 未关联知识库的窗口自动跳过三率指标计算，避免误导

### 2. 工具调用（Tool Calling）

四类工具统一抽象为 `ToolExecutor` SPI，通过 `ToolExecutionEngine` 按类型分发：

| 类型 | 执行器 | executorConfig 关键字段 | 典型场景 |
|------|--------|------------------------|---------|
| **HTTP** | `HttpToolExecutor` | `url` / `method` / `headers` / `timeoutMs` | 调用任意 REST API（占位符 `{param}` 渲染、环境变量 `${ENV}`） |
| **MCP** | `McpToolExecutor` + `McpClientManager` | `transport=http` 配 `serverUrl` / `transport=stdio` 配 `command+args` / `timeoutMs` | 接入 Model Context Protocol 服务器（如 `@modelcontextprotocol/server-everything`） |
| **SKILL** | `SkillToolExecutor` | `mode=inline`（promptTemplate）/ `mode=directory`（skillPath 运行时读盘）/ `subModelId` / `subModelDefault` | 可复用的 Prompt 模板技能，支持子模型独立配置 |
| **FUNCTION_CALLING** | `FunctionCallingToolExecutor` | `handlerClass` / `method`（默认 `execute`） | 本地反射调用 Java 静态/实例方法（演示见 `OrderQueryHandler`） |

**通用规则**：
- `definition` 必须是 OpenAI function 格式 `{"type":"function","function":{name,description,parameters}}`，否则解析失败会被静默跳过
- 工具结果 > 4000 字符自动截断，避免上下文溢出
- 工具调用循环最多 5 轮，防死循环
- SSE 实时事件：`tool_call_start` / `tool_call_result`（含 success / durationMs / resultText）
- 窗口级独立配置：每个聊天窗口通过 `toolIds` 关联全局工具池，互不影响
- 工具调试：工具管理页面任意工具可在线调试，按 definition 自动生成动态表单（enum 下拉 / number / boolean / string），并提供「一键填入示例参数」

### 3. Skill 管理（数据库 + 磁盘目录双源）

- **磁盘目录**：扫描 `${wgh.skill.base-path}`（默认 `./skills`）下的子目录，识别 `SKILL.md` + YAML front matter（`description` / `subModelId`），支持 `references/` 资源目录
- **数据库记录**：`toolType=SKILL` 的 ToolConfig，executorConfig 内嵌 `promptTemplate`（inline 模式）或 `skillPath`（directory 模式）
- **统一管理页**：左侧合并列表（目录 → 绿色 tag；数据库 → 蓝色 tag；目录已导入显示勾选图标），右侧编辑器按来源切换操作（保存到磁盘 / 保存到数据库 / 导入数据库）
- **同名防重复**：导入时若数据库已存在同名 SKILL，**不创建/不更新**，返回 `duplicated=true` 给前端黄色提示
- **子模型**：`subModelId` 显式指定 > `subModelDefault=true` 继承窗口模型 > 都不设置走 skill 默认

### 4. 分层上下文压缩

对话历史超过 `max-history-messages`（默认 20 条）时触发三层压缩，避免长对话 token 爆炸：

```
全部历史 → 按窗口切分
            ├─ 近期 recent-window-rounds (4)   → 原文保留
            ├─ 中期 mid-term-window-rounds (6)  → LLM 生成 3-5 句摘要
            └─ 远期 剩余所有                     → LLM 提取 ≤8 条关键事实
组装结果  [System, 远期事实, 中期摘要, ...近期原文, 当前用户输入]
```

**Prompt 显式保留优先级**（中期和远期 prompt 均包含）：

1. 用户明确的需求、决策、指令
2. 已确认的业务规则或事实
3. 实体名称（人名、产品名、文件名、配置项）
4. 时间、数字、ID 等精确数据
5. 讨论中的假设、未解决问题
6. 背景闲聊、礼貌用语、重复表述（可删除）

**持久化与复用**：摘要和事实会保存到 `wgh_chat_session`（含来源轮次范围、模型名、Prompt 版本、时间戳），后续请求如果窗口未滑动出已压缩范围则直接复用，避免重复 LLM 调用。Prompt 版本 bump 后旧压缩自动失效。

**配置项**（`wgh.rag.context`）：

| 参数 | 默认 | 说明 |
|------|------|------|
| `max-history-messages` | 20 | 超过此条数触发分层压缩 |
| `max-history-tokens` | 4000 | 单轮输入 token 上限 |
| `enable-compression` | true | 总开关 |
| `recent-window-rounds` | 4 | 近期窗口（轮，保留原文） |
| `mid-term-window-rounds` | 6 | 中期窗口（轮，LLM 摘要） |
| `long-term-facts-tokens` | 400 | 远期对话 Token 预算（关键事实） |
| `summary-max-tokens` | 600 | LLM 摘要最大输出 Token |
| `summary-timeout-seconds` | 20 | 摘要 LLM 调用超时（秒） |

### 5. RAG 评估双轨

| 轨道 | 指标 | 说明 |
|------|------|------|
| 检索指标（METRICS） | MRR、NDCG@K、Hit Rate、Recall | 需要 ground truth（相关文档列表） |
| LLM-as-Judge | Faithfulness、Answer Relevancy | 用独立 LLM 对生成答案打分，不需要 ground truth |

- 评估结果实时写入 `wgh_chat_message`
- 对话窗口可配置评估方案（METRICS / LLM_JUDGE / BOTH / NONE）和 Judge 模型
- 未指定 Judge 时自动选择 2 个不同提供商的模型，且排除当前聊天使用的模型
- LLM-as-Judge 异步执行，不阻塞 SSE 流式响应

### 6. 会话与分享

- **会话管理**：每个窗口下支持多会话，左侧侧边栏滑动加载历史（按 update_time DESC，LIMIT 50），首条消息自动生成会话标题（取前 20 字），支持单会话删除（连带逻辑删所有消息）
- **分享链路**：窗口可开启 / 关闭分享，重新生成分享 token 旧链接立即失效；分享页要求登录（JWT），`userIdentifier` 取当前用户名，多用户共用一个分享链接时各自看到独立历史
- **会话隔离**：管理端按 `default_user`、分享端按登录用户名隔离 `userIdentifier`，跨身份读不到对方会话

### 7. Agent 自动规划执行

窗口级可选能力（`agent_enabled=1`），开启后对话走 Agent 分支：先由 Planner 把目标拆成有序步骤，再逐步执行 + 反思，而非单轮 Tool Calling。

```
用户目标 → Planner 拆解（步骤 / 依赖 / 所需工具 / hitl 标记）
         → 逐步执行 StepExecutor（可调用工具 + 检索知识库）
         → ReflectionEngine 评分（0-10，低于阈值重试，最多 N 次）
         → 全部通过 → 汇总最终答复 → 落库 + SSE 下发
```

**SSE 事件**（独立事件名，不包裹在单一 `agent_event` 中）：

| 事件 | 载荷 | 说明 |
|------|------|------|
| `plan_created` / `plan_complete` / `plan_failed` | `planId` / `planSummary` / `totalSteps` | 计划生命周期 |
| `plan_paused` / `plan_resumed` / `plan_cancelled` | `planId` / `reason` | HITL 暂停、恢复、取消 |
| `plan_updated` | `steps` | 执行中动态重规划 |
| `step_start` / `step_success` / `step_failed` | `stepId` / `stepIndex` / `stepTitle` / `latencyMs` / `toolCount` | 步骤执行 |
| `step_reflection` | `stepId` / `score` / `pass` / `feedback` / `retryCount` | 反思评分 |
| `step_hitl_pending` | `id` / `stepId` / `stepIndex` / `expectedInput` | 等待人工确认 |

**HITL 人工确认**：Planner 会把**有副作用或不可逆**的操作标记 `hitl=true`（删除数据、发送邮件/通知、下单支付退款、改配置/权限变更等）。窗口开启 `agent_hitl_enabled=1` 时，这类步骤执行前暂停（`plan_paused`），用户在计划面板内联确认后才继续；只读查询类步骤不会暂停。

**配置项**（`wgh.agent`，窗口级字段优先）：

| 参数 | 默认 | 说明 |
|------|------|------|
| `enabled` | false | 全局开关（窗口 `agent_enabled` 覆盖） |
| `max-steps` | 15 | 最大步骤数（窗口 `agent_max_steps` 覆盖） |
| `max-tool-rounds-per-step` | 5 | 单步骤内工具调用轮数上限 |
| `default-reflection-retries` | 3 | 反思重试次数（窗口 `agent_max_steps` 参与计算上限） |
| `default-reflection-threshold` | 7.0 | 反思通过阈值（窗口 `agent_reflection_threshold` 覆盖） |
| `reflection-enabled` | true | 反思循环总开关 |
| `planner-prompt-version` | v1 | Planner Prompt 版本（bump 后旧计划缓存失效） |

窗口侧字段：`agent_enabled` / `agent_max_steps` / `agent_planner_model_id` / `agent_executor_model_id` / `agent_critic_model_ids` / `agent_reflection_threshold` / `agent_hitl_enabled`。

**前端表现**：调试页与分享页右侧均展示执行计划树（步骤时间线、状态、反思分数徽章、HITL 确认、取消/重试/跳过）。刷新页面后通过 `GET /api/agent/sessions/{sessionId}/plan` 恢复计划快照，并对 `RUNNING` 状态每 2 秒轮询直到终态，随后自动补拉消息列表。

### 8. 工作流可视化编排

与 Agent 互补：Agent 适合"目标明确、步骤未知"的开放任务，工作流适合"步骤固定、需要审核、需要复用"的业务 SOP（合同审查、工单分流、报告生成、审批流等）。

#### 9 类节点能力

| 节点 | 输入 | 输出 | 关键配置 |
|------|------|------|---------|
| START | 运行输入 | `{ text }` | — |
| END | — | 模板渲染的最终输出 | `output` 模板（支持变量） |
| LLM | system + user prompt | `{ text }` | `modelId`、`systemPrompt`、`prompt` |
| RETRIEVAL | 查询词 | `{ text, sources[] }` | `kbIds[]`、`query`、`topK` |
| TOOL | 工具参数（支持变量） | `{ text, success }` | `toolId`、`arguments{}` |
| CONDITION | 表达式求值 | 走 true/false 分支 | `expression`（支持 `==`/`!=`/`>`/`<`/`contains`/`is_empty`） |
| LOOP | 循环体最大轮次 | 走 loop/done 分支 | `maxIterations`、`breakCondition` |
| HITL | 用户输入 | `{ text }` | `prompt` |
| VARIABLE | 赋值映射 | 写入 `vars.<name>` | `assignments[{name, value}]` |

#### 引擎核心：并行 fan-out/fan-in

```
frontier = [startNode]         // 就绪节点队列（线程池并发）
pendingInEdges[target]--       // 入边等待计数器
pendingInEdges[target] <= 0    // 所有上游完成才入队 → fan-in 汇聚
```

- CONDITION / LOOP 节点按 `sourceHandle`（`true`/`false`、`loop`/`done`）选择分支，未走的分支下游节点 `pendingInEdges` 不会被减到 0，自然跳过
- 子流程 WORKFLOW 节点：调用另一个确定性 DAG，最大嵌套 3 层，内部节点静默执行不上报 SSE

#### 变量引用

统一 `{{ path }}` 语法，节点执行前渲染：

| 表达式 | 含义 |
|--------|------|
| `{{input}}` | 本次运行输入 |
| `{{vars.orderId}}` | 变量池中的变量 |
| `{{n_llm_1.text}}` | 某节点输出的 `text` 字段 |
| `{{n_rag_1.sources.0.fileName}}` | 数组下标访问 |
| `{{loop.iteration}}` | 当前循环轮次（从 1 开始） |

#### 版本管理

- 每次保存自动快照到 `wgh_workflow_version`，只增不改
- 回滚旧版本 = 基于该版本生成新版本（v1 回滚 → 生成 v5）
- 版本列表弹窗显示"当前"绿色标签，当前版本的回滚按钮禁用

#### 触发器

| 类型 | 配置 | 运行时 |
|------|------|--------|
| **CRON** | 6 段表达式（秒 分 时 日 月 周） | `ThreadPoolTaskScheduler` 动态注册/注销，秒级触发 |
| **Webhook** | 系统生成免密 token | `POST /api/workflows/webhook/{token}` 静默运行，统计 fireCount + lastFireTime |

#### 模板市场

`WorkflowTemplateRegistry` 内置 3 个开箱即用模板：

| 模板 | 适用场景 |
|------|---------|
| 知识库问答 | 快速搭建"检索 → LLM → 结束"链路 |
| 人工审批流 | HITL 节点确认 → 条件分支 → 批准/拒绝 |
| 定期巡检流 | CRON 定时触发 → LLM 巡检 → 异常告警 |

实例化走标准创建链路（图校验 + v1 快照），LLM 节点占位 `modelId` 自动填充为第一个启用模型，避免校验拦截。

#### SSE 事件

| 事件 | payload |
|------|---------|
| `workflow_start` | `{ runId, workflowId, workflowName, totalNodes }` |
| `node_start` | `{ runId, nodeId, nodeType, nodeName, iteration }` |
| `node_output` | `{ runId, nodeId, nodeName, output, durationMs }` |
| `node_error` | `{ runId, nodeId, nodeName, error }` |
| `node_hitl_pending` | `{ runId, nodeRunId, nodeId, nodeName, prompt }` |
| `workflow_complete` | `{ runId, output, durationMs, executedNodes }` |
| `workflow_failed` | `{ runId, nodeId, error }` |
| `workflow_cancelled` | `{ runId }` |

#### 与 Agent 的边界

| 维度 | Agent | Workflow |
|------|-------|----------|
| 链路来源 | LLM 运行时动态生成计划 | 人工预先编排的固定 DAG |
| 稳定性 | 每次运行结果可能不同 | 固定可复现、可审计 |
| 适用 | 开放探索型任务 | 固定 SOP、需审核、需复用 |
| 共性 | SSE 事件风格、先落库后推送、HITL 暂停续跑、刷新轮询恢复、独立线程池 | 同左（刻意对齐） |

## 快速开始

### 1. 启动 PostgreSQL + pgvector

```bash
docker run -d --name postgres-with-vector \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=wgh_rag \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 初始化（会创建全部 8 张表 + 25 个业务索引 + 种子数据）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/init.sql
```

### 2. 后端

```bash
cd rag-backend
# 注意：多模块项目必须用 -am 才会使用本仓库代码，否则会读 ~/.m2 里的旧 jar
mvn install -pl rag-app -am -DskipTests
mvn spring-boot:run -pl rag-app
# 默认 http://localhost:8080 / 默认账号 admin / admin
```

### 3. 前端

```bash
cd rag-frontend
npm install
npm run dev   # http://localhost:5173
```

### 4. 配置 LLM 模型

登录后进入 **模型管理** 页面，添加至少一个：
- **Embedding 模型**：用于向量化（如 text-embedding-3-small / text-embedding-v3）
- **Chat 模型**：用于对话生成
- 可选标记 `compression_model=true`：独立的压缩专用模型，避开对话模型计费

**注意**：
- DASHSCOPE 提供商使用 OpenAI 兼容端点时需把 provider 改为 `CUSTOM_OPENAI`（SDK 路径拼接兼容问题）
- 通义 / DeepSeek 在 LangChain4j 1.19.0 的 `community` 包下，不在核心包

### 5. 体验工具调用

打开 **聊天窗口管理** → 编辑窗口 → 关联工具（按 SKILL / MCP / HTTP / Function Calling 分组显示）→ 保存 → 调试对话，发送一条会触发工具的提问（例如「查一下我的订单」「调用 echo 说 hi」「现在系统健康状况怎么样」），即可看到 `tool_call_start` / `tool_call_result` SSE 事件。

### 6. 体验 Agent 自动规划

编辑聊天窗口 → 打开「🤖 Agent 自动规划执行」→ 配置最大步骤、Planner / Executor 模型、反思阈值、HITL 开关 → 保存。调试对话或分享页发送一个多步目标（如「查询北京天气并整理结果」），右侧会出现执行计划树，实时展示步骤状态与反思分数。

### 7. 体验工作流编排

进入主菜单「工作流编排」→ 点击右上角「模板市场」→ 选择「人工审批流」模板 → 命名后一键创建 → 自动跳转画布编辑器。

在画布上拖入节点、连线、填写参数，点右侧「运行调试」标签页填写输入 → 点执行 → 实时观察节点轨迹面板（绿色=成功、红色=失败、橙色=暂停等待人工确认）。运行过程中遇 HITL 节点会弹窗等待你输入「同意」/「拒绝」继续流程。

保存后版本号自动 +1，可在列表页点击「版本」按钮回滚到任意历史版本（自动生成新版本）。

### 8. 运行端到端测试

```bash
cd rag-backend
# Agent 主链路：SSE 事件 / payload 字段 / 持久化 / REST / 降级路径
bash scripts/test-agent-e2e.sh
# HITL：暂停 → 确认续跑、窗口开关对照、暂停后取消
bash scripts/test-agent-hitl-e2e.sh
# 工作流编排：9 类节点 + fan-out/fan-in + 子流程 + 版本 + 触发器 + HITL
bash scripts/test-workflow-e2e.sh
```

脚本依赖后端 8080 运行中、`postgres-with-vector` 容器可访问（默认库 `wgh_rag`），执行前会自行登录并做健康检查。

## 关键 API

### 鉴权
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录返回 JWT |

### 知识库
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/kb` | 知识库列表 |
| POST | `/api/kb/{id}/files` | 上传文件（触发解析+切分+向量化） |
| GET  | `/api/kb/{id}/search?query=xxx` | 知识库检索测试（含召回率/命中率） |

### 对话
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/windows/{id}/stream` | SSE 流式 RAG 对话（事件：start / retrieve / content / sources / accuracy / tool_call_start / tool_call_result / done；窗口启用 Agent 时额外下发 plan_* / step_* 事件） |
| GET  | `/api/chat/windows/{id}/sessions` | 当前用户在窗口下的会话列表 |
| POST | `/api/chat/windows/{id}/sessions` | 显式新建会话 |
| DELETE | `/api/chat/windows/{id}/sessions/{sessionId}` | 删除会话（连带逻辑删消息） |
| GET  | `/api/chat/sessions/{sessionId}/messages` | 分页拉取会话历史消息 |

### 分享
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/windows/{id}/share/enable` | 开启分享返回 token |
| POST | `/api/chat/windows/{id}/share/disable` | 关闭分享 |
| POST | `/api/chat/windows/{id}/share/regenerate` | 重新生成分享 token（旧链接失效，历史会话保留） |
| GET  | `/api/chat/share/{token}/window` | 通过 token 获取窗口信息（需登录） |
| POST | `/api/chat/share/{token}/stream` | 分享侧 SSE 对话（与调试页同链路，支持 Agent 事件） |
| GET  | `/api/chat/share/{token}/sessions` | 当前登录用户在分享窗口下的会话列表 |

### Agent 执行控制
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/agent/plans/{planId}/state` | 查询计划完整状态（含步骤列表） |
| GET  | `/api/agent/sessions/{sessionId}/plan` | 按会话查最新计划（刷新页面恢复面板） |
| POST | `/api/agent/steps/{stepId}/hitl-confirm` | HITL 确认，携带用户输入继续执行（结果补写回会话） |
| POST | `/api/agent/plans/{planId}/cancel` | 取消计划（未执行步骤置为 SKIPPED） |
| POST | `/api/agent/steps/{stepId}/retry` | 重试失败步骤 |
| POST | `/api/agent/steps/{stepId}/skip` | 跳过步骤 |

### 工作流编排
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/workflows` | 分页列表（`page`/`size`/`keyword`/`status`） |
| GET  | `/api/workflows/list` | 全量启用工作流（下拉选择用） |
| GET  | `/api/workflows/{id}` | 详情（含 graph_json） |
| POST | `/api/workflows` | 新建（校验图） |
| PUT  | `/api/workflows/{id}` | 保存（version+1，校验图 + 自动快照） |
| POST | `/api/workflows/{id}/validate` | 仅校验图 |
| DELETE | `/api/workflows/{id}` | 逻辑删除 |
| POST | `/api/workflows/{id}/enable` / `/disable` | 启停 |
| POST | `/api/workflows/{id}/run` | SSE 运行（body：`{ input }`） |
| GET  | `/api/workflows/runs/{runId}/state` | 运行状态 + 节点记录（刷新恢复） |
| POST | `/api/workflows/runs/{runId}/hitl-confirm` | 人工确认续跑（body：`{ userInput }`） |
| POST | `/api/workflows/runs/{runId}/cancel` | 取消运行 |
| GET  | `/api/workflows/{id}/runs` | 运行历史分页 |
| GET  | `/api/workflows/runs/{runId}/nodes` | 节点级记录回放 |
| GET  | `/api/workflows/{id}/versions` | 版本列表 |
| GET  | `/api/workflows/{id}/versions/{version}` | 版本详情（含 graph_json） |
| POST | `/api/workflows/{id}/rollback/{version}` | 回滚（基于旧版本生成新版本） |
| GET  | `/api/workflows/{id}/triggers` | 触发器列表 |
| POST | `/api/workflows/{id}/triggers` | 新增触发器（CRON 或 Webhook） |
| POST | `/api/workflows/triggers/{id}/enable` / `/disable` | 启停触发器 |
| DELETE | `/api/workflows/triggers/{id}` | 删除触发器 |
| POST | `/api/workflows/webhook/{token}` | Webhook 免密触发（token 由系统生成） |
| GET  | `/api/workflows/templates` | 模板列表 |
| POST | `/api/workflows/templates/{templateId}/instantiate` | 模板实例化（自动填充占位 modelId） |

### 工具
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/chat/tools/page` | 工具分页（支持 toolType 过滤） |
| POST | `/api/chat/tools` | 新增工具 |
| PUT  | `/api/chat/tools/{id}` | 更新工具 |
| DELETE | `/api/chat/tools/{id}` | 逻辑删除工具 |
| POST | `/api/chat/tools/{id}/debug` | 在线调试工具（按 definition 解析参数） |
| GET  | `/api/chat/tools/skill/list-dirs` | 扫描磁盘 skill 目录 |
| POST | `/api/chat/tools/skill/import` | 导入磁盘 skill 到数据库（同名时跳过并返回 duplicated=true） |
| POST | `/api/chat/tools/skill/save` | 保存 skill 内容（更新 front matter：subModelId / description） |

### 模型
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/models/chat` | Chat 模型列表 |
| GET  | `/api/models/embedding` | Embedding 模型列表 |

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `WGH_PROFILE` | dev | Spring Profile（dev / test / prod） |
| `WGH_DB_HOST` | localhost | PostgreSQL 主机 |
| `WGH_DB_PORT` | 5432 | PostgreSQL 端口 |
| `WGH_DB_NAME` | wgh_rag | 数据库名 |
| `WGH_DB_USERNAME` | postgres | DB 用户 |
| `WGH_DB_PASSWORD` | postgres | DB 密码 |
| `WGH_JWT_SECRET` | (内置) | JWT 签名密钥（生产必须改为环境变量注入） |
| `WGH_UPLOAD_DIR` | ./uploads | 文件上传目录 |
| `WGH_SKILL_BASE_PATH` | ./skills | Skill 磁盘目录基础路径 |
| `OPENAI_API_KEY` | demo | OpenAI 兼容接口 Key |
| `OPENAI_BASE_URL` | https://api.openai.com/v1 | OpenAI 兼容接口地址 |
| `DASHSCOPE_API_KEY` | - | 通义千问 API Key |
| `DEEPSEEK_API_KEY` | - | DeepSeek API Key |

## 目录约定

| 路径 | 说明 |
|------|------|
| `rag-backend/sql/` | 一键入口 `init.sql` → 串联 `schema.sql`（完整 DDL + 索引 + COMMENT）+ `data.sql`（种子数据 + 老库兼容修复）；所有历史 migration 已合入，新库只需跑 init.sql |
| `rag-backend/rag-app/skills/` | Skill 磁盘目录（由 `wgh.skill.base-path` 解析而来） |
| `rag-backend/uploads/` | 按知识库 ID 分子目录 |
| `rag-backend/logs/` | 运行日志（SizeAndTimeBasedRollingPolicy） |
| `rag-backend/scripts/` | 端到端测试脚本（Agent 主链路 / HITL） |
| `rag-backend/eval/` | RAG 评估脚本和报告 |

## 工程约定（重要）

- **多模块编译**：`mvn spring-boot:run -pl rag-app` 必须加 `-am`，否则会读 `~/.m2` 旧 jar
- **测试命名**：surefire excludes `**/*IntegrationTest.java`，集成测试用 `*ApiTest` / `*EngineTest` / `*Test` 命名
- **Java 25 + Mockito**：`-Dnet.bytebuddy.experimental=true`
- **逻辑删除**：所有删除操作走 `deleted` 字段，物理删除需明确需求
- **变量遮蔽**：前端避免使用 `window`、`location` 等全局变量名作 ref 名（已在 `ChatPlayground.vue` 中踩过坑，访问全局对象请用 `globalThis.xxx`）
- **Agent 字段映射**：`ChatWindow` 的 `agent_*` 字段需显式标注 `@TableField(value = "agent_xxx")`，否则 MyBatis-Plus 生成的 SELECT 不含这些列
- **Agent 落库顺序**：Agent 分支必须「先落库、后下发 SSE」，客户端断连（刷新）不影响最终答复写入；流式回调内不要用未保护的 `sse.send`，失败只降级为 warn，避免误触发「降级为普通 RAG」
- **HITL 事件时序**：HITL 步骤在暂停前不会发 `step_start`，前端收到 `step_hitl_pending` 时若列表中不存在该步骤需按 payload 补建（含 DB 主键 `id`，否则确认接口 404）
- **SSE 换行**：`content` 事件必须用 JSON 包装传输文本，避免换行符丢失
- **pgvector 配置**：`PgVectorEmbeddingStore` 不要显式配置空的 metadata，使用默认 COMBINED_JSON 模式
- **工作流前端路由**：`/workflows/:id/edit` 进入编辑器，列表页在 `/workflows`；刷新恢复逻辑需要跨页导航才能触发 `onMounted`（同一个 URL navigate 不重载）
- **工作流 HITL 弹窗时机**：`syncRunState` 的 PAUSED 分支中 `hitlVisible = true` 必须包在 `nextTick()` 中，避开 el-dialog teleport 渲染竞争；增加 `:close-on-press-escape="false"` 防 ESC 误关
- **工作流 SSE 事件驱动复位**：vite dev 代理不转发 SSE 终止帧（事件实时到达、连接挂起 pending），前端所有状态复位**必须以 SSE 事件驱动**，不能依赖 `await doStream()` resolve；开发代理独有，生产环境不影响
- **工作流模板实例化**：LLM 节点占位 `modelId` 自动填充为第一个启用模型，避免校验拦截；子流程 WORKFLOW 节点递归最大 3 层，超过返回校验失败
- **工作流触发器幂等**：CRON 触发器注册/注销通过 `CronTrigger.schedule` 动态操作，`destroy` 时从 scheduler 移除；Webhook token 由 `UUID.randomUUID` 生成，唯一索引保证不重复
- **并行 Agent 共享浏览器**：browser_use agent 严禁并行派遣（共享浏览器实例会串扰导航），必须串行；每个 agent step 预算仅够 2-4 个操作步，复杂任务拆细分批 resume

## 功能完成清单

### 后端模块

| 模块 | 状态 | 说明 |
|------|------|------|
| rag-common（Result/JWT/异常/MyBatisPlus/Security） | ✅ 完成 | 通用基础设施，全局可用 |
| rag-config（RagProperties + PgVectorProperties） | ✅ 完成 | 多 profile 配置映射 |
| rag-llm（模型管理 + 多厂商工厂 + 缓存） | ✅ 完成 | DB 动态配置 + DASHSCOPE / DEEPSEEK / CUSTOM_OPENAI 兼容 + ModelCacheManager 热更新 |
| rag-kb（知识库 CRUD + 上传 + 解析 + 切分 + 向量化 + 检索） | ✅ 完成 | PDF/DOCX/DOC/XLSX/XLS/CSV/TXT/MD 解析，7 种切分策略，pgvector 向量存储 |
| rag-chat（会话/窗口 + 分层上下文压缩 + 流式 RAG + 评估 + 分享） | ✅ 完成 | 三层压缩含持久化复用；MRR/NDCG + LLM-as-Judge 双轨评估；会话隔离 + 分享 token |
| rag-controller（REST API 层） | ✅ 完成 | Auth / Chat / KnowledgeBase / LlmModel / Agent / Workflow / 分享 / 工具调试 全覆盖 |
| rag-tool（工具框架 + 4 类执行器 + Skill 文件服务） | ✅ 完成 | ToolExecutor SPI + HTTP/MCP/SKILL/FUNCTION_CALLING + 工具调试 + 目录扫描 + 同名判重 |
| rag-agent（自动规划 + 反思 + HITL） | ✅ 完成 | Planner 步骤拆解 → StepExecutor 逐步执行 → Reflection 评分重试 → 编排持久化；窗口级开关；HITL 暂停/确认 |
| rag-workflow（可视化编排引擎） | ✅ 完成 | 9 类 DAG 节点 + frontier 并行调度 + pendingInEdges fan-in 汇聚 + 子流程嵌套（3 层）+ 版本快照 + CRON/Webhook 触发器 + 3 个内置模板 |
| Agent 端到端测试 | ✅ 完成 | `test-agent-e2e.sh`（18 项）+ `test-agent-hitl-e2e.sh`（16 项），覆盖 SSE / payload / 持久化 / REST / 降级 / 窗口开关对照 |
| 工作流端到端测试 | ✅ 完成 | `test-workflow-e2e.sh`（67 项），覆盖 9 类节点 / fan-out fan-in / 子流程 / 版本快照 / 回滚 / CRON & Webhook 触发器 / HITL 暂停续跑 / 模板实例化 |

### 前端页面

| 页面 | 状态 | 说明 |
|------|------|------|
| 登录 Login | ✅ 完成 | JWT 存储 + 路由守卫 + redirect 回跳 |
| 首页 | ✅ 完成 | 系统概览 |
| 知识库管理 | ✅ 完成 | 列表 + 新建 + 文件上传 + 处理状态 |
| 知识库搜索测试 | ✅ 完成 | 实时向量检索 + 召回/命中率指标 + 结果展示 |
| 对话 Playground | ✅ 完成 | SSE 流式 + 来源展示 + 三率指标 + 工具调用事件 + 历史会话侧边栏 + 新建/删除会话 + 复制分享链接 + Agent 执行计划树（实时步骤 / 反思分数 / HITL 确认 / 取消重试跳过 / 刷新恢复与轮询） |
| 对话窗口管理 | ✅ 完成 | 评估方案 / Judge 模型 / 知识库绑定 / 工具按类型分组选择 / Agent 配置（开关、最大步骤、Planner/Executor 模型、反思阈值、HITL）/ 分享开关联复制+重新生成 |
| 分享对话 ShareChat | ✅ 完成 | 登录后访问 / 历史会话侧边栏 / 新建/删除会话 / 与 Playground 渲染一致（含 Agent 执行计划树） |
| Chat 模型配置 | ✅ 完成 | 多厂商动态添加 + 启用/禁用 |
| Embedding 模型配置 | ✅ 完成 | 默认模型唯一约束 + 多厂商 |
| 工具管理 | ✅ 完成 | 5 个 tab（全部 / Skill / MCP / HTTP / Function Calling）+ SKILL 目录合并列表 + 在线编辑 + 任意工具调试弹窗 |
| 工作流管理 WorkflowList | ✅ 完成 | 分页列表 + 新建（含模板市场弹窗）+ 启停 + 逻辑删除 + 版本历史弹窗（回滚）+ 触发器管理 CRON/Webhook + 运行历史抽屉（展开节点级明细） |
| 工作流编辑器 WorkflowEditor | ✅ 完成 | Vue Flow DAG 画布 + 9 类节点拖拽 + 条件/循环多 handle 连线 + 右侧配置抽屉（按节点类型动态表单）+ 运行调试面板（节点轨迹实时上色 + 最终输出）+ HITL 暂停弹窗 + 刷新恢复轮询 |

### 知识库文件格式

| 格式 | 状态 | 解析方式 |
|------|------|----------|
| PDF | ✅ 完成 | PDFBox，按双换行分段；段落级抽取 |
| DOCX (Office Open XML) | ✅ 完成 | POI XWPF，段落 + 表格均抽取（`row1col1 \| row1col2` 格式） |
| DOC (旧版 Word) | ✅ 完成 | POI HWPF + WordExtractor |
| XLSX / XLS Excel | ✅ 完成 | POI XSSF/HSSF，逐行逐格合并为 `cell1 \| cell2 \| ...` 格式 |
| CSV | ✅ 完成 | BufferedReader 逐行读取 |
| TXT / MD | ✅ 完成 | `Files.readString`，按双换行分段 |
| HTML | 📋 计划中 | 引入 Jsoup 剥离标签，保留 `<h1-6>` 作为章节锚点 |
| JSON | 📋 计划中 | 递归展平 key-path:value，支持 array 逐项展开 |
| PPTX / PPT | 📋 计划中 | POI XSLF，按 slide 一页一个 chunk |
| 图片（PNG/JPG） | 📋 计划中 | 接入 OCR（PaddleOCR / Tesseract）识别后入库 |

### 文档分割策略

| 策略 | 状态 | 说明 |
|------|------|------|
| **AUTO**（自动路由） | ✅ 完成 | 按内容特征检测：FAQ 密度 >15% → FAQ；章节标题占比 >10% → CHAPTER；默认 → RECURSIVE |
| **RECURSIVE**（递归字符分块） | ✅ 完成 | 分隔符层级 `\n\n → \n → 。！？； → , → ` 逐级降级切割；相邻 chunk overlap 重叠 |
| **CHAPTER**（按章节） | ✅ 完成 | 识别 `1. / 1.1 / # ## / 第X章` 三种章节标题；识别失败回退 PARAGRAPH |
| **PARAGRAPH**（按段落滑窗） | ✅ 完成 | 段落为单位滑窗合并，超长段落内部按 LENGTH 切 |
| **FAQ**（问答对） | ✅ 完成 | 识别 `问N：...答：...` 模式，一问一答一个 chunk；识别失败回退 RECURSIVE |
| **LENGTH**（固定长度） | ✅ 完成 | 句子边界优先切分 + overlap；带死循环保护 |
| **CUSTOM**（自定义正则） | ⚠️ 占位 | 接口已预留（通过 `split_config` JSON 传正则），当前 fallback 到 PARAGRAPH |
| **SEMANTIC**（语义切分） | 📋 计划中 | 基于嵌入向量相似度的句子聚类切分（需要额外 embedding 开销） |
| **STRUCTURE_AWARE**（结构化切分） | 📋 计划中 | 对 Excel/CSV 按行/按列切，对 JSON 按 key-path 切，保留数据结构 |

### 数据层

| 项目 | 状态 | 说明 |
|------|------|------|
| SQL init（15 张表 + 40+ 业务索引 + 种子数据） | ✅ 完成 | schema.sql 幂等 DDL（CREATE TABLE / INDEX 全部 IF NOT EXISTS）+ data.sql 种子 INSERT，所有历史 migration 已合入（含 `wgh_agent_plan` / `wgh_agent_step` / `wgh_workflow` / `wgh_workflow_run` / `wgh_workflow_node_run` / `wgh_workflow_version` / `wgh_workflow_trigger`） |
| pgvector 向量扩展 | ✅ 完成 | 自动建表 + 可选 IVF 索引 |
| 老库兼容修复 | ✅ 内置 | data.sql 末尾含幂等 SKILL executor_config 格式修复 UPDATE，新库空跑安全 |

## 系统截图

`rag-frontend/image/` 目录下：

- 首页 / 模型管理 / 嵌入模型 / 聊天窗口管理 / 创建聊天窗口 / 调试聊天窗口 / 分享聊天窗口 / 工具管理
