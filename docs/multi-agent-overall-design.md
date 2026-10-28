# 多 Agent 协作框架设计 — 总体设计

> 版本：v1.0｜日期：2026-10-28｜新增模块：rag-agent-multi
> 关联文档：[overall-design.md](./overall-design.md)｜[detail-design.md](./detail-design.md)｜[agent-design.md](./agent-design.md)

---

## 1. 设计概述

### 1.1 背景

当前系统已实现**单 Agent 编排器**（Plan-and-Execute + Reflection + HITL）与**确定性工作流引擎**（fan-out/fan-in + 子流程 + 触发器），但在"开放对话式专家协作""代码-测试-修复迭代""研究分工"等场景中，缺少**多个独立 Agent 自主协作**的能力。

### 1.2 设计目标

**在现有 Java Spring Boot 技术栈上，共存三种主流多 Agent 协作范式，一套共享底座，三种编排形态并行**，避免引入 Python 框架的 Java port（生态薄、维护成本高、架构冲突）。

### 1.3 三种范式共存原则

| 范式 | 心智模型 | 协作机制 | 业务场景 |
|------|---------|---------|---------|
| **CrewAI 角色分层** | 角色 Crew | Role/Goal 声明 + Sequential/Hierarchical/Parallel 调度 | 结构化任务（研究→分析→撰写、代码→测试→修复） |
| **LangGraph 状态图路由** | 显式状态图 | frontier 调度 + CONDITION 条件路由 + LOOP 循环重试 | 生产级、合规审计、循环迭代 |
| **AutoGen GroupChat** | 对话消息传递 | Agent 自主轮流发言 + GroupChatManager 动态路由 + 对话历史协商终止 | 开放讨论、多专家辩论、创意发散 |

**共存方式**：一套共享底座（AgentInstance + AgentRun + AgentStep + SSE + 持久化），上层三个 Coordinator 各自编排，用 `execution_mode` 字段路由到对应编排器。

---

## 2. 架构总览

### 2.1 三层叠加架构图

