# 多 Agent 协作框架设计 — 详细设计

> 版本：v1.0｜日期：2026-10-28｜对应总体设计：[multi-agent-overall-design.md](./multi-agent-overall-design.md)

---

## 目录

1. [数据库详细设计（扩 3 张表）](#1-数据库详细设计扩-3-张表)
2. [核心类详细设计](#2-核心类详细设计)
3. [接口详细设计](#3-接口详细设计)
4. [SSE 事件协议（扩展）](#4-sse-事件协议扩展)
5. [配置项详细设计](#5-配置项详细设计)
6. [序列与枚举值](#6-序列与枚举值)
7. [与现有模块集成点](#7-与现有模块集成点)
8. [前端详细设计](#8-前端详细设计)

---

## 1. 数据库详细设计（扩 3 张表）

### 1.1 wgh_m_agent_run（多 Agent 运行实例）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| run_id | VARCHAR(64) | NOT NULL, UNIQUE | 对外 UUID |
| session_id | BIGINT | NOT NULL | FK → wgh_chat_session.id |
| chat_window_id | BIGINT | NOT NULL | |
| execution_mode | VARCHAR(30) | NOT NULL | CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL / GRAPH_ROUTED / GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER |
| crew_config_json | JSONB | NOT NULL | 持久化配置（按 execution_mode 不同结构不同，见 §1.4） |
| agents_json | JSONB | NOT NULL | 参与 AgentInstance 数组 [{agentId, name, role, goal, systemPrompt, chatModelId, toolIds, hasMemory, enableReflection, reflectionModelId, reflectionThreshold}] |
| user_goal | TEXT | NOT NULL | 用户原始目标 |
| input_text | TEXT | | 本次运行输入 |
| output_text | TEXT | | 最终输出 |
| status | VARCHAR(20) | DEFAULT 'PLANNING' | PLANNING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED |
| current_round | INT | DEFAULT 0 | GroupChat 当前对话轮次 |
| max_rounds | INT | DEFAULT 20 | GroupChat 最大轮次 |
| dynamic_router_model_id | BIGINT | | LLM 动态路由器的模型 ID |
| dynamic_router_prompt | TEXT | | 路由器 prompt 模板 |
| termination_criteria | TEXT | | 终止条件表达式（如 `{{lastMessage}} contains "TERMINATE"`） |
| summarizer_agent_id | VARCHAR(32) | | 谁负责最终汇总（可为空=自动选最后一个发言者） |
| error_message | TEXT | | |
| start_time | TIMESTAMP | | |
| finish_time | TIMESTAMP | | |
| duration_ms | BIGINT | | |
| create_time / update_time / create_by / update_by / deleted | — | — | 公共字段 |

索引：`(session_id)`、`(execution_mode)`、`(status)`、`(chat_window_id, start_time)`

### 1.2 wgh_m_agent_step（每个 Agent 执行记录）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| run_id | BIGINT | NOT NULL | FK → wgh_m_agent_run.id |
| agent_id | VARCHAR(32) | NOT NULL | AgentInstance.agentId |
| step_index | INT | NOT NULL | 执行顺序（GroupChat=对话轮次序号；Crew/Graph=节点执行序号） |
| status | VARCHAR(20) | DEFAULT 'PENDING' | PENDING / READY / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING |
| role | VARCHAR(50) | | 该 Agent 的 role 快照 |
| input_context | JSONB | | Agent 执行时的完整输入（含对话历史/变量渲染结果） |
| output_text | TEXT | | Agent 输出文本 |
| tool_calls_json | JSONB | | 本次执行的工具调用记录 [{toolName, input, output, durationMs, success}] |
| rag_sources_json | JSONB | | 本次执行的 RAG 来源 [{kbName, fileName, chunkIndex, score}] |
| reflection_score | DOUBLE | | 反思评分（0-10） |
| reflection_feedback | TEXT | | 反思反馈 |
| thought | TEXT | | LLM 推理（可选） |
| hitl_expected_input | TEXT | | HITL 提示 |
| result_summary | TEXT | | 步骤产出摘要（传给后续 Agent） |
| round | INT | DEFAULT 0 | GroupChat 模式的对话轮次 |
| start_time | TIMESTAMP | | |
| end_time | TIMESTAMP | | |
| duration_ms | BIGINT | | |
| token_count | INT | DEFAULT 0 | |
| create_time / update_time / create_by / update_by / deleted | — | — | 公共字段 |

索引：`(run_id, agent_id)`、`(run_id, step_index)`、`(run_id, status)`、`(run_id, round)`

### 1.3 wgh_m_agent_crew（可复用的 Crew 配置）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGSERIAL | PK | |
| name | VARCHAR(100) | NOT NULL | Crew 名称（如「代码-测试-修复」） |
| description | VARCHAR(500) | | |
| execution_mode | VARCHAR(30) | NOT NULL | |
| agents_json | JSONB | NOT NULL | AgentInstance 数组 |
| graph_json | JSONB | | 仅 Graph 模式需要 DAG：`{nodes:[{id,type}], edges:[{source,target,sourceHandle}]}` |
| crew_config_json | JSONB | | Crew 模式配置：`{mode:"SEQUENTIAL"/"HIERARCHICAL"/"PARALLEL", managerAgentId:"..."}` |
| group_chat_config_json | JSONB | | GroupChat 配置：`{rounds:20, terminationCriteria:"...", routerPrompt:"...", dynamicRouterModelId:1}` |
| status | SMALLINT | DEFAULT 1 | 1=启用 0=停用 |
| create_time / update_time / create_by / update_by / deleted | — | — | 公共字段 |

索引：`(execution_mode)`、`(status)`

### 1.4 crew_config_json 结构（按 execution_mode 区分）

```jsonc
// CREW_SEQUENTIAL / CREW_PARALLEL
{
  "mode": "SEQUENTIAL" | "PARALLEL",
  "graph": {
    "nodes": [{ "id": "agent_a", "type": "AGENT", "agentId": "researcher" },
              { "id": "agent_b", "type": "AGENT", "agentId": "analyst" },
              { "id": "end", "type": "END" }],
    "edges": [{ "source": "agent_a", "target": "agent_b" },
              { "source": "agent_b", "target": "end" }]
  }
}

// CREW_HIERARCHICAL
{
  "mode": "HIERARCHICAL",
  "managerAgentId": "project_manager",   // 第一个执行，先规划分配子任务
  "workerAgentIds": ["researcher", "coder", "tester"],
  "sequential": false                     // true=Worker 顺序执行 / false=Worker 并行
}

// GRAPH_ROUTED
{
  "nodes": [
    { "id": "agent_1", "type": "AGENT", "agentId": "coder" },
    { "id": "cond_1", "type": "CONDITION", "expression": "{{agent_1.output}} contains 'PASS'" },
    { "id": "loop_1", "type": "LOOP", "maxIterations": 3, "breakCondition": "{{loop.iteration}} >= 3" },
    { "id": "agent_2", "type": "AGENT", "agentId": "tester" },
    { "id": "agent_3", "type": "AGENT", "agentId": "fixer" },
    { "id": "hitl_1", "type": "HITL", "prompt": "请审批修复方案" },
    { "id": "end", "type": "END" }
  ],
  "edges": [
    { "source": "start", "target": "agent_1" },
    { "source": "agent_1", "target": "cond_1" },
    { "source": "cond_1", "target": "end", "sourceHandle": "true" },
    { "source": "cond_1", "target": "loop_1", "sourceHandle": "false" },
    { "source": "loop_1", "target": "agent_2", "sourceHandle": "loop" },
    { "source": "loop_1", "target": "cond_1", "sourceHandle": "done" },
    { "source": "agent_2", "target": "agent_3" },
    { "source": "agent_3", "target": "agent_1" },
    { "source": "hitl_1", "target": "end" }
  ]
}

// GROUP_CHAT_ROUND_ROBIN
{
  "rounds": 20,
  "terminationCriteria": "{{lastMessage}} contains 'TERMINATE'",
  "roundRobinOrder": ["researcher", "analyst", "writer"],  // 固定轮询顺序
  "summarizerAgentId": null,                              // 自动选最后一个
  "hitlEnabled": true,
  "hitlAgentIds": ["human_proxy"]                          // 代表人类的 Agent
}

// GROUP_CHAT_DYNAMIC_ROUTER
{
  "rounds": 20,
  "terminationCriteria": "{{lastMessage}} contains 'TERMINATE'",
  "dynamicRouterModelId": 1,
  "routerPrompt": "你是 GroupChat 协调器。以下是本轮对话...请选择下一个应该发言的 Agent...",
  "summarizerAgentId": null
}
```

### 1.5 公共字段（同现有表）

```sql
create_time      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
update_time      TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
create_by        VARCHAR(50) DEFAULT 'system'
update_by        VARCHAR(50) DEFAULT 'system'
deleted          SMALLINT    DEFAULT 0
```

### 1.6 完整 DDL（init.sql 追加）

```sql
-- ========== 多 Agent 运行实例 ==========
CREATE TABLE IF NOT EXISTS wgh_m_agent_run (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL UNIQUE,
    session_id          BIGINT NOT NULL,
    chat_window_id      BIGINT NOT NULL,
    execution_mode      VARCHAR(30) NOT NULL,
    crew_config_json    JSONB NOT NULL,
    agents_json         JSONB NOT NULL,
    user_goal           TEXT NOT NULL,
    input_text          TEXT,
    output_text         TEXT,
    status              VARCHAR(20) DEFAULT 'PLANNING',
    current_round       INT DEFAULT 0,
    max_rounds          INT DEFAULT 20,
    dynamic_router_model_id BIGINT,
    dynamic_router_prompt  TEXT,
    termination_criteria TEXT,
    summarizer_agent_id VARCHAR(32),
    error_message       TEXT,
    start_time          TIMESTAMP,
    finish_time         TIMESTAMP,
    duration_ms         BIGINT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by           VARCHAR(50) DEFAULT 'system',
    update_by           VARCHAR(50) DEFAULT 'system',
    deleted             SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_m_ar_session  ON wgh_m_agent_run(session_id);
CREATE INDEX IF NOT EXISTS idx_m_ar_mode     ON wgh_m_agent_run(execution_mode);
CREATE INDEX IF NOT EXISTS idx_m_ar_status   ON wgh_m_agent_run(status);
CREATE INDEX IF NOT EXISTS idx_m_ar_window   ON wgh_m_agent_run(chat_window_id, start_time);

-- ========== Agent 执行记录 ==========
CREATE TABLE IF NOT EXISTS wgh_m_agent_step (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL,
    agent_id            VARCHAR(32) NOT NULL,
    step_index          INT NOT NULL,
    status              VARCHAR(20) DEFAULT 'PENDING',
    role                VARCHAR(50),
    input_context       JSONB,
    output_text         TEXT,
    tool_calls_json     JSONB,
    rag_sources_json    JSONB,
    reflection_score    DOUBLE,
    reflection_feedback TEXT,
    thought             TEXT,
    hitl_expected_input TEXT,
    result_summary      TEXT,
    round               INT DEFAULT 0,
    start_time          TIMESTAMP,
    end_time            TIMESTAMP,
    duration_ms         BIGINT,
    token_count         INT DEFAULT 0,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by           VARCHAR(50) DEFAULT 'system',
    update_by           VARCHAR(50) DEFAULT 'system',
    deleted             SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_m_as_run_agent ON wgh_m_agent_step(run_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_m_as_run_step  ON wgh_m_agent_step(run_id, step_index);
CREATE INDEX IF NOT EXISTS idx_m_as_status    ON wgh_m_agent_step(status);
CREATE INDEX IF NOT EXISTS idx_m_as_round     ON wgh_m_agent_step(run_id, round);

-- ========== 可复用 Crew 配置 ==========
CREATE TABLE IF NOT EXISTS wgh_m_agent_crew (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    execution_mode      VARCHAR(30) NOT NULL,
    agents_json         JSONB NOT NULL,
    graph_json          JSONB,
    crew_config_json    JSONB,
    group_chat_config_json JSONB,
    status              SMALLINT DEFAULT 1,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by           VARCHAR(50) DEFAULT 'system',
    update_by           VARCHAR(50) DEFAULT 'system',
    deleted             SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_m_crew_mode   ON wgh_m_agent_crew(execution_mode);
CREATE INDEX IF NOT EXISTS idx_m_crew_status ON wgh_m_agent_crew(status);
```

---

## 2. 核心类详细设计

### 2.1 AgentInstance（rag-agent-multi）

| 属性 | 类型 | 说明 |
|------|------|------|
| agentId | String | 唯一 ID |
| name | String | 显示名 |
| role | String | 角色（"研究员" / "分析师"） |
| goal | String | 核心目标 |
| systemPrompt | String | 独立 system prompt（可从 role+goal 自动构造） |
| chatModelId | Long | 独立模型 ID |
| kbIds | List\<Long\> | 独立知识库（RAG 用） |
| toolIds | List\<String\> | 独立工具白名单 |
| hasMemory | boolean | 是否保留独立对话历史 |
| enableReflection | boolean | 是否启用反思 |
| reflectionModelId | Long | Critic 模型 ID（可空=复用 chatModel） |
| reflectionThreshold | double | 默认 7.0 |

**核心方法**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| execute() | inputText: String, context: MultiAgentContext, emitter: SseEmitter | AgentInstanceResult | 复用 StepExecutor 执行：构建 prompt（systemPrompt + 对话历史 + inputText）→ ReAct 循环（工具调用 + RAG）→ 返回 result |
| execute() | inputText: String, history: List\<ChatMessage\>, context: MultiAgentContext, emitter: SseEmitter | AgentInstanceResult | GroupChat 模式重载：传入 Agent 视角的独立对话历史 |
| buildPrompt() | input: String, context: MultiAgentContext | ChatRequest | 组装：systemPrompt + 上下文压缩 + 对话历史 + inputText |
| receiveMessage() | msg: ChatMessage | void | GroupChat 模式：接收到其他 Agent 广播的消息 → 写入自身 history |

**AgentInstanceResult 结构**：

```jsonc
{
  "output": "...",                     // 主输出文本
  "thought": "...",                    // LLM 推理（可选）
  "toolInvocations": [{                // 工具调用记录
    "toolName": "...", "input": {...}, "output": "...", "durationMs": 500, "success": true
  }],
  "ragSources": [{                     // RAG 来源
    "kbName": "...", "fileName": "...", "chunkIndex": 0, "score": 0.82
  }],
  "reflectionScore": 8.5,              // 反思评分（如果 enableReflection=true）
  "reflectionFeedback": "...",
  "reflectionPass": true,
  "tokenCount": 1500,
  "durationMs": 2300
}
```

### 2.2 MultiAgentRuntime（rag-agent-multi）

**职责**：统一运行时。入口、execution_mode 路由、共享上下文管理、HITL 暂停恢复、状态持久化、SSE 事件统一推送。

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| execute() | runId: String, emitter: SseEmitter | void | 主入口：加载 MultiAgentRun → 根据 execution_mode 路由到 Coordinator.execute |
| resumeFromHitl() | runId: String, userInput: String, emitter: SseEmitter | void | PAUSED → RUNNING → 续跑 |
| cancelRun() | runId: String | void | CANCELLED → 未执行 steps 置 SKIPPED |
| loadRun() | runId: String | MultiAgentRun | 从 DB 加载 + 解析 agents_json / crew_config_json |
| saveRun() | run: MultiAgentRun | void | 持久化 |
| saveStep() | step: MultiAgentStep | void | 持久化到 wgh_m_agent_step |

**状态机**：

```java
// 复用现有 AgentPlan 状态机
enum RunStatus {
    PLANNING,    // 初始化中
    RUNNING,     // 执行中
    PAUSED,      // HITL 暂停
    COMPLETED,   // 所有 Agent 完成
    FAILED,      // 失败（可重试耗尽或 LLM 不可用）
    CANCELLED    // 用户取消
}
```

### 2.3 MultiAgentContext（rag-agent-multi）

**职责**：多 Agent 运行时上下文（替代现有 VariableResolver 的单一 context）。

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| put() | key: String, value: Object | void | 存 Agent 输出（`agentId + ".text"`） |
| get() | key: String | Object | 取 |
| render() | template: String | String | 渲染 `{{ path }}` 模板，支持 `{{agentId.text}}` / `{{agentId.toolResult}}` / `{{vars.x}}` / `{{loop.iteration}}` |
| setVars() | vars: Map\<String, Object\> | void | 变量池写入 |
| getVars() | - | Map | |
| incrementLoop() | loopId: String | void | 循环计数 +1 |
| getLoopIteration() | loopId: String | int | |
| setPendingAgents() | agentIds: Set\<String\> | void | GroupChat 模式：记录哪些 Agent 已收到广播消息待处理 |
| snapshot() | - | void | 关键节点快照（HITL 暂停前 / 每轮 GroupChat 后），用于恢复 |

### 2.4 CrewCoordinator（rag-agent-multi/crew）

**职责**：CrewAI 角色分层模式——Sequential / Hierarchical / Parallel。

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| execute() | run: MultiAgentRun, context: MultiAgentContext, emitter: SseEmitter | void | 根据 crewConfig.mode 路由 |
| executeSequential() | agents: List\<AgentInstance\>, graph: DAGGraph, context: MultiAgentContext | void | 按 DAG 边顺序执行；上一步 output 自动写入 context 供下一步用 |
| executeHierarchical() | managerAgentId: String, workers: List\<AgentInstance\>, context: MultiAgentContext | void | Manager Agent 先规划分配子任务 → Worker 执行 → Manager 汇总 |
| executeParallel() | graph: DAGGraph, context: MultiAgentContext | void | 复用 WorkflowEngine frontier 并发调度 + pendingInEdges fan-in 汇聚 |
| routeEdges() | node: GraphNode | List\<String\> | 后继节点选择（Crew 模式无条件路由，全部出边） |

**核心伪代码（executeParallel）**：

```java
public void executeParallel(MultiAgentRun run, MultiAgentContext ctx, SseEmitter emitter) {
    DAGGraph graph = parseGraph(run.getCrewConfigJson());
    Map<String, Integer> pendingInEdges = countInEdges(graph);
    Queue<String> frontier = new LinkedList<>(graph.startNodes());

    // 复用 WorkflowEngine 调度逻辑
    while (!frontier.isEmpty() && run.getStatus().equals("RUNNING")) {
        String nodeId = frontier.poll();
        pendingInEdges.merge(nodeId, -1, Integer::sum);
        if (pendingInEdges.getOrDefault(nodeId, 0) > 0) continue;

        GraphNode node = graph.getNode(nodeId);
        emitterExecutor.submit(() -> {
            // 1. 渲染入参模板
            String input = ctx.render(node.getInputTemplate());
            SseEvent.send(emitter, "agent_start", node.getAgentId());

            // 2. 执行 AgentInstance
            AgentInstance instance = ctx.getAgent(node.getAgentId());
            AgentInstanceResult result = instance.execute(input, ctx, emitter);

            // 3. 写回 context
            ctx.put(nodeId + ".text", result.getOutput());
            ctx.put(node.getAgentId() + ".text", result.getOutput());
            SseEvent.send(emitter, "agent_done", result);

            // 4. 持久化 step 记录
            MultiAgentStep step = buildStep(run, node, result);
            multiAgentRuntime.saveStep(step);

            // 5. 后继节点 pendingInEdges 递减
            for (GraphEdge edge : graph.getOutEdges(nodeId)) {
                pendingInEdges.merge(edge.getTarget(), -1, Integer::sum);
                if (pendingInEdges.getOrDefault(edge.getTarget(), 0) <= 0) {
                    frontier.add(edge.getTarget());
                }
            }
        });
    }

    // 6. 汇总 Collector Agent 或 Manager Agent 输出
    run.setOutputText(ctx.render("{{lastAgentId.text}}"));
    run.setStatus("COMPLETED");
    multiAgentRuntime.saveRun(run);
    SseEvent.send(emitter, "multi_agent_complete", run.getOutputText());
}
```

### 2.5 GraphCoordinator（rag-agent-multi/graph）

**职责**：LangGraph 状态图路由——在 CrewCoordinator 基础上叠加 CONDITION + LOOP 节点。

与 CrewCoordinator 的差异：

| 项 | CrewCoordinator | GraphCoordinator |
|---|----------------|-----------------|
| 节点类型 | 只有 AGENT | AGENT + CONDITION + LOOP + HITL + END |
| 路由条件 | 无条件（全部出边） | CONDITION 按 sourceHandle 选 true/false；LOOP 按 loop/done |
| 循环 | 不支持 | 原生 LOOP 节点 + breakCondition + maxIterations |
| 调度底座 | 复用 WorkflowEngine | 复用 WorkflowEngine（更完整：pendingInEdges + CONDITION/LOOP 分支选择） |

**核心伪代码（routeEdges）**：

```java
public List<String> routeEdges(GraphNode node, MultiAgentContext ctx) {
    return switch (node.getType()) {
        case AGENT -> graph.getOutEdges(node.getId()).stream()
                          .map(GraphEdge::getTarget).toList();
        case CONDITION -> {
            boolean result = conditionEvaluator.evaluate(node.getExpression(), ctx);
            String handle = result ? "true" : "false";
            yield graph.getOutEdges(node.getId()).stream()
                      .filter(e -> handle.equals(e.getSourceHandle()))
                      .map(GraphEdge::getTarget).toList();
        }
        case LOOP -> {
            String iteration = ctx.get("loop." + node.getId() + ".iteration");
            boolean shouldBreak = conditionEvaluator.evaluate(node.getBreakCondition(), ctx)
                                 || iteration >= node.getMaxIterations();
            String handle = shouldBreak ? "done" : "loop";
            yield graph.getOutEdges(node.getId()).stream()
                      .filter(e -> handle.equals(e.getSourceHandle()))
                      .map(GraphEdge::getTarget).toList();
        }
        case HITL -> {
            // 抛 PauseSignal → run.status=PAUSED → 等待 REST hitl-confirm
            throw new PauseSignal(node.getPrompt());
        }
        case END -> Collections.emptyList();
    };
}
```

### 2.6 GroupChatCoordinator（rag-agent-multi/groupchat）

**职责**：AutoGen 对话式——MessageBus + GroupChatManager + 对话循环。

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| execute() | run: MultiAgentRun, context: MultiAgentContext, emitter: SseEmitter | void | 对话循环主入口 |
| handleHitl() | agent: AgentInstance, prompt: String, emitter: SseEmitter | String | 暂停等待人工输入 → 返回 userInput |
| finalize() | summarizerAgentId: String, context: MultiAgentContext | String | 调用汇总 Agent → 最终答复 |

**对话循环伪代码**：

```java
public void execute(MultiAgentRun run, MultiAgentContext ctx, SseEmitter emitter) {
    List<AgentInstance> agents = parseAgents(run.getAgentsJson());
    MessageBus bus = new MessageBus(agents, ctx, emitter);
    GroupChatManager manager = createManager(run);

    // 初始化：userProxy（或第一个 Agent）先发出 userGoal
    bus.broadcast("user", ChatMessage.user(run.getUserGoal()));

    while (run.getStatus().equals("RUNNING") && run.getCurrentRound() < run.getMaxRounds()) {
        // 1. 终止条件检查
        if (manager.shouldTerminate(run, bus.getAllHistory())) break;

        // 2. 选择下一个发言者
        String nextSpeaker = manager.pickNextSpeaker(run, agents, bus.getAllHistory());
        AgentInstance speaker = bus.getAgent(nextSpeaker);

        // 3. 检查 HITL
        if (speaker.isHitlRequired()) {
            SseEvent.send(emitter, "agent_hitl_pending", speaker);
            run.setStatus("PAUSED");
            run.setCurrentRound(run.getCurrentRound() + 1);
            multiAgentRuntime.saveRun(run);
            return;  // 退出循环，等 REST hitl-confirm 续跑
        }

        // 4. Agent 执行（传入独立 history 视角）
        SseEvent.send(emitter, "agent_turn_start", speaker, run.getCurrentRound());
        AgentInstanceResult result = speaker.execute(
            run.getUserGoal(),
            bus.getHistory(nextSpeaker),   // Agent 自己视角的对话历史
            ctx,
            emitter
        );

        // 5. 广播输出
        bus.broadcast(nextSpeaker, ChatMessage.assistant(result.getOutput()));
        // 给每个 Agent 的 MessageHistory 追加
        // 落库到 wgh_m_agent_step（step_index=run.currentRound, round=run.currentRound）
        persistStep(run, speaker, result);

        SseEvent.send(emitter, "agent_turn_end", speaker, run.getCurrentRound(), result);
        run.setCurrentRound(run.getCurrentRound() + 1);
        multiAgentRuntime.saveRun(run);
    }

    // 6. 汇总：调用 summarizer Agent
    run.setStatus("COMPLETED");
    run.setOutputText(finalize(run, bus, ctx));
    multiAgentRuntime.saveRun(run);
    SseEvent.send(emitter, "multi_agent_complete", run.getOutputText());
}
```

### 2.7 MessageBus（rag-agent-multi/groupchat）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| send() | fromAgentId: String, toAgentId: String, msg: ChatMessage | void | 点对点 |
| broadcast() | fromAgentId: String, msg: ChatMessage | void | 广播所有参与者（除 fromAgent） |
| getHistory() | agentId: String | List\<ChatMessage\> | 指定 Agent 视角的对话历史 |
| getAllHistory() | - | List\<ChatMessage\> | 全部对话历史（router / termination 用） |
| subscribe() | agentId: String, handler: Consumer\<ChatMessage\> | void | AgentInstance 注册消息接收回调 |

**内部结构**：

```java
public class MessageBus {
    private final Map<String, List<ChatMessage>> perAgentHistory;   // 每个 Agent 独立 history
    private final List<ChatMessage> globalHistory;                  // 全局 history（给 router 用）
    private final Map<String, AgentInstance> agents;
    private final SseEmitter emitter;

    public void broadcast(String fromAgentId, ChatMessage msg) {
        globalHistory.add(msg);
        // 给每个 Agent 的 history 追加（包括发送者自己）
        for (AgentInstance agent : agents.values()) {
            perAgentHistory.get(agent.getAgentId()).add(msg);
            // 如果 agent.hasMemory=false 则只保留最近 N 条
        }
        // 所有订阅 handler 触发
        for (Consumer<ChatMessage> handler : subscribers.values()) {
            handler.accept(msg);
        }
        SseEvent.send(emitter, "agent_message", msg);
    }
}
```

### 2.8 GroupChatManager（rag-agent-multi/groupchat）

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| pickNextSpeaker() | run, agents, globalHistory | String（agentId） | 选择下一个发言者 |
| shouldTerminate() | run, globalHistory | boolean | 判断终止条件 |

**RoundRobinRouter 实现**：

```java
public String pickNextSpeaker(MultiAgentRun run, List<AgentInstance> agents, List<ChatMessage> history) {
    int idx = (run.getCurrentRound()) % agents.size();
    return agents.get(idx).getAgentId();
}
```

**DynamicRouter 实现**：

```java
public String pickNextSpeaker(MultiAgentRun run, List<AgentInstance> agents, List<ChatMessage> history) {
    // 1. 构建 Router Prompt
    String prompt = """
        %s
        
        可发言的 Agent：
        %s
        
        本轮对话历史（最新 %d 条）：
        %s
        
        请选择下一个应该发言的 Agent。返回 JSON: {"agentId": "...", "reason": "..."}
        """.formatted(
        run.getDynamicRouterPrompt(),
        buildAgentListPrompt(agents),
        MAX_HISTORY_FOR_ROUTER,
        formatHistory(history)
    );

    // 2. 调用 Router LLM
    ChatModel router = modelCacheManager.getChatModel(run.getDynamicRouterModelId());
    String response = router.chat(prompt);

    // 3. 解析返回的 agentId
    return parseAgentIdFromJson(response);
}
```

**shouldTerminate 实现**：

```java
public boolean shouldTerminate(MultiAgentRun run, List<ChatMessage> history) {
    // 条件 1：maxRounds 达到
    if (run.getCurrentRound() >= run.getMaxRounds()) return true;

    // 条件 2：最后一条消息含 TERMINATE
    ChatMessage last = history.getLast();
    if (last.getContent().contains("TERMINATE")) return true;

    // 条件 3：terminationCriteria 表达式求值（复用 ConditionEvaluator）
    if (run.getTerminationCriteria() != null) {
        MultiAgentContext ctx = new MultiAgentContext();
        ctx.put("lastMessage", last.getContent());
        return conditionEvaluator.evaluate(run.getTerminationCriteria(), ctx);
    }

    // 条件 4：连续 3 轮所有 Agent 回复相同或空
    // ...（略）
    return false;
}
```

---

## 3. 接口详细设计

### 3.1 MultiAgentController（新增）

| HTTP | URL | 方法 | 入参 | 出参 | 说明 |
|------|-----|------|------|------|------|
| **Crew 配置** | | | | | |
| GET | /api/agent-multi/crews | page() | Query: page, size, executionMode | PageResult | 分页 |
| GET | /api/agent-multi/crews/{id} | detail() | Path: id | CrewConfig | 详情 |
| POST | /api/agent-multi/crews | create() | Body: CrewCreateDTO | CrewConfig | 新建 |
| PUT | /api/agent-multi/crews/{id} | update() | Body: CrewUpdateDTO | CrewConfig | 编辑 |
| DELETE | /api/agent-multi/crews/{id} | remove() | Path: id | void | 逻辑删除 |
| POST | /api/agent-multi/crews/{id}/validate | validate() | - | `{valid, errors[]}` | Crew 配置校验 |
| **运行** | | | | | |
| POST | /api/agent-multi/runs | run() | Body: `{sessionId, executionMode, crewId, input}` 或内联 agents_json | SSE（见 §4） | 运行入口 |
| GET | /api/agent-multi/runs/{runId}/state | getRunState() | Path: runId | MultiAgentRun + steps[] | 刷新恢复 |
| POST | /api/agent-multi/runs/{runId}/hitl-confirm | hitlConfirm() | Body: `{userInput}` | void | HITL 续跑 |
| POST | /api/agent-multi/runs/{runId}/cancel | cancelRun() | Path: runId | void | 取消 |
| GET | /api/agent-multi/sessions/{sessionId}/run | getRunBySession() | Path: sessionId | MultiAgentRun? | 按会话查最新运行 |
| GET | /api/agent-multi/runs/{runId}/steps | listSteps() | Path: runId | `[MultiAgentStep]` | 步骤级记录回放 |
| **AgentInstance 预览** | | | | | |
| POST | /api/agent-multi/agent-preview | preview() | Body: AgentInstance + testInput | AgentInstanceResult | 在线调试单个 Agent 配置（复用现有 Tool Debug 模式） |

### 3.2 WorkflowController 扩展（AGENT nodeType）

现有 9 类节点 + 新增 1 类：

```java
// rag-workflow NodeExecutor 扩展
public NodeResult execute(Node node, WorkflowContext ctx) {
    return switch (node.getType()) {
        // ... 现有 9 类 ...
        case "AGENT" -> executeAgentNode(node, ctx);  // 新增
        default -> throw new IllegalArgumentException("未知 nodeType: " + node.getType());
    };
}

private NodeResult executeAgentNode(Node node, WorkflowContext ctx) {
    String crewConfigId = node.getConfig().get("crewConfigId");
    String inputTemplate = node.getConfig().getOrDefault("inputTemplate", "{{input}}");

    AgentInstance instance = loadAgentInstance(crewConfigId, node.getConfig().get("agentId"));
    String input = variableResolver.render(inputTemplate, ctx);
    AgentInstanceResult result = instance.execute(input, ctx);

    return NodeResult.success(Map.of("text", result.getOutput(), "sources", result.getRagSources()));
}
```

**Workflow DTO 扩展**：

```java
// WorkflowGraphValidator 新增 AGENT 节点校验
public class AgentNodeValidator implements NodeTypeValidator {
    public List<String> validate(Node node) {
        List<String> errors = new ArrayList<>();
        if (!node.getConfig().containsKey("crewConfigId"))
            errors.add("AGENT 节点需要 crewConfigId");
        if (!node.getConfig().containsKey("agentId"))
            errors.add("AGENT 节点需要 agentId");
        return errors;
    }
}
```

---

## 4. SSE 事件协议（扩展）

所有事件走 `event: xxx\ndata: {...}\n\n` 格式，payload 为单行 JSON（同现有协议）。

### 4.1 MultiAgent 运行级事件

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| multi_agent_start | `{runId, executionMode, agentCount, userGoal}` | 运行开始 |
| multi_agent_complete | `{runId, finalAnswer, totalRounds, totalDurationMs}` | 全部完成 |
| multi_agent_failed | `{runId, error, failedAgentId}` | 失败 |
| multi_agent_cancelled | `{runId}` | 用户取消 |
| multi_agent_paused | `{runId, reason, currentRound}` | HITL 暂停 |

### 4.2 AgentInstance 执行级事件

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| agent_start | `{agentId, agentName, role, modelName}` | AgentInstance 开始执行 |
| agent_done | `{agentId, output, durationMs, tokenCount}` | AgentInstance 完成 |
| agent_failed | `{agentId, error}` | AgentInstance 失败 |
| agent_reflection | `{agentId, score, pass, feedback}` | 反思评分 |

### 4.3 工具与 RAG 事件

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| agent_tool_call_start | `{agentId, toolName, input}` | Agent 内工具调用开始 |
| agent_tool_call_result | `{agentId, toolName, success, resultText, durationMs}` | 工具返回 |
| agent_rag | `{agentId, chunks: [{kbName, fileName, score}]}` | Agent 内 RAG 检索 |

### 4.4 GroupChat 对话专属事件

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| agent_turn_start | `{agentId, round, routerReason?}` | 每轮开始（含动态路由器选择理由） |
| agent_turn_end | `{agentId, round, output}` | 每轮结束 |
| agent_message | `{fromAgentId, content, timestamp}` | 广播消息（前端渲染对话气泡流） |
| agent_hitl_pending | `{agentId, prompt}` | GroupChat 中 HITL Agent 暂停 |

### 4.5 HITL 续跑事件

| 事件名 | payload JSON | 触发时机 |
|--------|-------------|---------|
| agent_hitl_resumed | `{agentId, userInput}` | REST hitl-confirm 成功 |
| agent_cancel_confirm | `{runId}` | REST cancelRun 成功 |

---

## 5. 配置项详细设计

### 5.1 wgh.agent-multi（RagProperties 扩展）

| 参数 | 默认 | 类型 | 说明 |
|------|------|------|------|
| `enabled` | true | boolean | 多 Agent 总开关 |
| `max-rounds` | 20 | int | GroupChat 默认最大轮次 |
| `default-reflection-threshold` | 7.0 | double | AgentInstance 默认反思阈值 |
| `default-tool-rounds-per-agent` | 5 | int | 单 Agent 内工具调用循环上限 |
| `max-history-for-router` | 30 | int | DynamicRouter LLM 输入的最大历史条数 |
| `enable-agent-debug-preview` | true | boolean | 是否允许在线调试 AgentInstance |
| `group-chat.default-router-model-id` | null | Long | 未指定 dynamic_router_model_id 时自动选择 |
| `thread-pool.core-pool-size` | 4 | int | 并行模式线程池核心 |
| `thread-pool.max-pool-size` | 8 | int | 并行模式线程池最大 |
| `thread-pool.queue-capacity` | 200 | int | 队列容量 |

### 5.2 RagProperties 扩展示例（YAML）

```yaml
wgh:
  agent-multi:
    enabled: true
    max-rounds: 20
    default-reflection-threshold: 7.0
    default-tool-rounds-per-agent: 5
    max-history-for-router: 30
    enable-agent-debug-preview: true
    thread-pool:
      core-pool-size: 4
      max-pool-size: 8
      queue-capacity: 200
```

---

## 6. 序列与枚举值

### 6.1 ExecutionMode 枚举

| 值 | 说明 | 前端标签 |
|----|------|---------|
| CREW_SEQUENTIAL | CrewAI 串行 | 角色分层（串行） |
| CREW_HIERARCHICAL | CrewAI 层级 | 角色分层（层级） |
| CREW_PARALLEL | CrewAI 并行 | 角色分层（并行） |
| GRAPH_ROUTED | LangGraph 状态图 | 状态图路由 |
| GROUP_CHAT_ROUND_ROBIN | AutoGen 固定轮询 | 对话式（固定轮询） |
| GROUP_CHAT_DYNAMIC_ROUTER | AutoGen LLM 动态路由 | 对话式（LLM 动态路由） |

### 6.2 RunStatus 枚举（wgh_m_agent_run.status）

| 值 | 说明 | 可转换到 |
|----|------|---------|
| PLANNING | 初始化中 | RUNNING / FAILED |
| RUNNING | 执行中 | PAUSED / COMPLETED / FAILED / CANCELLED |
| PAUSED | HITL 暂停 | RUNNING / CANCELLED |
| COMPLETED | 成功终态 | — |
| FAILED | 失败终态 | — |
| CANCELLED | 取消终态 | — |

### 6.3 StepStatus 枚举（wgh_m_agent_step.status）

| 值 | 说明 |
|----|------|
| PENDING | 等待就绪 |
| READY | 可执行 |
| RUNNING | 执行中 |
| SUCCESS | 成功 |
| FAILED | 失败 |
| SKIPPED | 跳过（依赖失败） |
| HITL_PENDING | HITL 等待 |

### 6.4 GraphNodeType 枚举

| 值 | 所属 Coordinator | 有出边 | sourceHandle | 说明 |
|----|-----------------|--------|-------------|------|
| START | 全部 | ✅ 无条件 | - | |
| AGENT | Crew / Graph | ✅ 无条件 | - | AgentInstance 执行 |
| CONDITION | Graph | ✅ 条件 | true / false | 布尔表达式 |
| LOOP | Graph | ✅ 条件 | loop / done | 循环 + breakCondition |
| HITL | Graph | ✅ 无条件 | - | 暂停等待人工 |
| END | 全部 | ❌ | - | 终止 |

---

## 7. 与现有模块集成点

### 7.1 rag-agent 模块（复用）

| 集成点 | 复用方式 | 不修改的原因 |
|--------|---------|-------------|
| StepExecutor | AgentInstanceExecutor 内部调用 | AgentOrchestrator 主链路不变 |
| ReflectionEngine | AgentInstance.enableReflection=true 时调用 | ReflectionEngine 独立可用 |
| Agent HITL 暂停恢复逻辑 | MultiAgentRuntime 复用 | HITL 是通用机制，多 Agent 只是另一个消费者 |
| Planner | CrewCoordinator.Hierarchical 模式的 Manager Agent 内部调用 | Planner 是 LLM 能力，AgentInstance 可以直接用 |

### 7.2 rag-workflow 模块（复用）

| 集成点 | 复用方式 | 不修改的原因 |
|--------|---------|-------------|
| WorkflowEngine frontier 调度 | CrewCoordinator.Parallel 模式 | 调度算法通用，多 Agent Parallel 就是 AGENT nodeType 的 Workflow 并行 |
| WorkflowEngine pendingInEdges | 同上 | fan-in 汇聚点等待机制通用 |
| ConditionEvaluator | GraphCoordinator.CONDITION + GroupChatManager.shouldTerminate | 表达式求值通用 |
| VariableResolver | MultiAgentContext.render() | 模板渲染通用，扩一个 `{{agentId.text}}` 就行 |
| NodeExecutor AGENT nodeType | switch-case 扩一个 case | 不影响现有 9 类节点 |
| HITL nodeType | GraphCoordinator | HITL 是通用节点类型 |

### 7.3 rag-tool 模块（复用）

| 集成点 | 复用方式 |
|--------|---------|
| ToolExecutionEngine | AgentInstance.execute() 内部 ReAct 循环调用 |
| ToolDefinitionParser | AgentInstance 初始化时按 toolIds 过滤 ToolDefinition |
| 窗口级 toolIds → AgentInstance 级扩展 | 新增 AgentInstance.toolIds 字段，窗口 toolIds 作为默认值，AgentInstance 可以覆盖 |

### 7.4 rag-kb / rag-llm / rag-chat（复用）

| 集成点 | 复用方式 |
|--------|---------|
| RagRetrieveService | AgentInstance kbIds 绑定后，execute() 内可直接调用 |
| ModelCacheManager | AgentInstance.chatModelId 独立路由，热更新自动生效 |
| CompressionService | AgentInstance 执行前可选对上下文进行分层压缩 |
| SessionService | MultiAgentRun.sessionId 关联到现有聊天会话 |

---

## 8. 前端详细设计

### 8.1 ChatWindows.vue 扩展

新增 tab 和配置表单：

```html
<!-- ChatWindows.vue 新增 -->
<el-tab-pane label="多 Agent Crew" name="multi-agent">
  <el-form-item label="协作模式">
    <el-radio-group v-model="form.multiAgentMode">
      <el-radio value="disabled">关闭（单 Agent Plan-and-Execute）</el-radio>
      <el-radio value="crew">CrewAI 角色分层</el-radio>
      <el-radio value="graph">LangGraph 状态图</el-radio>
      <el-radio value="groupchat">AutoGen 对话式</el-radio>
    </el-radio-group>
  </el-form-item>

  <!-- CrewAI 模式配置 -->
  <template v-if="form.multiAgentMode === 'crew'">
    <el-form-item label="调度方式">
      <el-select v-model="form.crewMode" style="width:100%">
        <el-option label="串行" value="SEQUENTIAL" />
        <el-option label="层级（Manager + Worker）" value="HIERARCHICAL" />
        <el-option label="并行" value="PARALLEL" />
      </el-select>
    </el-form-item>
  </template>

  <!-- GroupChat 模式配置 -->
  <template v-if="form.multiAgentMode === 'groupchat'">
    <el-form-item label="对话路由">
      <el-select v-model="form.groupChatRouter" style="width:100%">
        <el-option label="固定轮询" value="ROUND_ROBIN" />
        <el-option label="LLM 动态路由" value="DYNAMIC_ROUTER" />
      </el-select>
    </el-form-item>
    <el-form-item label="最大对话轮次">
      <el-input-number v-model="form.groupChatMaxRounds" :min="3" :max="50" />
    </el-form-item>
  </template>

  <!-- Agent 列表（三种模式都需要） -->
  <div class="agent-list">
    <div v-for="agent in form.agents" :key="agent.agentId" class="agent-card">
      <el-input v-model="agent.role" placeholder="角色，如：研究员" />
      <el-input v-model="agent.goal" placeholder="核心目标" />
      <el-select v-model="agent.chatModelId">模型下拉</el-select>
      <el-select v-model="agent.toolIds" multiple>工具下拉</el-select>
      <el-button type="danger" link @click="removeAgent(agent)">删除</el-button>
    </div>
    <el-button @click="addAgent">+ 添加 Agent</el-button>
  </div>
</el-tab-pane>
```

### 8.2 MultiAgentPlayground.vue（新增页面）

**路由**：`/chat/windows/:id/multi-agent-playground`

| 组件 | 职责 |
|------|------|
| 左侧会话列表 | 复用现有 ChatPlayground.vue 组件 |
| 中间对话气泡流 | 渲染 `agent_message` 事件，按 `fromAgentId` 区分头像/颜色/标签 |
| 右侧执行面板 | 按 execution_mode 切换：Crew=步骤时间线、Graph=节点轨迹（复用 AgentPlanTree）、GroupChat=对话轮次 + 当前发言者高亮 |
| HITL 确认弹窗 | 复用现有 AgentHITLConfirmDialog 组件 |
| 底部输入框 | 发送消息 → `POST /api/agent-multi/runs` |

**SSE 事件处理**：

```javascript
// MultiAgentPlayground.vue script setup
const handleEvent = (event, payload) => {
  switch (event) {
    case 'multi_agent_start':
      // 设置运行状态、Agent 列表
      break;
    case 'agent_start':
      // 更新右侧面板 Agent 状态为 RUNNING
      break;
    case 'agent_done':
      // 更新 Agent 状态为 SUCCESS、渲染气泡、刷新右侧统计
      break;
    case 'agent_message':
      // 中间对话气泡流追加一条气泡（按 fromAgentId 区分颜色）
      break;
    case 'agent_turn_start':
      // GroupChat 模式：当前发言者高亮
      break;
    case 'agent_hitl_pending':
      // HITL 弹窗弹出
      break;
    case 'multi_agent_complete':
      // 完成提示、保存消息
      break;
  }
};
```

### 8.3 WorkflowEditor.vue AGENT 节点

在现有 palette 里新增 AGENT 类型：

```html
<!-- 左侧 palette 新增 -->
<div v-for="item in palette" :key="item.type" class="palette-item">
  <!-- ... 现有 9 类 ... -->
  <div v-if="showMultiAgentNode" class="palette-item">
    <span class="p-icon" style="background:#9333ea">🤖</span>
    <span class="p-label">Agent（多Agent协作）</span>
  </div>
</div>
```

右侧配置抽屉：

```html
<!-- AGENT 节点配置 -->
<template v-if="selectedNode.data.nodeType === 'AGENT'">
  <div class="field">
    <label>Crew 配置</label>
    <el-select v-model="selectedNode.data.crewConfigId">下拉选择 Crew</el-select>
  </div>
  <div class="field">
    <label>使用哪个 Agent</label>
    <el-select v-model="selectedNode.data.agentId">下拉选择 AgentInstance</el-select>
  </div>
  <div class="field">
    <label>输入模板</label>
    <el-input v-model="selectedNode.data.inputTemplate" type="textarea" placeholder="{{input}} 或 {{prevNode.text}}" />
  </div>
</template>
```

---

## 附录：三种范式的错误码映射

| 场景 | HTTP | backend code | message |
|------|------|-------------|---------|
| Crew 配置缺 AgentInstance | 422 | 422 | Crew 配置校验失败：至少需要 2 个 Agent |
| Graph 缺少 CONDITION 出边 sourceHandle | 422 | 422 | 校验失败：CONDITION 节点需要 true/false 出边 |
| LOOP maxIterations 超限 | 400 | 400 | 参数校验失败：maxIterations 不得超过 20 |
| GroupChat agents_json 为空 | 422 | 422 | Crew 配置校验失败：agents 列表为空 |
| DynamicRouter 未指定 modelId | 400 | 400 | DynamicRouter 模式需要指定 dynamicRouterModelId |
| AgentInstance chatModelId 无效 | 400 | 400 | AgentInstance.chatModelId 不存在或已禁用 |
| AgentInstance toolIds 含无效 ID | 400 | 400 | AgentInstance.toolIds 包含无效工具 ID |
| GroupChat HITL Agent 无 hitlEnabled | 400 | 400 | HITL Agent 需要 hitlEnabled=true |
| RUNNING 状态重复 cancel | 200 | 200 | 幂等 |
| PAUSED 状态 hitl-confirm 但状态已变化 | 400 | 400 | run 状态不是 PAUSED，无法确认 |

---

> **文档修订（2026-10-28）**：测试体系与质量门禁更新 —— 单元测试 374 项、功能 e2e 113 项、压测 7 场景 8/8 全绿；测试代码统一英文命名 + 中文注释并纳入 CI 静态检查。本次改动不涉及本文档描述的架构与接口设计变更，完整数据见 `docs/test-report-20261028.md`。
