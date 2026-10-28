# WGH RAG System — 详细设计文档

> 版本：v1.0｜日期：2026-10-28｜反向工程生成（源码逆向输出）

---

## 目录

1. [数据库详细设计（15 张表）](#1-数据库详细设计15-张表)
2. [核心类详细设计](#2-核心类详细设计)
3. [接口详细设计](#3-接口详细设计)
4. [SSE 事件协议](#4-sse-事件协议)
5. [配置项详细设计](#5-配置项详细设计)
6. [异常码定义](#6-异常码定义)
7. [序列与枚举值](#7-序列与枚举值)

---

## 1. 数据库详细设计（15 张表）

### 公共字段

所有业务表统一带以下字段（`schema.sql` 中每个表的末尾）：

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| create_time | TIMESTAMP | CURRENT_TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | CURRENT_TIMESTAMP | 更新时间 |
| create_by | VARCHAR(50) | 'system' | 创建人 |
| update_by | VARCHAR(50) | 'system' | 更新人 |
| deleted | SMALLINT | 0 | 逻辑删除标记 |

### ER 关系概要

```
wgh_llm_model ──1:N──► wgh_chat_window（chatModelId）
wgh_embedding_model ──1:N──► wgh_knowledge_base（embeddingModelId）

wgh_knowledge_base ──1:N──► wgh_knowledge_file
wgh_knowledge_base ──N:N──► wgh_chat_window（中间表 kb_ids JSON）

wgh_chat_window ──1:N──► wgh_chat_session
wgh_chat_window ──N:N──► wgh_tool（中间表 tool_ids JSON）

wgh_chat_session ──1:N──► wgh_chat_message
wgh_chat_session ──1:N──► wgh_agent_plan（可选）

wgh_agent_plan ──1:N──► wgh_agent_step

wgh_workflow ──1:N──► wgh_workflow_version
wgh_workflow ──1:N──► wgh_workflow_run
wgh_workflow ──1:N──► wgh_workflow_trigger
wgh_workflow_run ──1:N──► wgh_workflow_node_run
```

### 1.1 wgh_llm_model（Chat 模型）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| name | VARCHAR(100) | NOT NULL | 模型名称（如 qwen-plus） |
| provider | VARCHAR(30) | NOT NULL | 提供商：DASHSCOPE / DEEPSEEK / CUSTOM_OPENAI |
| model_name | VARCHAR(100) | NOT NULL | 实际模型名（传给 LLM SDK） |
| base_url | VARCHAR(300) | | 自定义端点（CUSTOM_OPENAI 必填） |
| api_key | VARCHAR(500) | | API Key（生产应加密） |
| enabled | SMALLINT | DEFAULT 1 | 1=启用 0=禁用 |
| compression_model | SMALLINT | DEFAULT 0 | 1=标记为压缩专用模型 |
| max_tokens | INT | DEFAULT 4096 | 单次输出上限 |
| temperature | DOUBLE | DEFAULT 0.2 | 采样温度 |

索引：`(enabled)`、`(name)`

### 1.2 wgh_embedding_model（Embedding 模型）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| name | VARCHAR(100) | NOT NULL | 名称 |
| provider | VARCHAR(30) | NOT NULL | |
| model_name | VARCHAR(100) | NOT NULL | |
| base_url | VARCHAR(300) | | |
| api_key | VARCHAR(500) | | |
| dimension | INT | NOT NULL | 向量维度（向量化时据此建列） |
| enabled | SMALLINT | DEFAULT 1 | |
| is_default | SMALLINT | DEFAULT 0 | 唯一默认（UK 约束） |

唯一约束：`uk_embedding_default` ON (is_default) WHERE is_default=1

### 1.3 wgh_knowledge_base（知识库）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| name | VARCHAR(100) | NOT NULL | |
| description | VARCHAR(500) | | |
| embedding_model_id | BIGINT | NOT NULL | FK → wgh_embedding_model.id |
| status | SMALLINT | DEFAULT 1 | 1=启用 0=停用 |
| chunk_size | INT | DEFAULT 500 | 默认切分 chunk 大小 |
| chunk_overlap | INT | DEFAULT 50 | 默认 overlap |
| split_strategy | VARCHAR(30) | DEFAULT 'AUTO' | AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM |

索引：`(embedding_model_id)`、`(status, create_time)`

### 1.4 wgh_knowledge_file（知识库文件）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| kb_id | BIGINT | NOT NULL | FK → wgh_knowledge_base.id |
| file_name | VARCHAR(200) | NOT NULL | 原始文件名 |
| file_path | VARCHAR(500) | | 服务器存储路径 |
| file_size | BIGINT | | 字节数 |
| file_type | VARCHAR(20) | | PDF / DOCX / TXT / MD / XLSX / CSV ... |
| process_status | VARCHAR(20) | DEFAULT 'PENDING' | PENDING / PROCESSING / SUCCESS / FAILED |
| chunk_count | INT | DEFAULT 0 | 成功切分的 chunk 数 |
| error_message | TEXT | | 失败时的错误 |

索引：`(kb_id)`、`(process_status)`、`(kb_id, process_status, create_time)`

### 1.5 wgh_chat_window（聊天窗口）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| name | VARCHAR(100) | NOT NULL | |
| description | VARCHAR(500) | | |
| chat_model_id | BIGINT | NOT NULL | FK → wgh_llm_model.id |
| kb_ids | JSONB | DEFAULT '[]' | 绑定的知识库 ID 数组 |
| tool_ids | JSONB | DEFAULT '[]' | 关联的工具 ID 数组 |
| max_tool_rounds | INT | DEFAULT 5 | 单轮对话最大工具调用循环 |
| evaluation_scheme | VARCHAR(20) | DEFAULT 'NONE' | NONE / METRICS / LLM_JUDGE / BOTH |
| judge_model_ids | JSONB | | 评估用的 Critic 模型 ID（可选） |
| enable_share | SMALLINT | DEFAULT 0 | |
| share_token | VARCHAR(64) | | |
| agent_enabled | SMALLINT | DEFAULT 0 | Agent 总开关 |
| agent_max_steps | INT | DEFAULT 15 | 最大计划步骤数 |
| agent_planner_model_id | BIGINT | | Planner 独立模型（可为空=继承 chat_model） |
| agent_executor_model_id | BIGINT | | Executor 独立模型 |
| agent_critic_model_ids | JSONB | | Reflection Critic 模型数组 |
| agent_reflection_threshold | DOUBLE | DEFAULT 7.0 | 反思通过阈值 |
| agent_hitl_enabled | SMALLINT | DEFAULT 0 | HITL 开关 |
| status | SMALLINT | DEFAULT 1 | 1=启用 0=停用 |

唯一约束：`uk_chat_window_share_token` ON (share_token) WHERE share_token IS NOT NULL

索引：`(agent_enabled)`、`(share_token)`、`(status)`

### 1.6 wgh_chat_session（会话）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| window_id | BIGINT | NOT NULL | FK → wgh_chat_window.id |
| user_identifier | VARCHAR(100) | NOT NULL | default_user / 分享页登录用户名 |
| title | VARCHAR(200) | | 自动取首条用户消息前 20 字 |
| status | SMALLINT | DEFAULT 1 | 1=活跃 0=归档 |
| compressed_context | TEXT | | 分层压缩后的上下文 JSON（含中期摘要 + 远期事实） |
| compression_version | VARCHAR(10) | | 压缩 Prompt 版本（bump 后旧压缩失效） |

索引：`(window_id, user_identifier, update_time)`、`(window_id, status)`

### 1.7 wgh_chat_message（消息）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| session_id | BIGINT | NOT NULL | FK → wgh_chat_session.id |
| role | VARCHAR(20) | NOT NULL | user / assistant |
| content | TEXT | NOT NULL | |
| sources | JSONB | | RAG 来源 [{kbName, fileName, chunkIndex, score}] |
| recall_rate | DOUBLE | DEFAULT 0 | RAG 实时计算 |
| hit_rate | DOUBLE | DEFAULT 0 | |
| accuracy_rate | DOUBLE | DEFAULT 0 | |
| faithfulness | DOUBLE | DEFAULT 0 | LLM-as-Judge |
| answer_relevancy | DOUBLE | DEFAULT 0 | |
| plan_id | BIGINT | | Agent 模式关联的 plan（非 Agent 为空） |
| token_count | INT | DEFAULT 0 | |
| latency_ms | INT | DEFAULT 0 | |

索引：`(session_id, create_time)`、`(session_id, plan_id)`

### 1.8 wgh_tool（工具）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| name | VARCHAR(100) | NOT NULL | 工具唯一名称 |
| description | VARCHAR(500) | NOT NULL | |
| tool_type | VARCHAR(20) | NOT NULL | HTTP / MCP / SKILL / FUNCTION_CALLING |
| definition | JSONB | NOT NULL | OpenAI function 格式 `{type:"function",function:{name,description,parameters}}` |
| executor_config | JSONB | | 按 tool_type 不同字段不同 |
| enabled | SMALLINT | DEFAULT 1 | |

唯一约束：`uk_tool_name` ON (name)

索引：`(tool_type)`、`(enabled)`

**executorConfig 按 toolType 结构**：

```jsonc
// HTTP
{ "url": "...", "method": "GET", "headers": {...}, "timeoutMs": 10000 }
// MCP
{ "transport": "http", "serverUrl": "...", "timeoutMs": 30000 }
{ "transport": "stdio", "command": "npx", "args": [...], "timeoutMs": 30000 }
// SKILL
{ "mode": "inline", "promptTemplate": "...", "subModelId": null }
{ "mode": "directory", "skillPath": "./skills/xxx", "subModelId": null }
// FUNCTION_CALLING
{ "handlerClass": "...", "method": "execute" }
```

### 1.9 wgh_agent_plan（Agent 计划）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| session_id | BIGINT | NOT NULL | FK → wgh_chat_session.id |
| chat_window_id | BIGINT | NOT NULL | |
| llm_model_id | BIGINT | NOT NULL | Planner 使用的模型 |
| status | VARCHAR(20) | DEFAULT 'PLANNING' | PLANNING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED |
| user_goal | TEXT | NOT NULL | 用户原始目标 |
| plan_json | JSONB | NOT NULL | Planner 生成的完整 Plan JSON |
| current_step_index | INT | DEFAULT 0 | |
| max_steps | INT | DEFAULT 15 | |
| total_tokens | INT | DEFAULT 0 | |

索引：`(session_id)`、`(status)`

### 1.10 wgh_agent_step（Agent 步骤）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| plan_id | BIGINT | NOT NULL | FK → wgh_agent_plan.id |
| step_index | INT | NOT NULL | 计划内序号（1-based） |
| step_id | VARCHAR(32) | NOT NULL | Plan JSON 里的唯一 stepId |
| title | VARCHAR(200) | NOT NULL | 步骤标题 |
| description | TEXT | | 详细说明 |
| depends_on | JSONB | DEFAULT '[]' | 依赖 stepId 数组 |
| status | VARCHAR(20) | DEFAULT 'PENDING' | PENDING / READY / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING |
| thought | TEXT | | 最近一次 LLM 推理 |
| action_tool | VARCHAR(100) | | 调用的工具名 |
| action_input | TEXT | | 工具输入 |
| observation | TEXT | | 工具返回 / LLM 输出 |
| reflection_score | DOUBLE | | 反思评分（0-10） |
| reflection_feedback | TEXT | | 反思反馈 |
| retry_count | INT | DEFAULT 0 | |
| max_retries | INT | DEFAULT 3 | |
| hitl_expected_input | TEXT | | HITL 提示用户输入 |
| result_summary | TEXT | | 步骤产出摘要（传给后续） |
| start_time | TIMESTAMP | | |
| end_time | TIMESTAMP | | |
| token_count | INT | DEFAULT 0 | |

索引：`(plan_id)`、`(plan_id, status)`、`(status)`

### 1.11 wgh_workflow（工作流定义）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| name | VARCHAR(100) | NOT NULL | |
| description | VARCHAR(500) | | |
| status | SMALLINT | DEFAULT 1 | 1=启用 0=停用 |
| version | INT | DEFAULT 1 | 乐观锁 + 快照版本号 |
| graph_json | JSONB | NOT NULL | `{ nodes: [...], edges: [...] }` |

索引：`(status)`、`(update_time)`

### 1.12 wgh_workflow_version（版本快照）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| workflow_id | BIGINT | NOT NULL | FK → wgh_workflow.id |
| version | INT | NOT NULL | 快照版本号 |
| graph_json | JSONB | NOT NULL | 快照时的完整图 |

索引：`(workflow_id)`、`(workflow_id, version)`

### 1.13 wgh_workflow_trigger（触发器）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| workflow_id | BIGINT | NOT NULL | |
| trigger_type | VARCHAR(20) | NOT NULL | CRON / WEBHOOK |
| cron_expression | VARCHAR(50) | | CRON 6 段表达式（秒 分 时 日 月 周） |
| webhook_token | VARCHAR(64) | | Webhook 免密调用 token |
| input_text | TEXT | | 每次触发时的默认输入 |
| status | SMALLINT | DEFAULT 1 | 1=启用 0=停用 |
| fire_count | INT | DEFAULT 0 | 触发次数统计 |
| last_fire_time | TIMESTAMP | | 最近触发时间 |

唯一约束：`uk_wf_trigger_webhook_token` ON (webhook_token) WHERE webhook_token IS NOT NULL

索引：`(workflow_id)`、`(trigger_type)`、`(status)`

### 1.14 wgh_workflow_run（运行实例）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| workflow_id | BIGINT | NOT NULL | |
| workflow_version | INT | NOT NULL | 运行时的快照版本 |
| input_text | TEXT | | 本次运行输入 |
| status | VARCHAR(20) | DEFAULT 'RUNNING' | RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED |
| output_text | TEXT | | 最终输出（END 节点渲染结果） |
| context_json | JSONB | | 运行上下文：nodeOutputs / loopIterations / pendingNodeIds |
| error_message | TEXT | | |
| executed_nodes | INT | DEFAULT 0 | |
| total_nodes | INT | DEFAULT 0 | |
| start_time | TIMESTAMP | | |
| finish_time | TIMESTAMP | | |
| duration_ms | BIGINT | | |

索引：`(workflow_id, start_time)`、`(status)`

### 1.15 wgh_workflow_node_run（节点级执行记录）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| run_id | BIGINT | NOT NULL | FK → wgh_workflow_run.id |
| node_id | VARCHAR(64) | NOT NULL | graph_json.nodes[] 中的 node.id |
| node_type | VARCHAR(30) | NOT NULL | START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE / WORKFLOW |
| node_name | VARCHAR(100) | | |
| status | VARCHAR(20) | DEFAULT 'PENDING' | PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING |
| iteration | INT | DEFAULT 0 | 循环体第几次执行（非循环节点为 0） |
| input_json | JSONB | | 渲染后的入参 |
| output_json | JSONB | | 出参 |
| error_message | TEXT | | |
| start_time | TIMESTAMP | | |
| end_time | TIMESTAMP | | |
| duration_ms | BIGINT | | |

索引：`(run_id, node_id)`、`(run_id, start_time)`

---

## 2. 核心类详细设计

### 2.1 AgentOrchestrator（rag-agent）

| 属性 | 类型 | 说明 |
|------|------|------|
| planner | Planner | LLM 计划生成器 |
| stepExecutor | StepExecutor | 单步骤 ReAct 执行器 |
| reflectionEngine | ReflectionEngine | Critic 反思评估引擎 |
| sessionMapper / planMapper / stepMapper | MyBatis-Plus Mapper | 持久化 |

**核心方法**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| orchestrate() | chatSessionId, window, userGoal, SseEmitter | void | Agent 主入口：Planner → 拓扑排序 → 逐步执行 → 反思 → HITL 暂停 → 完成 |
| executePlan() | AgentPlan plan, SseEmitter | void | 执行循环：for step in ready_steps → StepExecutor → Reflection → HITL 检查 → 落库 |
| handleStepResult() | AgentStep step, AgentStepResult result | AgentStep | 后置处理：反思评分、重试、动态重规划、终态判断 |
| pauseForHitl() | AgentStep step | void | step 置 HITL_PENDING → plan 置 PAUSED → 落库 → SSE step_hitl_pending + plan_paused |
| resumeFromHitl() | Long planId, String userInput | void | PAUSED → RUNNING → frontier=pendingNodeIds → 续跑 |
| cancelPlan() | Long planId | void | plan 置 CANCELLED → 未执行 steps 置 SKIPPED → SSE plan_cancelled |

### 2.2 Planner（rag-agent）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| generate() | userGoal, plannerModelId, toolList, kbList, maxSteps | PlanDTO | LLM（GOAP prompt + 可用资源列表）→ Plan JSON → 结构化解析 |
| generateV2() | 执行中重规划 | PlanDTO | 动态 re-plan：前置步骤 result_summary 作为 context → Planner 重新生成剩余步骤 |

**PlanDTO 结构**（Planner LLM 输出）：

```jsonc
{
  "planId": "plan_001",
  "steps": [{
    "stepId": "step_1",
    "title": "...",
    "description": "...",
    "dependsOn": [],
    "requiresTools": ["toolName"],
    "requiresRAG": false,
    "hitl": false,        // 有副作用/不可逆操作标记
    "outputType": "DATA"
  }]
}
```

### 2.3 StepExecutor（rag-agent）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| execute() | AgentStep step, StepContext ctx, executorModelId | AgentStepResult | 构建 step prompt → 内部 ReAct 循环（最多 5 轮工具调用）→ 返回 step 结果 |

**内部 ReAct 循环**：

```
while tool_rounds++ < 5:
  response = LLM(stepPrompt + availableTools + ctx)
  if response has ToolCall:
    toolResult = ToolExecutionEngine.execute(...)
    ctx.appendObservation(toolResult)
  else:
    finalAnswer = response.text
    break
return AgentStepResult(finalAnswer, toolCalls, ragSources)
```

**AgentStepResult 结构**：

```jsonc
{
  "observation": "...",
  "toolInvocations": [{ "name": "...", "input": {...}, "output": "...", "durationMs": 500, "success": true }],
  "ragSources": [{ "kbName": "...", "fileName": "...", "chunkIndex": 0, "score": 0.82 }],
  "thoughts": ["..."]
}
```

### 2.4 ReflectionEngine（rag-agent）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| evaluate() | AgentStep step, AgentStepResult result, criticModelIds | ReflectionResult | Critic LLM 评估 step goal 与 step output → score (0-10) + feedback + pass |

**ReflectionResult 结构**：

```jsonc
{ "score": 8.5, "feedback": "步骤产出满足目标，数据准确", "pass": true }
```

### 2.5 WorkflowEngine（rag-workflow）

| 属性 | 类型 | 说明 |
|------|------|------|
| nodeExecutor | NodeExecutor | 9 类节点执行器 |
| variableResolver | VariableResolver | `{{ path }}` 模板渲染 |
| conditionEvaluator | ConditionEvaluator | CONDITION/LOOP 表达式求值 |
| executor | ThreadPoolTaskExecutor | 并发调度线程池（core 2 / max 8 / queue 100） |
| runMapper / nodeRunMapper | Mapper | 持久化 |

**核心方法**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| run() | workflowId, input, SseEmitter | void | 主入口：校验图 → 创建 run + node_runs → frontier + pendingInEdges 调度循环 |
| dispatchNode() | nodeId, run, context, SseEmitter | void | 并发提交节点执行：渲染参数 → NodeExecutor.execute → 写回 nodeOutputs → 持久化 |
| selectNext() | node, result | List<String> | 后继节点选择：普通节点=全部出边目标，CONDITION/LOOP=按 sourceHandle 选分支 |
| pauseForHitl() | run, nodeRun, prompt | void | 抛 PauseSignal → run.status=PAUSED → SSE node_hitl_pending |
| resumeFromHitl() | runId, userInput | void | run.status=RUNNING → frontier=pendingNodeIds → 续跑 |
| cancelRun() | runId | void | run.status=CANCELLED → 未执行 nodeRun=SKIPPED |

**frontier + pendingInEdges 调度伪代码**：

```
frontier ← Collections.singletonList(startNodeId)
pendingInEdges[target] = 入边总数（按 graph_json.edges 聚合）

while !frontier.isEmpty() && run.status == RUNNING:
    nodeId ← frontier.poll()
    // 入边等待计数器：所有上游完成才入队
    // CONDITION/LOOP 未走的分支不减计数 → 自然跳过
    executor.submit(() -> {
        NodeResult result = dispatchNode(nodeId, ...)
        for (Edge edge : outEdges(nodeId)):
            pendingInEdges[edge.target]--
            if pendingInEdges[edge.target] <= 0:
                frontier.add(edge.target)
    })
```

### 2.6 NodeExecutor（rag-workflow）

**9 类节点执行 switch-case 分发**：

| nodeType | 执行逻辑 | 输出写入 context |
|----------|---------|----------------|
| START | `{ text: input }` | nodeOutputs.startNodeId = text |
| END | VariableResolver.render(output 模板) | run.outputText = rendered |
| LLM | modelCacheManager.getChatModel(modelId).chat(ChatRequest) | nodeOutputs.{id} = `{ text }` |
| RETRIEVAL | ragRetrieveService.searchMultiple(kbIds, query) | nodeOutputs.{id} = `{ text, sources[] }` |
| TOOL | toolExecutionEngine.execute(toolConfig, variableArgs) | nodeOutputs.{id} = `{ text, success }` |
| CONDITION | conditionEvaluator.evaluate(expression) | nodeOutputs.{id} = `{ result: true/false }` |
| LOOP | 判断 breakCondition 或 iteration >= max | nodeOutputs.{id} = `{ iteration }` → 走 loop/done 出口 |
| HITL | 抛 PauseSignal（暂停等待用户输入后写 output） | SUCCESS 后 nodeOutputs.{id} = `{ text: userInput }` |
| VARIABLE | assignments 渲染后写入 run.context.vars | run.context.vars.{name} = value |
| WORKFLOW | 递归调用 WorkflowService.run(子 workflowId, 渲染后的 inputTemplate) | nodeOutputs.{id} = `{ text: 子流程最终输出 }`（内部节点静默执行不上报 SSE） |

### 2.7 ToolExecutionEngine（rag-tool）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| execute() | ToolExecutionRequest request, Map<String, ToolExecutor> registry | ToolInvocationRecord | 按 toolConfig.toolType 从 registry 取 Executor → 执行 → 后处理截断 |

**4 类 Executor 结构**：

| Executor | 执行方式 | 关键依赖 |
|----------|---------|---------|
| HttpToolExecutor | RestTemplate 调用 + `{param}` 占位符 + `${ENV}` 环境变量解析 | RestTemplate |
| McpToolExecutor | McpClientManager 管理 stdio/Streamable HTTP 连接 | mcp-jdk-sdk |
| SkillToolExecutor | inline 模式 promptTemplate → ModelCacheManager.getChatModel(subModelId)；directory 模式运行时读 SKILL.md → front matter + references/追加 | LangChain4j ChatModel |
| FunctionCallingToolExecutor | Class.forName + Method.invoke | 无外部依赖 |

### 2.8 RagRetrieveService（rag-kb）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| searchMultiple() | kbIds[], query, topK | RagSearchResult | 按 kb 逐个检索 → 按 embedding modelId 分组避免维度混用 → min_similarity 过滤 → 计算 recall_rate / hit_rate |

**RagSearchResult 结构**：

```jsonc
{
  "chunks": [{ "kbName": "...", "fileName": "...", "chunkIndex": 0, "score": 0.82, "content": "..." }],
  "recallRate": 0.75,
  "hitRate": 0.90
}
```

### 2.9 ModelCacheManager（rag-llm）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| getChatModel() | modelId | ChatModel | 按 id 缓存/创建 LangChain4j ChatModel 实例 |
| getStreamingChatModel() | modelId | StreamingChatModel | 流式版本（ReAct 循环用非流式） |
| getEmbeddingModel() | modelId | EmbeddingModel | |
| invalidate() | modelId | void | 配置变更时清理缓存 |

### 2.10 CompressionService（rag-chat）

**三层压缩核心逻辑**：

```java
compress(session, history, currentInput) {
    // 1. 分割
    recent = history.last(recentWindowRounds)      // 近期 4 轮：原文保留
    midTerm = history.midRange(midTermWindowRounds) // 中期 6 轮：LLM 摘要
    longTerm = history.exclude(recent + midTerm)    // 远期剩余：关键事实

    // 2. 检查复用（session 上是否有同 promptVersion 且同范围的压缩）
    if (session.compressedContext 匹配) {
        直接复用
    }

    // 3. LLM 压缩（用 compressionModel）
    midSummary = summarize(midTerm, promptVersion)
    longFacts = extractFacts(longTerm, promptVersion)

    // 4. 持久化
    session.compressedContext = JSON({midSummary, longFacts})
    session.compressionVersion = promptVersion
    sessionMapper.updateById(session)

    // 5. 返回压缩后的 context 组装
    return assemble(SystemPrompt + longFacts + midSummary + recent + currentInput)
}
```

---

## 3. 接口详细设计

### 3.1 AuthController

| HTTP | URL | 方法 | 入参 | 出参 | 说明 |
|------|-----|------|------|------|------|
| POST | /api/auth/login | login() | `{"username":"..","password":".."}` | `{code:200,data:{token,expiresIn,userId,username}}` | 登录 |
| GET | /api/auth/me | me() | Header: Authorization | `{code:200,data:{username,userId}}` | 当前用户信息 |

### 3.2 ChatController

| HTTP | URL | 方法 | 入参 | 出参 | 说明 |
|------|-----|------|------|------|------|
| GET | /api/chat/windows | page() | Query: page, size, keyword, status | PageResult | 分页列表 |
| GET | /api/chat/windows/{id} | detail() | Path: id | ChatWindow | 详情 |
| POST | /api/chat/windows | create() | Body: ChatWindowCreateDTO | ChatWindow | 新建 |
| PUT | /api/chat/windows/{id} | update() | Body: ChatWindowUpdateDTO | ChatWindow | 编辑 |
| POST | /api/chat/windows/{id}/enable | enable() | Path: id | void | 启用 |
| POST | /api/chat/windows/{id}/disable | disable() | Path: id | void | 停用 |
| POST | /api/chat/windows/{id}/share/enable | enableShare() | Path: id | `{shareToken}` | 开启分享 |
| POST | /api/chat/windows/{id}/share/disable | disableShare() | Path: id | void | 关闭分享 |
| POST | /api/chat/windows/{id}/share/regenerate | regenerateToken() | Path: id | `{shareToken}` | 重新生成（旧失效） |
| GET | /api/chat/windows/{id}/sessions | listSessions() | Path: id | `[{id,title,updateTime}]` | 会话列表 |
| POST | /api/chat/windows/{id}/sessions | createSession() | Path: id | `{sessionId}` | 新建会话 |
| DELETE | /api/chat/windows/{id}/sessions/{sid} | deleteSession() | Path: id, sid | void | 删除会话 |
| GET | /api/chat/sessions/{sid}/messages | listMessages() | Path: sid, Query: page, size | PageResult | 消息分页 |
| POST | /api/chat/windows/{wid}/stream | streamChat() | Body: `{sessionId,message}` | SSE（见 §4） | 主对话入口 |
| GET | /api/chat/share/{token}/window | getShareWindow() | Path: token | ChatWindow | 分享页窗口信息 |
| POST | /api/chat/share/{token}/stream | shareStream() | Body: `{sessionId,message}` | SSE | 分享页对话（同 playgroud 链路） |
| GET | /api/chat/share/{token}/sessions | shareSessions() | Path: token | `[{id,title,updateTime}]` | 分享页会话列表 |

### 3.3 AgentController

| HTTP | URL | 方法 | 入参 | 出参 | 说明 |
|------|-----|------|------|------|------|
| GET | /api/agent/plans/{planId}/state | getPlanState() | Path: planId | AgentPlan + steps[] | 刷新恢复 |
| GET | /api/agent/sessions/{sessionId}/plan | getPlanBySession() | Path: sessionId | AgentPlan? | 按会话查最新 plan |
| POST | /api/agent/steps/{stepId}/hitl-confirm | hitlConfirm() | Body: `{userInput}` | void | HITL 续跑 |
| POST | /api/agent/plans/{planId}/cancel | cancelPlan() | Path: planId | void | 取消计划 |
| POST | /api/agent/steps/{stepId}/retry | retryStep() | Path: stepId | void | 重试步骤 |
| POST | /api/agent/steps/{stepId}/skip | skipStep() | Path: stepId | void | 跳过步骤 |

### 3.4 WorkflowController + WorkflowTriggerController

| HTTP | URL | 方法 | 入参 | 出参 | 说明 |
|------|-----|------|------|------|------|
| GET | /api/workflows | page() | Query: page, size, keyword, status | PageResult | 分页列表 |
| GET | /api/workflows/list | enabledList() | - | `[{id,name,version}]` | 全量启用（下拉用） |
| GET | /api/workflows/{id} | detail() | Path: id | Workflow | 含 graph_json |
| POST | /api/workflows | create() | Body: `{name,description,graphJson}` | Workflow | 新建（校验图） |
| PUT | /api/workflows/{id} | update() | Body: `{name,description,graphJson}` | Workflow | 保存（version+1 + 自动快照） |
| POST | /api/workflows/{id}/validate | validate() | Body: graphJson | `{valid,errors[]}` | 仅校验 |
| DELETE | /api/workflows/{id} | remove() | Path: id | void | 逻辑删除 |
| POST | /api/workflows/{id}/enable | enable() | Path: id | void | |
| POST | /api/workflows/{id}/disable | disable() | Path: id | void | |
| POST | /api/workflows/{id}/run | run() | Body: `{input}` | SSE（见 §4） | SSE 运行 |
| GET | /api/workflows/runs/{runId}/state | getRunState() | Path: runId | WorkflowRun + nodeRuns[] | 刷新恢复 |
| POST | /api/workflows/runs/{runId}/hitl-confirm | hitlConfirm() | Body: `{userInput}` | void | |
| POST | /api/workflows/runs/{runId}/cancel | cancelRun() | Path: runId | void | |
| GET | /api/workflows/{id}/runs | listRuns() | Path: id, Query: page, size | PageResult | 运行历史 |
| GET | /api/workflows/runs/{runId}/nodes | listNodeRuns() | Path: runId | `[WorkflowNodeRun]` | 节点级回放 |
| GET | /api/workflows/{id}/versions | listVersions() | Path: id | `[WorkflowVersion]` | |
| GET | /api/workflows/{id}/versions/{v} | getVersion() | Path: id, v | WorkflowVersion | 含 graph_json |
| POST | /api/workflows/{id}/rollback/{v} | rollback() | Path: id, v | Workflow | 基于旧版本生成新版本 |
| GET | /api/workflows/{id}/triggers | listTriggers() | Path: id | `[WorkflowTrigger]` | |
| POST | /api/workflows/{id}/triggers | createTrigger() | Body: `{triggerType, cronExpression, inputText}` | WorkflowTrigger | |
| POST | /api/workflows/triggers/{id}/enable | enableTrigger() | Path: id | void | |
| POST | /api/workflows/triggers/{id}/disable | disableTrigger() | Path: id | void | |
| DELETE | /api/workflows/triggers/{id} | deleteTrigger() | Path: id | void | |
| POST | /api/workflows/webhook/{token} | webhookTrigger() | Body: `{input}` | void | 免密触发 |
| GET | /api/workflows/templates | listTemplates() | - | `[WorkflowTemplate]` | 内置模板 |
| POST | /api/workflows/templates/{id}/instantiate | instantiate() | Body: `{name,description}` | Workflow | 模板实例化 |

### 3.5 KnowledgeBaseController

| HTTP | URL | 方法 | 说明 |
|------|-----|------|------|
| GET | /api/kb | page() | 分页列表 |
| GET | /api/kb/{id} | detail() | 详情含 vectorCount |
| POST | /api/kb | create() | 新建 |
| PUT | /api/kb/{id} | update() | 编辑 |
| DELETE | /api/kb/{id} | remove() | 逻辑删除 |
| POST | /api/kb/{id}/enable / /disable | enable/disable() | 启停 |
| POST | /api/kb/{id}/files | upload() | multipart 上传文件 → 异步解析切分向量化 |
| GET | /api/kb/{id}/files | listFiles() | 文件列表（含 processStatus） |
| DELETE | /api/kb/files/{fid} | deleteFile() | 删文件同步清理向量 |
| POST | /api/kb/files/{fid}/reprocess | reprocess() | 重新分割向量化 |
| GET | /api/kb/{id}/search | search() | Query: query, topK → RagSearchResult（含三率） |

### 3.6 Tool API

| HTTP | URL | 方法 | 说明 |
|------|-----|------|------|
| GET | /api/chat/tools/page | page() | 分页（支持 toolType 过滤） |
| POST | /api/chat/tools | create() | 新增 |
| PUT | /api/chat/tools/{id} | update() | 编辑 |
| DELETE | /api/chat/tools/{id} | remove() | 逻辑删除 |
| POST | /api/chat/tools/{id}/debug | debug() | 在线调试（按 definition 解析参数 → 返回 ToolInvocationRecord） |
| GET | /api/chat/tools/skill/list-dirs | listSkillDirs() | 扫描磁盘 skill 目录 |
| POST | /api/chat/tools/skill/import | importSkill() | 导入磁盘 skill → DB（同名返回 duplicated=true） |
| POST | /api/chat/tools/skill/save | saveSkill() | 保存 skill 内容（更新 front matter） |

---

## 4. SSE 事件协议

### 4.1 普通 RAG 对话（非 Agent）

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| start | `{sessionId, windowId}` | SSE 连接建立 |
| retrieve | `{chunks: [{kbName, fileName, chunkIndex, score}]}` | RAG 检索完成 |
| content | `{text: "..."}` | LLM 流式生成，多次推送 |
| sources | `{sources: [{kbName, fileName, chunkIndex, score}]}` | 最终去重后的来源列表 |
| accuracy | `{recallRate, hitRate, accuracyRate}` | 三率指标 |
| done | `{messageId, latencyMs}` | 完成 |
| tool_call_start | `{toolName}` | 工具调用开始 |
| tool_call_result | `{toolName, success, durationMs, resultText}` | 工具调用返回 |

**协议约束**：所有 content 事件文本必须 JSON 包装（避免换行符丢失）。

### 4.2 Agent 自动规划

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| plan_created | `{planId, maxSteps, steps: [{stepId, title, hitl}]}` | Planner 生成完计划 |
| plan_complete | `{planId, summary, totalSteps, successSteps, failedSteps}` | 所有步骤执行完 |
| plan_failed | `{planId, reason, failedStepId}` | 计划无法继续 |
| plan_paused | `{planId, reason}` | HITL 暂停 |
| plan_resumed | `{planId}` | HITL 确认后续跑 |
| plan_cancelled | `{planId}` | 用户取消 |
| plan_updated | `{planId, steps}` | 执行中动态重规划 |
| step_start | `{stepId, stepIndex, title, latencyMs, toolCount}` | 步骤开始 |
| step_success | `{stepId, resultSummary, durationMs, tokenCount}` | 步骤成功 |
| step_failed | `{stepId, error, retryCount}` | 步骤失败 |
| step_skipped | `{stepId, reason}` | 步骤跳过 |
| step_reflection | `{stepId, score, pass, feedback, retryCount}` | 反思评分 |
| step_hitl_pending | `{id, stepId, stepIndex, expectedInput}` | HITL 等待确认（含 DB 主键 id） |

### 4.3 工作流执行

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| workflow_start | `{runId, workflowId, workflowName, totalNodes}` | 运行开始 |
| node_start | `{runId, nodeId, nodeType, nodeName, iteration}` | 节点开始 |
| node_output | `{runId, nodeId, nodeName, output, durationMs}` | 节点完成 |
| node_error | `{runId, nodeId, nodeName, error}` | 节点错误 |
| node_hitl_pending | `{runId, nodeRunId, nodeId, nodeName, prompt}` | HITL 等待确认 |
| workflow_complete | `{runId, output, durationMs, executedNodes}` | 运行成功 |
| workflow_failed | `{runId, nodeId, error}` | 运行失败 |
| workflow_cancelled | `{runId}` | 用户取消 |

### 4.4 SSE 统一格式

```
event: event_name
data: {"key":"value","key2":"value2"}

```

所有 payload 为单行 JSON（SseEmitter.event().name(x).data(json)），避免换行丢失。

---

## 5. 配置项详细设计

### 5.1 wgh.rag（rag-config / RagProperties）

| 参数 | 默认 | 类型 | 说明 |
|------|------|------|------|
| `pgvector.enabled` | true | boolean | pgvector 降级开关 |
| `pgvector.schema` | public | String | pgvector 表 schema |
| `upload.dir` | ./uploads | String | 文件上传目录 |
| `skill.base-path` | ./skills | String | Skill 磁盘目录 |
| `context.max-history-messages` | 20 | int | 超过触发分层压缩 |
| `context.max-history-tokens` | 4000 | int | 单轮输入 token 上限 |
| `context.enable-compression` | true | boolean | 总开关 |
| `context.recent-window-rounds` | 4 | int | 近期保留原文 |
| `context.mid-term-window-rounds` | 6 | int | 中期 LLM 摘要 |
| `context.long-term-facts-tokens` | 400 | int | 远期事实预算 |
| `context.summary-max-tokens` | 600 | int | 摘要输出上限 |
| `context.summary-timeout-seconds` | 20 | int | 压缩 LLM 超时 |

### 5.2 wgh.agent（AgentProperties）

| 参数 | 默认 | 类型 | 说明 |
|------|------|------|------|
| `enabled` | false | boolean | 全局开关 |
| `max-steps` | 15 | int | 最大步骤数 |
| `max-tool-rounds-per-step` | 5 | int | 单步骤工具调用循环上限 |
| `default-reflection-retries` | 3 | int | 反思重试次数 |
| `default-reflection-threshold` | 7.0 | double | 反思通过阈值 |
| `reflection-enabled` | true | boolean | 反思总开关 |
| `planner-prompt-version` | v1 | String | Planner prompt 版本 |

### 5.3 wgh.workflow（默认无独立 properties）

工作流引擎调度参数：`corePoolSize=2, maxPoolSize=8, queueCapacity=100`（ThreadPoolTaskExecutor）。

触发器 CRON 表达式：6 段（秒 分 时 日 月 周）。

---

## 6. 异常码定义

| HTTP | code | message | 场景 |
|------|------|---------|------|
| 200 | 200 | 操作成功 | 正常 |
| 400 | 400 | 参数校验失败 | 请求体缺字段 / 类型错 |
| 401 | 401 | 未授权 | Token 缺失 / 过期 / 伪造 |
| 403 | 403 | 权限不足 | 未来扩展 |
| 404 | 404 | 资源不存在 | 按 ID 查不到 |
| 422 | 422 | 校验失败 | 工作流图校验不通过 |
| 500 | 500 | 系统错误 | LLM 调用失败 / DB 异常（兜底） |

**注意**：当前部分异常也用 `code=500, message=操作失败` 兜底返回，生产环境需细化。

---

## 7. 序列与枚举值

### 7.1 Agent 计划状态（wgh_agent_plan.status）

| 值 | 说明 |
|----|------|
| PLANNING | Planner 生成中 |
| RUNNING | 执行中 |
| PAUSED | HITL 暂停 |
| COMPLETED | 所有步骤成功 |
| FAILED | 无法继续（重试耗尽/LLM 不可用） |
| CANCELLED | 用户取消 |

### 7.2 Agent 步骤状态（wgh_agent_step.status）

| 值 | 说明 |
|----|------|
| PENDING | 等待就绪 |
| READY | 依赖已满足，可执行 |
| RUNNING | 执行中 |
| SUCCESS | 执行成功 |
| FAILED | 执行失败 |
| SKIPPED | 依赖步骤失败被跳过 |
| HITL_PENDING | 等待人工确认 |

### 7.3 工作流运行状态（wgh_workflow_run.status）

| 值 | 说明 |
|----|------|
| RUNNING | 运行中 |
| PAUSED | HITL 暂停 |
| COMPLETED | 所有节点成功 |
| FAILED | 节点失败且 onError=STOP |
| CANCELLED | 用户取消 |

### 7.4 工作流节点运行状态（wgh_workflow_node_run.status）

| 值 | 说明 |
|----|------|
| PENDING | 等待入边就绪 |
| RUNNING | 执行中 |
| SUCCESS | 成功 |
| FAILED | 失败 |
| SKIPPED | 未走分支的下游节点自然跳过 |
| HITL_PENDING | 等待人工确认 |

### 7.5 聊天窗口评估方案（evaluation_scheme）

| 值 | 说明 |
|----|------|
| NONE | 不评估 |
| METRICS | 仅检索指标（MRR / NDCG / Hit Rate / Recall） |
| LLM_JUDGE | 仅生成侧评估（Faithfulness / Answer Relevancy） |
| BOTH | 双轨都开 |

### 7.6 Agent Critic 模型选择策略

1. 窗口显式指定 `agent_critic_model_ids` → 用指定的
2. 未指定 → 自动选择 2 个不同 provider 的 Chat 模型，且排除当前聊天窗口使用的 `chat_model_id` 和 `agent_planner_model_id`
3. 只有 1 个可用 Critic 模型 → 降级用这 1 个（或跳过，按实现）
4. 所有模型被禁用 → 跳过反思环节

### 7.7 工作流节点类型（node_type）

| 值 | sourceHandle | 说明 |
|----|-------------|------|
| START | default | |
| END | -（无出边） | |
| LLM | default | |
| RETRIEVAL | default | |
| TOOL | default | |
| CONDITION | true / false | 分支选择 |
| LOOP | loop / done | loop=循环体，done=结束后出口 |
| HITL | default | |
| VARIABLE | default | |
| WORKFLOW | default | 子流程调用 |

### 7.8 MCP Transport

| 值 | 说明 |
|----|------|
| stdio | 本地子进程（command + args） |
| http（Streamable HTTP） | 远程 MCP server（serverUrl + token） |