```
┌───────────────────────────────────────────────────────────────────────┐
│  前端层                                                               │
│                                                                       │
│  ChatWindows.vue（新增「多 Agent Crew」tab + execution_mode 选择器）  │
│  MultiAgentPlayground.vue（新增：Agent 对话气泡流 + SSE 事件渲染）    │
│  WorkflowEditor.vue（AGENT nodeType 拖入画布）                        │
│  api/index.js（multiAgent* 系列 API）                                 │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ HTTP + SSE
┌───────────────────────────────▼───────────────────────────────────────┐
│  Controller 层                                                        │
│                                                                       │
│  MultiAgentController（新增）                                         │
│  ├─ CrewConfig API：create / list / update / delete / validate        │
│  ├─ MultiAgentRun API：run / state / cancel / hitl-confirm            │
│  └─ 复用 AgentController 的 plan/step 控制接口（runId → planId 映射） │
│                                                                       │
│  WorkflowController（扩展）                                           │
│  └─ nodeType 新增 AGENT（可选：工作流内嵌 Agent Crew）                │
└───────────────────────────────┬───────────────────────────────────────┘
                                │
┌───────────────────────────────▼───────────────────────────────────────┐
│  多 Agent 编排层（新增 rag-agent-multi 模块）                          │
│                                                                       │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ CrewCoordinator   │  │ GraphCoordinator │  │ GroupChatCoordinator││
│  │（角色分层）        │  │（状态图路由）     │  │（对话式）          │  │
│  │                    │  │                  │  │                    │  │
│  │ Sequential 串行   │  │ frontier 调度    │  │ GroupChatManager  │  │
│  │ Hierarchical 层级 │  │ CONDITION 路由   │  │ MessageBus        │  │
│  │ Parallel 并行      │  │ LOOP 循环重试    │  │ 动态选下一个发言者│  │
│  │                    │  │ HITL 暂停恢复    │  │ 对话历史协商终止  │  │
│  └────────┬───────────┘  └────────┬─────────┘  └─────────┬──────────┘  │
│           │                       │                      │              │
│           └───────────┬───────────┴──────────────────────┘              │
│                       │  统一调度接口                                    │
│           ┌───────────▼───────────┐                                    │
│           │ MultiAgentRuntime    │  统一运行时                          │
│           │ execution_mode 路由   │                                    │
│           │ Context 状态管理       │                                    │
│           │ HITL 暂停恢复          │                                    │
│           │ SSE 事件统一推送       │                                    │
│           └───────────┬───────────┘                                    │
├───────────────────────┼────────────────────────────────────────────────┤
│                       │ 共享底座（全部复用现有代码）                     │
│                                                                       │
│  ┌────────────────────▼───────────────────────────────────────────┐   │
│  │ AgentInstance（新增：独立 Agent 对象）                          │   │
│  │   ├─ 复用 StepExecutor（ReAct 循环 + 工具调用 + RAG 检索）      │   │
│  │   ├─ 复用 ModelCacheManager（独立模型路由）                      │   │
│  │   ├─ 复用 ToolExecutionEngine（工具白名单隔离）                  │   │
│  │   ├─ 复用 ReflectionEngine（独立反思评分）                      │   │
│  │   └─ 新增：独立对话历史（MessageBus 订阅机制）                  │   │
│  │                                                                 │   │
│  │ 共享基础服务（已存在）                                          │   │
│  │   RagRetrieveService · VariableResolver（{{agentId.text}} 扩展） │   │
│  │   AgentPlan/AgentStep 持久化 · HITL 暂停恢复逻辑                │   │
│  │   SSE 事件协议（加 agent_* 几类）                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
├───────────────────────────────────────────────────────────────────────┤
│  现有模块（不修改主链路）                                              │
│                                                                       │
│  rag-agent（AgentOrchestrator 单 Agent 编排，不变）                    │
│  rag-workflow（WorkflowEngine 确定性 DAG 调度，不变）                   │
│  rag-tool（ToolExecutionEngine 4 类执行器，不变）                      │
│  rag-kb（RagRetrieveService + 切分策略，不变）                         │
│  rag-llm（ModelCacheManager 多模型，不变）                            │
└───────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────────────┐
│  数据层（PostgreSQL 16 + pgvector，扩 3 张表）                        │
│                                                                       │
│  wgh_m_agent_run       — 多 Agent 运行实例（execution_mode + agents[]）│
│  wgh_m_agent_step      — 每个 Agent 执行记录（独立对话历史）          │
│  wgh_m_agent_crew      — CrewConfig 持久化配置                        │
│                                                                       │
│  复用：wgh_agent_plan / wgh_agent_step / wgh_workflow_* / wgh_tool   │
└───────────────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖关系

```
rag-agent-multi（新增）
  ├─→ rag-agent（StepExecutor、ReflectionEngine、AgentOrchestrator 单 Agent 链路）
  ├─→ rag-workflow（WorkflowEngine frontier 调度逻辑、ConditionEvaluator、VariableResolver）
  ├─→ rag-tool（ToolExecutionEngine、ToolDefinitionParser）
  ├─→ rag-kb（RagRetrieveService）
  ├─→ rag-llm（ModelCacheManager）
  └─→ rag-chat（CompressionService、SessionService）

rag-controller → rag-agent-multi（新增 MultiAgentController）
rag-controller → rag-workflow（扩展 AGENT nodeType）

现有模块之间依赖不变（rag-chat → rag-agent/rag-tool/rag-kb/rag-llm，rag-agent-multi 是上层消费者）
```

**关键约束**：新增模块 **不修改** 现有 `AgentOrchestrator`、`WorkflowEngine`、`RagChatService` 的主链路。AGENT nodeType 通过 switch-case 扩展现有的 NodeExecutor，不影响现有 9 类节点。

---

## 3. 三种范式的统一执行模型

### 3.1 AgentInstance 抽象

三种范式的协作单位统一为 `AgentInstance`：

```java
public record AgentInstance(
    String agentId,                // 唯一 ID
    String name,                   // Agent 显示名
    String role,                   // "研究员" / "分析师" / "撰写者"（CrewAI 风格）
    String goal,                   // 该 Agent 的核心目标
    String systemPrompt,           // 独立 system prompt（可从 role + goal 自动构造）
    Long chatModelId,              // 独立 LLM 模型
    List<String> toolIds,          // 独立工具白名单（窗口级 → AgentInstance 级扩展）
    boolean hasMemory,             // 是否保留独立对话历史
    boolean enableReflection,      // 是否启用反思
    Long reflectionModelId,        // 独立 Critic 模型（可空=复用 chatModel）
    double reflectionThreshold     // 反思通过阈值（默认 7.0）
) {}
```

**直接复用现有组件**：

| 能力 | 复用来源 | 复用方式 |
|------|---------|---------|
| LLM 调用 | ModelCacheManager.getChatModel(chatModelId) | AgentInstance 传入独立 modelId |
| 工具调用 | ToolExecutionEngine.execute(toolConfig) | 按 toolIds 过滤可用工具列表 |
| RAG 检索 | RagRetrieveService.searchMultiple(kbIds, query) | AgentInstance 可绑定独立 kbIds |
| ReAct 循环 | StepExecutor.execute(step, context) | 每个 AgentInstance 的执行就是一次 StepExecutor 调用 |
| 反思评分 | ReflectionEngine.evaluate(step, result) | 可选开启 |
| HITL | Agent HITL 暂停恢复逻辑 | Crew/Graph 的 HITL 节点与 GroupChat 的 UserProxyAgent 统一走 hitl-confirm REST |

### 3.2 MultiAgentRun 统一运行时

```java
@Entity("wgh_m_agent_run")
public class MultiAgentRun {
    Long id;
    String runId;                         // 对外 UUID
    String sessionId;                     // 关联聊天会话
    Long chatWindowId;
    ExecutionMode executionMode;          // CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL
                                          // GRAPH_ROUTED
                                          // GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER
    String crewConfigJson;                // 持久化的 CrewConfig / Graph / GroupChat 配置
    String userGoal;                      // 用户原始目标
    String inputText;                     // 本次运行输入
    String status;                         // RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED
    String outputText;                    // 最终输出
    Integer currentRound;                 // GroupChat 当前对话轮次
    Integer maxRounds;                    // GroupChat 最大对话轮次
    String dynamicRouterPrompt;           // GroupChat LLM 路由器的 prompt 模板
    String terminationCriteria;           // 终止条件表达式（如 "{{lastMessage}} contains 'TERMINATE'"）
    String errorMessage;
    LocalDateTime startTime;
    LocalDateTime finishTime;
    Long durationMs;
    // 公共字段 create_time / update_time / deleted
}
```

### 3.3 统一状态机

三种范式的运行状态机统一：

```
PLANNING → RUNNING ─┬─ PAUSED ─→ RUNNING ─→ COMPLETED
                    │  (HITL)
                    ├─ FAILED
                    └─ CANCELLED
```

---

## 4. 三种范式的编排调度

### 4.1 CrewCoordinator（角色分层）

#### 4.1.1 Sequential 串行模式

```
Manager Agent（可选，Hierarchical 模式独有）
    │
    ▼
Agent A（研究员） → Agent B（分析师） → Agent C（撰写者） → 结束
    │                    │                      │
    └── A.output 传入 B ──┘                      │
    └───── B.output 传入 C ──────────────────────┘
```

调度逻辑：按 DAG 边顺序执行，上一步 `{{agentId.text}}` 自动渲染到下一步 context。直接复用 WorkflowEngine 的 frontier 调度（串行时 frontier 每次只有一个节点）。

#### 4.1.2 Hierarchical 层级模式

```
┌─ Manager Agent（先用 Planner 能力分配子任务给每个 Worker Agent）
│  │
│  ├── Worker Agent A → 返回 sub-task result 给 Manager
│  ├── Worker Agent B → 返回 sub-task result 给 Manager
│  └── Worker Agent C → 返回 sub-task result 给 Manager
│
└─ Manager Agent 汇总所有 Worker 结果 → 最终答复
```

Manager Agent 是一个特殊 AgentInstance，第一步先 LLM 规划分配子任务，然后并行（或串行）执行 Worker Agent，最后汇总。复用现有 Planner 能力做子任务拆分。

#### 4.1.3 Parallel 并行模式

```
          ┌→ Agent A ─┐
START ────┼→ Agent B ─┼→ Collector Agent → 结束
          └→ Agent C ─┘
```

直接复用 WorkflowEngine 的 frontier 并发调度 + pendingInEdges fan-in 汇聚。Collector Agent 收到所有 Worker Agent 输出后汇总。

### 4.2 GraphCoordinator（状态图路由）

在 CrewCoordinator 基础上叠加 CONDITION + LOOP 路由：

```
START → Agent A → CONDITION（A 输出是否达标？）
                      │              │
                   true ←─────── false
                   (结束)     LOOP 节点 → Agent B（修复）→ Agent A（重新执行）
                                    ↑                    │
                                    └────────────────────┘
                                     （最多 3 轮迭代）
```

**调度逻辑**：

- frontier + pendingInEdges：复用 WorkflowEngine 现有 fan-out/fan-in 调度代码
- CONDITION 节点：复用 `ConditionEvaluator.evaluate(expression, context)`，按 `sourceHandle` 选 true/false 分支
- LOOP 节点：复用现有 breakCondition / iteration 计数逻辑
- AGENT 节点：调用 `AgentInstance.execute(input, toolRegistry, context)`，输出写入 `context.put(agentId + ".text", result)`
- HITL 节点：复用现有 HITL 暂停恢复逻辑（`node_hitl_pending` SSE + 状态落库 + REST hitl-confirm）

**GraphCoordinator 与 CrewCoordinator 的代码复用率：~85%**。差异点仅在 CONDITION/LOOP 路由分支选择（Crew 模式无条件路由，Graph 模式有）。

### 4.3 GroupChatCoordinator（对话式）

#### 4.3.1 核心组件

```java
public class GroupChatCoordinator {
    MessageBus bus;                    // 消息总线
    GroupChatManager manager;          // 对话路由器
    List<AgentInstance> agents;        // 参与 Agent 列表
    AgentInstance userProxy;           // 特殊 Agent：代表人类（HITL）
    MultiAgentRun run;                 // 运行时上下文
    SseEmitter emitter;
}
```

#### 4.3.2 MessageBus 消息总线（唯一新增层）

```java
public class MessageBus {
    // Agent 间直接发消息（点对点）
    public void send(String fromAgentId, String toAgentId, ChatMessage msg) {
        getAgent(toAgentId).receive(msg);
        appendToHistory(msg);  // 所有消息落库到 wgh_m_agent_step
    }

    // 广播给所有参与者
    public void broadcast(String fromAgentId, ChatMessage msg) {
        for (AgentInstance agent : agents) {
            if (!agent.getAgentId().equals(fromAgentId)) {
                agent.receive(msg);
            }
        }
        appendToHistory(msg);
        SseEvent.send(emitter, "agent_message", msg);
    }

    // 获取对话历史（某个 Agent 视角）
    public List<ChatMessage> getHistory(String agentId) {
        return history.getOrDefault(agentId, Collections.emptyList());
    }
}
```

#### 4.3.3 GroupChatManager 对话路由

两种选择策略：

| 策略 | 实现 | 适用场景 |
|------|------|---------|
| **RoundRobin（固定轮询）** | 按 agents 列表顺序轮流，userProxy 固定位置 | 简单场景、可预测 |
| **DynamicRouter（LLM 动态路由）** | 用独立 Router LLM，根据对话历史 + 每个 Agent 的 role/goal → 选择下一个说话者 | 开放讨论、复杂协商 |

```java
public class GroupChatManager {
    public String pickNextSpeaker(MultiAgentRun run, List<AgentInstance> agents, List<ChatMessage> history) {
        switch (run.getExecutionMode()) {
            case GROUP_CHAT_ROUND_ROBIN:
                int idx = (currentRound++) % agents.size();
                return agents.get(idx).getAgentId();

            case GROUP_CHAT_DYNAMIC_ROUTER:
                // 构建 Router prompt：
                // 你是 GroupChat 协调器。以下是本轮对话历史...
                // 以下是可发言的 Agent 列表...
                // 请选择下一个应该发言的 Agent，并说明理由。
                ChatModel router = modelCacheManager.getChatModel(run.getDynamicRouterModelId());
                String response = router.chat(prompt);
                return parseAgentIdFromResponse(response);
        }
    }

    public boolean shouldTerminate(MultiAgentRun run, List<ChatMessage> history) {
        // 终止条件（任一满足即终止）：
        // 1. 最后一条消息包含 "TERMINATE"
        // 2. currentRound >= maxRounds
        // 3. 连续 3 轮所有 Agent 无实质回复（相同或空）
        // 4. terminationCriteria 表达式求值为 true
        //    （复用 ConditionEvaluator）
    }
}
```

#### 4.3.4 对话循环

```java
public void run() {
    // 初始化：userProxy 先发出用户原始 goal
    bus.broadcast("userProxy", ChatMessage.user(userGoal));

    while (!manager.shouldTerminate(run, bus.getHistory())) {
        String nextSpeaker = manager.pickNextSpeaker(run, agents, bus.getHistory());
        AgentInstance agent = bus.getAgent(nextSpeaker);

        // Agent 执行：
        // 1. 构建 Agent 视角的对话历史
        // 2. AgentInstance.execute(context) → LLM + 可选工具调用
        // 3. 输出写入 bus.broadcast
        SseEvent.send(emitter, "agent_turn_start", agent);

        AgentResult result = agent.execute(
            buildAgentPrompt(agent, bus.getHistory(nextSpeaker)),
            toolRegistry,
            run.getContext()
        );

        bus.broadcast(nextSpeaker, ChatMessage.assistant(result.output));
        SseEvent.send(emitter, "agent_turn_end", result);

        // 如果是 HITL 类型 Agent → 暂停等待人工确认
        if (agent.isHitlRequired()) {
            SseEvent.send(emitter, "agent_hitl_pending", agent);
            run.setStatus("PAUSED");
            return;  // 退出循环，等 hitl-confirm REST 续跑
        }
    }

    // 所有 Agent 连续回复 → Manager 汇总 → 最终答复
    AgentInstance summarizer = getSummarizerAgent(run);
    String finalAnswer = summarizer.execute(
        "请汇总以上对话所有 Agent 的输出，给出最终答复。",
        bus.getHistory("summarizer")
    ).getOutput();

    run.setOutputText(finalAnswer);
    run.setStatus("COMPLETED");
    SseEvent.send(emitter, "multi_agent_complete", finalAnswer);
}
```

### 4.4 三种范式的边界关系

```
┌──────────────────────────────────────────────────────────┐
│                    MultiAgentRuntime                     │
│                                                          │
│  execution_mode 路由 → Coordinator.execute(run, emitter) │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │  CrewCoordinator                                    │ │
│  │  ├─ Sequential: frontier 串行 + VariableResolver     │ │
│  │  ├─ Hierarchical: Manager Agent + 子任务分配         │ │
│  │  └─ Parallel: frontier 并发 + pendingInEdges fan-in  │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │  GraphCoordinator（在 Crew 基础上叠加）              │ │
│  │  ├─ frontier + pendingInEdges fan-out/fan-in        │ │
│  │  ├─ CONDITION + sourceHandle 分支选择               │ │
│  │  └─ LOOP + breakCondition 循环迭代                  │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │  GroupChatCoordinator（独立编排器）                  │ │
│  │  ├─ MessageBus: send / broadcast / history          │ │
│  │  ├─ GroupChatManager: RoundRobin / LLM Router        │ │
│  │  └─ shouldTerminate: 4 种终止条件                   │ │
│  └────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

---

## 5. HITL 统一模型

三种范式的 HITL 暂停恢复统一走 `hitl-confirm` REST 接口：

| 范式 | HITL 触发点 | SSE 事件 | 续跑方式 |
|------|------------|---------|---------|
| Crew | 某个 AgentInstance 标记 `hitlRequired=true` | `agent_hitl_pending` | POST /api/agent-multi/runs/{runId}/hitl-confirm |
| Graph | HITL nodeType 节点 | `agent_hitl_pending` | 同上 |
| GroupChat | userProxy 或特殊 HITL Agent 轮次 | `agent_hitl_pending` | 同上 |

续跑后恢复逻辑：

- PAUSED → RUNNING
- frontier = pending node IDs（Crew/Graph）或 currentRound（GroupChat）
- 继续执行循环
- 同现有 Agent 和工作流的 HITL 恢复机制完全一致

---

## 6. 前端设计

### 6.1 协作模式选择

ChatWindows.vue 新增「多 Agent Crew」tab：

```
多 Agent 协作模式：
┌────────────────────────────────────────────────────┐
│  ○ 关闭（使用单 Agent Plan-and-Execute）            │
│  ○ CrewAI 角色分层 ──→ [串行 / 层级 / 并行] 下拉   │
│  ○ LangGraph 状态图 ──→ 可视化 DAG 画布            │
│  ○ AutoGen 对话式 ──→ [固定轮询 / LLM 动态路由]    │
└────────────────────────────────────────────────────┘
```

### 6.2 AgentInstance 配置表单

```
Agent 列表（可动态添加/删除）：
┌─────────────────────────────────────────────────┐
│ Agent 1: [研究员]                                │
│   角色名称: 研究员                              │
│   核心目标: 搜集多源数据                         │
│   System Prompt: [自动填充或自定义]              │
│   LLM 模型: [下拉选择]                          │
│   可用工具: [多选 toolIds]                       │
│   启用反思: ☑ 阈值 [7.0] Critic 模型: [下拉]    │
│   保留独立对话历史: ☑                            │
└─────────────────────────────────────────────────┘
```

### 6.3 MultiAgentPlayground.vue（新增页面）

```
┌────────────────────────────────────────────────────────────┐
│  左侧：会话列表（复用现有 ChatPlayground）                   │
├────────────────────────────────────────────────────────────┤
│  中间：多 Agent 对话气泡流                                    │
│                                                              │
│  [🔵 研究员] 在 Wikipedia 上搜索... → 找到 5 个相关条目      │
│  [🟢 分析师] 这 5 个条目中... → 关键矛盾是 XXX              │
│  [🟡 撰写者] 汇总以上发现 → 初稿完成                        │
│  [⏸ 人工确认] 是否继续修改？ [输入框] [稍后处理] [确认继续]   │
│  [🔵 研究员] 根据反馈补充... → 更新条目 2                    │
│  ✅ 最终答复：...                                            │
│                                                              │
├────────────────────────────────────────────────────────────┤
│  右侧：执行面板（按 execution_mode 显示不同内容）             │
│                                                              │
│  Crew 模式：步骤时间线（Agent A → B → C）                    │
│  Graph 模式：节点轨迹（复用现有 AgentPlanTree）              │
│  GroupChat 模式：对话轮次 + 当前发言者高亮 + 路由理由          │
└────────────────────────────────────────────────────────────┘
```

---

## 7. SSE 事件协议扩展

在现有 Agent 事件基础上新增多 Agent 专属事件：

| 事件名 | payload JSON | 触发时机 | 现有 Agent/工作流事件不受影响 |
|--------|-------------|---------|------------------------------|
| agent_start | `{agentId, agentName, role, modelName}` | AgentInstance 开始执行 | ✅ 新增 |
| agent_done | `{agentId, output, durationMs, tokenCount}` | AgentInstance 完成 | ✅ 新增 |
| agent_failed | `{agentId, error}` | AgentInstance 失败 | ✅ 新增 |
| agent_tool_call_start | `{agentId, toolName}` | Agent 内工具调用 | ✅ 新增 |
| agent_tool_call_result | `{agentId, toolName, success, resultText}` | 工具返回 | ✅ 新增 |
| agent_message | `{fromAgentId, content, timestamp}` | GroupChat 广播 | ✅ 新增 |
| agent_turn_start | `{agentId, round}` | GroupChat 每轮开始 | ✅ 新增 |
| agent_turn_end | `{agentId, round, output}` | GroupChat 每轮结束 | ✅ 新增 |
| agent_hitl_pending | `{agentId, prompt}` | HITL 暂停 | ✅ 新增 |
| agent_hitl_resumed | `{agentId, userInput}` | HITL 确认续跑 | ✅ 新增 |
| multi_agent_start | `{runId, executionMode, agentCount}` | 多 Agent 运行开始 | ✅ 新增 |
| multi_agent_complete | `{runId, finalAnswer, totalRounds}` | 全部完成 | ✅ 新增 |
| multi_agent_failed | `{runId, error}` | 失败 | ✅ 新增 |
| multi_agent_cancelled | `{runId}` | 取消 | ✅ 新增 |
| multi_agent_paused | `{runId, reason}` | PAUSED | ✅ 新增 |

---

## 8. 部署与依赖

### 8.1 Maven 依赖

```xml
<!-- rag-agent-multi 模块 -->
<dependencies>
    <dependency>rag-agent</dependency>       <!-- StepExecutor, ReflectionEngine, AgentOrchestrator -->
    <dependency>rag-workflow</dependency>    <!-- WorkflowEngine frontier 调度, ConditionEvaluator -->
    <dependency>rag-tool</dependency>        <!-- ToolExecutionEngine -->
    <dependency>rag-kb</dependency>          <!-- RagRetrieveService -->
    <dependency>rag-llm</dependency>         <!-- ModelCacheManager -->
    <dependency>rag-chat</dependency>         <!-- CompressionService, SessionService -->
    <dependency>rag-common</dependency>      <!-- Result, JwtUtils -->
</dependencies>
```

### 8.2 包结构

```
rag-agent-multi/
├── src/main/java/com/wgh/rag/agentmulti/
│   ├── MultiAgentRuntime.java           // 统一运行时 + execution_mode 路由
│   ├── AgentInstance.java               // 独立 Agent 抽象
│   ├── AgentInstanceExecutor.java       // AgentInstance 执行器（复用 StepExecutor）
│   ├── context/
│   │   ├── MultiAgentContext.java        // 运行时状态（nodeOutputs / vars / history）
│   │   └── MessageHistory.java           // GroupChat 对话历史持久化
│   ├── crew/
│   │   ├── CrewCoordinator.java          // Sequential / Hierarchical / Parallel 调度
│   │   └── CrewConfig.java               // Crew 配置 DTO
│   ├── graph/
│   │   ├── GraphCoordinator.java         // CONDITION + LOOP 路由调度
│   │   ├── GraphNode.java                // 节点定义（AGENT / CONDITION / LOOP / HITL）
│   │   └── GraphEdge.java                // 边定义（含 sourceHandle）
│   └── groupchat/
│       ├── GroupChatCoordinator.java     // 对话循环主入口
│       ├── MessageBus.java               // send / broadcast / history
│       ├── GroupChatManager.java         // 对话路由 + 终止条件
│       ├── RoundRobinRouter.java         // 固定轮询
│       ├── DynamicRouter.java            // LLM 动态路由
│       └── TerminationCriteria.java      // 终止条件求值
└── src/main/resources/
    └── application.yml                   // wgh.agent-multi.* 配置
```

---

## 9. 增量实施路径

| 阶段 | 交付物 | 核心类 | 预估 | 验证标准 |
|------|--------|--------|------|---------|
| **P0 底座抽象** | AgentInstance + MultiAgentRuntime + AgentInstanceExecutor（复用 StepExecutor） | AgentInstance / AgentInstanceExecutor / MultiAgentRuntime | 3-5 天 | 单 Agent 也能跑在新接口上（先统一接口不改变现有链路） |
| **P1 CrewCoordinator** | Sequential + Hierarchical + Parallel 三种模式 | CrewCoordinator / CrewConfig | 3-5 天 | 代码→测试→修复 Crew 跑通完整链路 |
| **P2 GraphCoordinator** | CONDITION + LOOP 路由（复用 WorkflowEngine frontier） | GraphCoordinator / GraphNode / GraphEdge | 3-5 天 | 循环重试场景（Agent A 写代码 → CONDITION 判断 → Agent B 测试 → LOOP 迭代） |
| **P3 GroupChatCoordinator** | MessageBus + GroupChatManager + 两种路由策略 | GroupChatCoordinator / MessageBus / GroupChatManager | 5-7 天 | 多专家辩论（3 Agent 对话 5 轮 + 动态路由器 + 协商终止） |
| **P4 前端 + API + 持久化** | 3 张 DB 表 + MultiAgentController + MultiAgentPlayground.vue | （前端 + Controller + Mapper） | 5-7 天 | 三种模式全部端到端可用 + 刷新恢复 + HITL 确认 |
| **合计** | | | **19-29 天** | |

---

## 10. 非目标与约束

| 约束 | 说明 |
|------|------|
| **不修改现有单 Agent/工作流主链路** | rag-agent/AgentOrchestrator 和 rag-workflow/WorkflowEngine 保持原样，互不干扰 |
| **不引入 LangGraph/CrewAI/AutoGen Java port** | 生态薄、维护成本高、架构冲突；全部自研但精准复用现有组件 |
| **不在第一期做 WebSocket** | SSE 流式推送已足够，WebSocket 可作为后续扩展 |
| **GroupChat 对话历史内存优先 + 增量落库** | 高频对话全落库性能差，采用内存 history + 关键节点快照落库 |
| **不做跨 Session 长期记忆** | 每个 MultiAgentRun 独立，无跨会话持久化记忆（AgentInstance 是运行时实例，不是常驻进程） |
| **生产级可观测性（LangSmith 级）** | 当前仅 SSE + DB 记录；可接入 ELK/Prometheus 作为后续扩展 |

---

## 附录：三种范式的代码复用矩阵

| 能力 | CrewCoordinator | GraphCoordinator | GroupChatCoordinator | 复用来源 |
|------|----------------|-----------------|---------------------|---------|
| Agent 执行 | ✅ AgentInstance.execute | ✅ AgentInstance.execute | ✅ AgentInstance.execute | StepExecutor + ToolExecutionEngine |
| 模型路由 | ✅ 独立 chatModelId | ✅ 独立 chatModelId | ✅ 独立 chatModelId | ModelCacheManager |
| 工具隔离 | ✅ toolIds 白名单 | ✅ toolIds 白名单 | ✅ toolIds 白名单 | ToolExecutionEngine |
| RAG 检索 | ✅ 可选 kbIds | ✅ 可选 kbIds | ✅ 可选 kbIds | RagRetrieveService |
| 反思评分 | ✅ 可选 enableReflection | ✅ 可选 enableReflection | ✅ 可选 enableReflection | ReflectionEngine |
| 并行 fan-out | ✅ Parallel 模式 | ✅ 原生支持 | ❌ 对话式串行 | WorkflowEngine frontier |
| fan-in 汇聚 | ✅ Parallel 模式 | ✅ 原生 pendingInEdges | ❌ 无（对话式） | WorkflowEngine pendingInEdges |
| 条件路由 | ❌ 无（只顺序/层级/并行） | ✅ CONDITION | ❌ Agent 自己协商 | ConditionEvaluator |
| 循环重试 | ❌ 无 | ✅ LOOP | ❌ Agent 自己协商 | WorkflowEngine LOOP 节点 |
| HITL 暂停 | ✅ AgentInstance 标记 | ✅ HITL nodeType | ✅ userProxy Agent | Agent HITL 恢复逻辑 |
| 动态重规划 | ❌ 固定 DAG | ❌ 固定 DAG | ✅ GroupChat 自然支持 | Planner（可选接入） |
| 对话消息传递 | ❌ 无（变量引用） | ❌ 无（变量引用） | ✅ MessageBus.send/broadcast | **新增** |
| 对话历史 | ❌ 各自独立 context | ❌ 各自独立 context | ✅ 共享 history 持久化 | **新增** |
| 对话路由 | ❌ 固定 | ✅ 显式 Edge 路由 | ✅ GroupChatManager | **新增** |

---

> **文档修订（2026-10-28）**：测试体系与质量门禁更新 —— 单元测试 374 项、功能 e2e 113 项、压测 7 场景 8/8 全绿；测试代码统一英文命名 + 中文注释并纳入 CI 静态检查。本次改动不涉及本文档描述的架构与接口设计变更，完整数据见 `docs/test-report-20261028.md`。
