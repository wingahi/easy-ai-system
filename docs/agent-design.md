# WGH RAG System — Agent 能力升级设计方案

> 版本：v1.1 | 日期：2026-10-28 | 状态：Phase 1+2 已完成，Phase 3 评估更新
> 关联设计：[workflow-design.md](./workflow-design.md)

---

## 一、市面主流 Agent 实现方案调研

### 1.1 四种核心范式

| 范式 | 核心思想 | 流程 | 适用场景 |
|------|---------|------|---------|
| **ReAct** | 推理与行动交替 | Thought → Action → Observation → Thought → ... → Final Answer | 中短任务、工具链清晰 |
| **Plan-and-Execute** | 先拆解计划，再逐步执行 | 用户输入 → Planner 生成任务列表 → Executor 逐步执行 → 汇总 | 长任务、步骤可枚举 |
| **Reflection** | 执行后自我反思 + 改进 | 生成 → Critique → 反馈改进 → 重复直到阈值 | 生成质量敏感的任务 |
| **Multi-Agent** | 多角色协作（Manager-Worker / 对话群） | Manager 分解任务 → 分派 Worker → 协作/辩论 → Manager 汇总 | 复杂领域、专家协作 |

### 1.2 主流框架对比

| 维度 | LangGraph (LangChain) | CrewAI | AutoGen (Microsoft) | LangChain4j Agentic (Java) | 本项目契合度 |
|------|----------------------|--------|---------------------|---------------------------|-------------|
| 语言 | Python/JS | Python | Python | **Java** | ⭐⭐⭐⭐⭐ LangChain4j 1.19.0 已用 |
| 编排模型 | 有向图 + 状态机 | Manager-Worker 层级 | 对话代理群 | Planner 抽象 + AgenticScope | ⭐⭐⭐⭐⭐ |
| 核心范式 | ReAct + 条件分支 + 循环 | 角色分工 + 任务委托 | 多代理对话 | GOAP / Sequential / Parallel / Voting | ⭐⭐⭐⭐⭐ |
| 状态持久化 | Checkpointer（可落库） | 内存为主 | 内存为主 | AgenticScope（可扩展） | ⭐⭐⭐⭐ 需自建落库 |
| 工具集成 | LangChain Tool 生态 | LangChain Tool | 任意 Python callable | ToolSpecification（已用） | ⭐⭐⭐⭐⭐ 完全兼容 |
| RAG 集成 | 原生支持 | 需组合 | 需组合 | EmbeddingStore + Retriever（已用） | ⭐⭐⭐⭐⭐ 完全兼容 |
| 上下文压缩 | 需自建 | 需自建 | 需自建 | 需自建 | ⭐⭐⭐⭐⭐ 本项目已有分层压缩 |
| 评估/反思 | 需自建 | Critic 角色 | Critic 代理 | VotingPlanner（多 judge 并行） | ⭐⭐⭐⭐⭐ 本项目已有 judge 评估 |
| 学习曲线 | 中 | 低 | 中高 | **低**（注解驱动，Spring Boot 友好） | ⭐⭐⭐⭐⭐ |
| 生产稳定性 | 成熟 | 中 | 中 | **成熟**（企业级用例验证） | ⭐⭐⭐⭐⭐ |

### 1.3 选型结论

**选择 LangChain4j Agentic 模块作为底层编排引擎 + 自建 Agent 状态持久化层**。理由：

1. 项目已在 LangChain4j 1.19.0 栈上，`langchain4j-agentic` 官方支持 GOAP / Sequential / Parallel / Voting 模式，零栈切换成本
2. Agent 状态持久化（计划树、步骤执行历史）需要落库到 PostgreSQL，LangChain4j Agentic 的 AgenticScope 是内存态，需要自建持久化层
3. 现有 ToolExecutionEngine、分层压缩、RAG 评估都可以作为 Agent 的底层能力复用

**不选择**：
- LangGraph / CrewAI / AutoGen：Python 栈，引入多语言运维成本
- Semantic Kernel Java：同样成熟但生态不如 LangChain4j 紧密

---

## 二、现有系统能力盘点 & Gap 分析

### 2.1 已具备的 Agent 基础设施

| 能力 | 现有实现 | Agent 中角色 |
|------|---------|-------------|
| **工具调用** | ToolExecutionEngine + 4 类 ToolExecutor + 5 轮循环 | Agent 的 Action 执行层 |
| **RAG 检索** | pgvector + 7 种切分 + 实时召回率/命中率 | Agent 的 Observation 来源 |
| **分层上下文压缩** | 近期保留原文 + 中期 LLM 摘要 + 远期关键事实 | Agent 的 Memory 管理 |
| **LLM 模型管理** | DB 动态配置 + 多厂商兼容 + 缓存工厂 | Agent 的 Brain（ReAct 推理引擎） |
| **Judge 评估** | MRR/NDCG + 多 judge Faithfulness/Answer Relevancy + 一致性校验 | Agent 的 Reflection 反馈 |
| **SSE 流式** | start/retrieve/content/sources/accuracy/tool_call_start/tool_call_result/done | Agent 思维链可视化的传输层 |
| **会话管理** | chat_session + chat_message + 分层压缩持久化 | Agent 的短期 Memory |

### 2.2 Gap 分析（Agent 需要补齐的核心能力）

| Gap | 现状 | Agent 要求 | 优先级 |
|-----|------|-----------|--------|
| **任务规划** | 无，LLM 收到消息直接开始 RAG + Tool Calling | Plan-and-Execute：用户输入 → Planner 生成有向无环计划图 → Executor 按依赖顺序执行 | P0 |
| **多步执行循环** | 单次 5 轮 Tool Calling 循环 | 计划图驱动的多阶段执行：每阶段内部仍可 ReAct 循环 | P0 |
| **执行后反思** | LLM-as-Judge 离线评估 | 每阶段执行完 → Critic Agent 评估结果质量 → 不合格则重试或调整计划 | P1 |
| **计划自适应调整** | 无 | 执行中遇到失败/新信息 → Planner 动态更新剩余步骤 | P1 |
| **Agent 状态持久化** | chat_message 存消息，无计划/步骤存储 | 计划树、步骤状态、执行中间结果需落库，支持断点续跑 | P0 |
| **思维链可视化** | 前端只显示对话流 + tool_call_start/result SSE | 需新增 Agent 面板：计划树、步骤状态（等待/执行中/成功/失败）、每步 Thought/Observation | P0 |
| **多 Agent 协作** | 无 | 先做单 Agent + Reflection 闭环，Multi-Agent 放 P2 | P2 |
| **人工介入节点** | 无 | 计划中可标记 HUMAN_IN_THE_LOOP 节点，执行到此暂停等待用户确认 | P1 |

---

## 三、需求分析

### 3.1 核心用户故事

**US-1 复杂任务分解执行**
> 作为用户，我想输入「帮我分析一下最近的订单数据，找出异常交易，生成报告并保存到知识库」，系统应该自动拆解为多个步骤执行：获取订单数据 → 异常检测 → 生成报告 → 保存到知识库，每步的中间结果可查看。

**US-2 执行中动态调整**
> 作为用户，Agent 执行到「获取订单数据」失败时，应该自动降级用备用数据源重试，或调整后续计划（比如改成先查用户配置）。

**US-3 结果质量保证**
> 作为用户，Agent 每步执行完应该自动做质量检查，如果生成的报告 Faithfulness 太低，应该自动重试并告诉我重试了几次。

**US-4 长任务持久化**
> 作为用户，Agent 执行的长任务（比如 30 分钟的数据分析）应该能断点续跑，我刷新页面后还能看到进度。

**US-5 思维透明**
> 作为用户，我想看到 Agent 的计划树和每步的执行状态（成功/失败/重试中），而不是只看到最终结果。

### 3.2 功能需求清单

| 编号 | 模块 | 功能 | 优先级 |
|------|------|------|--------|
| F1 | **Agent 编排** | Plan-and-Execute 模式：LLM 生成有向无环计划图（JSON），每个步骤含 description / depends_on / assigned_to | P0 |
| F2 | **Agent 编排** | Go/No-Go 门控：计划步骤间可设条件判断（前置步骤结果满足条件才执行后续） | P1 |
| F3 | **Agent 编排** | 执行中动态 re-plan：失败/新信息触发 LLM 重新生成剩余计划 | P1 |
| F4 | **Agent 执行** | 步骤执行器：每个计划步骤内部仍是 ReAct 循环（LLM 推理 + Tool Calling + RAG 检索），但有独立的 step 上下文 | P0 |
| F5 | **Agent 执行** | 反思循环：步骤执行完 → Critic LLM 评估（Faithfulness / Correctness / Completeness） → 低于阈值则重试（最多 N 次） | P1 |
| F6 | **Agent 记忆** | 步骤间上下文传递：前置步骤的 Observation 自动注入后续步骤的 prompt 作为已知事实 | P0 |
| F7 | **Agent 记忆** | 长任务进度持久化：计划树 + 步骤状态（pending/running/success/failed/skipped）落库，支持进程重启后续跑 | P0 |
| F8 | **Agent UI** | 思维链可视化面板：左侧对话流，右侧计划树（展开/折叠）+ 步骤状态 + 每步 Thought/Observation 详情 | P0 |
| F9 | **Agent UI** | HITL（Human-In-The-Loop）：计划步骤标记 HITL 时执行到该步暂停，前端弹窗等待用户确认/修改输入 | P1 |
| F10 | **Agent UI** | Agent 执行控制：暂停 / 恢复 / 终止 / 重试单步 | P1 |
| F11 | **Agent 配置** | 聊天窗口新增「启用 Agent」开关 + 配置项（最大计划步骤数 / 单步最大工具轮次 / 反思重试次数 / 是否启用 HITL） | P0 |
| F12 | **Agent 配置** | Agent 执行器模型选择：可指定独立 Planner 模型（快模型做规划）和独立 Executor 模型（强模型做执行） | P0 |

### 3.3 非功能需求

| 编号 | 维度 | 要求 |
|------|------|------|
| NF1 | **性能** | 计划生成 < 3s（首次 LLM 调用）；单步执行 ≤ 现有单次 Tool Calling 耗时 |
| NF2 | **可靠性** | 进程崩溃后 Agent 任务状态从 DB 恢复，正在 running 的步骤设为 failed 并标记可重试 |
| NF3 | **可观测性** | Agent 全链路事件（plan_created / step_start / step_tool_call / step_reflect / step_success / plan_complete / plan_failed）走 SSE，每步耗时、token 消耗落库 |
| NF4 | **安全** | Agent 执行的工具集合仍按窗口级 toolIds 隔离，不能越权 |
| NF5 | **降级** | Agent 不可用时（LLM 超时 / Planner 返回非法 JSON）自动降级为现有单次 Tool Calling 模式，不阻塞用户 |
| NF6 | **兼容** | 现有非 Agent 窗口（evaluation_scheme=NONE）不受影响，保持原有行为 |

---

## 四、系统设计

### 4.1 模块归属

新建 **`rag-agent`** 模块（与 rag-chat / rag-tool 平级），职责单一：Agent 编排引擎。

```
rag-backend/
├── rag-agent/              # 新增：Agent 编排引擎
│   ├── AgentOrchestrator       # 核心编排器（Plan → Execute → Reflect 循环）
│   ├── Planner                 # 计划生成器（LangChain4j Agentic GOAP 实现）
│   ├── StepExecutor            # 单步执行器（复用 RagChatService 的 Tool Calling + RAG）
│   ├── ReflectionEngine        # 反思评估引擎（复用 LLM-as-Judge）
│   ├── AgentStateRepository    # Agent 状态持久化
│   ├── dto/                    # PlanDTO / StepDTO / AgentStateDTO
│   └── exception/              # AgentException
├── rag-chat/                # 现有：对话服务
│   └── RagChatService          # 调用 AgentOrchestrator（启用 Agent 时走新链路，否则走老链路）
├── rag-tool/                # 现有：工具执行（Agent Action 层）
├── rag-kb/                  # 现有：RAG 检索（Agent Observation 层）
├── rag-config/              # 现有：配置
│   └── AgentProperties         # 新增 Agent 配置项
└── rag-controller/          # 现有：API
    └── AgentController         # 新增 Agent 专用接口（plan/status/resume/cancel）
```

### 4.2 核心数据模型（新增 2 张表）

#### wgh_agent_plan（Agent 计划）

```sql
CREATE TABLE IF NOT EXISTS wgh_agent_plan (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL,              -- 关联的 chat_session
  chat_window_id BIGINT NOT NULL,
  llm_model_id BIGINT NOT NULL,            -- Planner 模型
  status VARCHAR(20) DEFAULT 'PLANNING',    -- PLANNING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED
  user_goal TEXT NOT NULL,                 -- 用户原始输入
  plan_json JSONB NOT NULL,                -- 生成的计划（含 step 列表、依赖关系）
  current_step_index INT DEFAULT 0,        -- 当前执行到第几步
  max_steps INT DEFAULT 15,                -- 计划最大步骤数
  total_tokens INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finish_time TIMESTAMP,
  created_by VARCHAR(50) DEFAULT 'system',
  updated_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_plan_session ON wgh_agent_plan(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_plan_status ON wgh_agent_plan(status);
```

#### wgh_agent_step（Agent 步骤）

```sql
CREATE TABLE IF NOT EXISTS wgh_agent_step (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL,
  step_index INT NOT NULL,                 -- 计划内序号
  step_id VARCHAR(32) NOT NULL,            -- 计划 JSON 里的唯一 step id
  title VARCHAR(200) NOT NULL,             -- 步骤标题（Planner 生成）
  description TEXT,                        -- 详细说明
  depends_on TEXT,                          -- 依赖的 step_id 列表（JSON 数组）
  status VARCHAR(20) DEFAULT 'PENDING',     -- PENDING / READY / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING
  thought TEXT,                            -- LLM 的推理过程（最近一次）
  action_tool VARCHAR(100),                -- 调用的工具名（如有）
  action_input TEXT,                        -- 工具输入
  observation TEXT,                        -- 工具返回 / RAG 检索 / LLM 输出
  reflection_score DOUBLE PRECISION,        -- 反思评估分数（0-10）
  reflection_feedback TEXT,                -- 反思反馈
  retry_count INT DEFAULT 0,               -- 重试次数
  max_retries INT DEFAULT 3,
  result_summary TEXT,                      -- 步骤产出摘要（传给后续步骤的已知事实）
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  token_count INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_step_plan ON wgh_agent_step(plan_id);
CREATE INDEX IF NOT EXISTS idx_agent_step_status ON wgh_agent_step(status);
```

### 4.3 计划 JSON Schema

Planner（LLM）输出结构化 JSON，经 `AgentOrchestrator` 解析后落库：

```json
{
  "planId": "plan_001",
  "steps": [
    {
      "stepId": "step_1",
      "title": "获取最近一周订单数据",
      "description": "从订单工具查询最近 7 天的订单列表",
      "dependsOn": [],
      "requiresTools": ["query_user_order"],
      "requiresRAG": false,
      "outputType": "DATA"
    },
    {
      "stepId": "step_2",
      "title": "检测异常交易",
      "description": "分析订单数据，标记异常交易（金额异常、频率异常、地点异常）",
      "dependsOn": ["step_1"],
      "requiresTools": [],
      "requiresRAG": true,
      "outputType": "ANALYSIS"
    },
    {
      "stepId": "step_3",
      "title": "生成异常交易分析报告",
      "description": "基于异常检测结果生成结构化分析报告",
      "dependsOn": ["step_2"],
      "requiresTools": [],
      "requiresRAG": true,
      "outputType": "REPORT"
    },
    {
      "stepId": "step_4",
      "title": "保存报告到知识库",
      "description": "将生成的报告作为新文档上传到知识库归档",
      "dependsOn": ["step_3"],
      "requiresTools": ["save_to_kb"],
      "requiresRAG": false,
      "hitl": true,
      "outputType": "ACTION"
    }
  ]
}
```

### 4.4 核心执行流程

```
用户输入
  │
  ▼
ChatController.stream()
  │
  ├─ window.agent_enabled == false → 走现有 RagChatService（不变）
  │
  └─ window.agent_enabled == true → AgentOrchestrator.orchestrate()
                                       │
                                       ▼
                                  ① Planner 生成计划
                                     LLM(GOAP prompt) → Plan JSON
                                     ├─ 解析/校验（有向无环 + 工具存在性检查）
                                     ├─ 落库 wgh_agent_plan + wgh_agent_step
                                     └─ SSE: plan_created { steps: [...] }
                                       │
                                       ▼
                                  ② 拓扑排序得到执行序列
                                     按 dependsOn 依赖计算就绪步骤
                                       │
                                       ▼
                                  ③ 逐步骤执行循环
                                     for step in ready_steps:
                                       ├─ SSE: step_start { stepIndex, title }
                                       ├─ StepExecutor.execute(step, context)
                                       │    ├─ 构建 Step Context（前置步骤 result_summary 注入）
                                       │    ├─ 内部 ReAct 循环（LLM 推理 + Tool Calling + RAG）
                                       │    │    ├─ SSE: step_thought / step_tool_call_start / step_tool_call_result
                                       │    │    └─ 产出 observation
                                       │    └─ 生成 result_summary（LLM 摘要，传给后续步骤）
                                       │
                                       ├─ ReflectionEngine.evaluate(step)
                                       │    ├─ Critic LLM 评估（Relevance / Correctness / Completeness）
                                       │    ├─ 低于阈值？→ retry（max_retries 次）
                                       │    ├─ SSE: step_reflection { score, feedback, retry }
                                       │    └─ 达标 → step.status = SUCCESS
                                       │
                                       ├─ HITL 节点？→ SSE: step_hitl_pending → 暂停等待确认
                                       │
                                       ├─ step 失败？
                                       │    ├─ ReflectionEngine 判定是否可降级
                                       │    └─ 触发 ② Planner 重新生成剩余计划
                                       │
                                       └─ SSE: step_success / step_failed
                                       │
                                       ▼
                                  ④ 计划完成
                                     汇总所有步骤 result_summary
                                     生成 Final Answer
                                     SSE: plan_complete { summary, totalSteps, successSteps, failedSteps }
```

### 4.5 StepExecutor 内部实现（复用现有能力）

```java
// rag-agent 模块内
public class StepExecutor {

    // 核心：把现有 RagChatService 的 Tool Calling 循环 + RAG 检索包进一个可复用的方法
    public StepResult execute(AgentStep step, StepContext context) {
        // 1. 构建 step-specific prompt
        String stepPrompt = buildStepPrompt(step, context);
        //    - System prompt = step.title + step.description + 执行指令
        //    - Context = 前置步骤的 result_summary 汇总
        //    - HITL 等待用户输入时注入 user_confirmation

        // 2. 调用 LLM + Tool Calling 循环（复用现有 streamChatWithTools，但把 RAG/压缩暂时旁路）
        //    内部递归处理 ToolExecutionRequest，发送 SSE 事件

        // 3. 如果 step.requiresRAG == true → 在循环前先执行 RAG 检索
        //    复用 RagKbSearchService

        // 4. 返回 StepResult
        return StepResult.builder()
            .observation(finalObservation)
            .toolCalls(toolCalls)
            .ragSources(ragSources)
            .thoughts(thoughts)    // 收集 ReAct 的推理过程用于 SSE 可视化
            .build();
    }
}
```

### 4.6 SSE 事件扩展

在现有事件基础上新增 Agent 专用事件：

| 事件名 | 触发时机 | payload |
|--------|---------|---------|
| `plan_created` | Planner 生成完计划 | `{ planId, steps: [{stepId, title, description, dependsOn, hitl}], maxSteps }` |
| `plan_updated` | 执行中动态重规划 | `{ planId, addedSteps: [...], removedStepIds: [...] }` |
| `plan_complete` | 所有步骤执行完 | `{ planId, summary, totalSteps, successSteps, failedSteps, totalTokens }` |
| `plan_failed` | 计划无法继续 | `{ planId, reason, failedStepId }` |
| `step_start` | 步骤开始执行 | `{ stepIndex, stepId, title }` |
| `step_thought` | LLM 产生推理 | `{ stepId, thought }` |
| `step_tool_call_start` | 步骤内调用工具 | 复用现有 tool_call_start + stepId |
| `step_tool_call_result` | 步骤内工具返回 | 复用现有 tool_call_result + stepId |
| `step_rag_result` | 步骤内 RAG 检索返回 | `{ stepId, sources: [{fileName, chunkIndex, score, content}] }` |
| `step_reflection` | 反思评估 | `{ stepId, score, feedback, retryCount, pass }` |
| `step_hitl_pending` | HITL 步骤等待用户 | `{ stepId, title, description, expectedInput }` |
| `step_hitl_confirm` | 用户确认 HITL 步骤 | `{ stepId, userInput, confirmed }` |
| `step_success` | 步骤执行成功 | `{ stepId, resultSummary, durationMs, tokenCount }` |
| `step_failed` | 步骤执行失败 | `{ stepId, error, retryCount }` |
| `step_skipped` | 因依赖步骤失败被跳过 | `{ stepId, reason }` |

### 4.7 Agent 状态机

```
PLANNING ──生成计划失败──▶ FAILED
    │
    ▼
RUNNING ──用户取消──▶ CANCELLED
    │
    ├──遇 HITL 步骤──▶ PAUSED (HITL_PENDING)
    │                    │
    │              用户确认──▶ RUNNING
    │              用户拒绝──▶ FAILED
    │
    ├──执行到最后一步成功──▶ COMPLETED
    │
    └──所有重试耗尽──▶ FAILED
```

### 4.8 降级策略

```
Agent 模式启动
    │
    ├─ Planner LLM 超时 / 返回非法 JSON
    │   └─ 降级 → 发送 SSE: plan_create_failed { reason } → 走现有单次 Tool Calling
    │
    ├─ StepExecutor 工具都不可用（ToolRegistry 为空）
    │   └─ 降级 → 跳过 Action 层，只做 RAG 检索 + 直接 LLM 回答
    │
    ├─ ReflectionEngine 不可用（Judge 模型都被禁用）
    │   └─ 降级 → 跳过反思循环，步骤执行完直接 SUCCESS
    │
    └─ 进程崩溃
        └─ 重启后 AgentStateRepository 加载状态，RUNNING 步骤标记为 FAILED，自动重试或等待用户操作
```

---

## 五、原型 UI 设计

### 5.1 整体布局

在现有 **对话 Playground** 右侧新增 **Agent 思维链面板**（可折叠），形成左右双栏布局：

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  WGH RAG System                                                            🔔 ⚙ 👤 admin ▾ │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  ┌──────────────────────────┐  ┌────────────────────────────────────────────────────────┐ │
│  │                          │  │  🤖 智能体执行                    [折叠] [停止]         │ │
│  │  💬 调试对话              │  │ ───────────────────────────────────────────────────────│ │
│  │  ─────────────────────── │  │  📋 执行计划 (4 步骤)                                  │ │
│  │  👤 帮我分析最近的订单    │  │                                                        │ │
│  │      数据，找出异常...    │  │  ✅ step_1  获取最近一周订单数据                       │ │
│  │                          │  │     📊 42 条记录 · 2026-10-28 14:23 · 3.2s · 1.2k token  │ │
│  │  🤖 好的，我来帮你...     │  │                                                        │ │
│  │      [来源: 知识库 A]    │  │  🔄 step_2  检测异常交易  ● 执行中...                  │ │
│  │      🔧 get_user_order   │  │     💭 Thought: 我需要先统计每笔订单的金额和频率...   │ │
│  │      ✓ 返回 42 条订单    │  │     🔧 get_user_order × 已调用                        │ │
│  │      [来源: 知识库 B]    │  │     🔍 RAG 检索: 3 chunks                              │ │
│  │                          │  │                                                        │ │
│  │  🤖 检测到 3 笔异常...    │  │  ⏳ step_3  生成异常交易分析报告                       │ │
│  │                          │  │                                                        │ │
│  │  ─────────────────────── │  │  ⏳ step_4  保存报告到知识库  👤 需您确认               │ │
│  │  [输入框]           发送  │  │                                                        │ │
│  │                          │  │ ───────────────────────────────────────────────────────│ │
│  │                          │  │  ⚡ 性能指标                                            │ │
│  │                          │  │  总耗时: 32.4s | Token: 18.2k | 模型: qwen-plus       │ │
│  └──────────────────────────┘  └────────────────────────────────────────────────────────┘ │
│                                                                                          │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 计划树组件（AgentPlanTree）

每个步骤显示为卡片，支持展开/折叠查看 Thought/Observation 详情：

**步骤状态图标：**

| 状态 | 图标 | 颜色 |
|------|------|------|
| PENDING（等待） | ⏳ | gray |
| RUNNING（执行中） | 🔄 | blue（带旋转动画） |
| SUCCESS（成功） | ✅ | green |
| FAILED（失败） | ❌ | red |
| HITL_PENDING（等待确认） | 👤 | orange（脉冲动画） |
| SKIPPED（跳过） | ⊘ | #999 |

**步骤卡片展开详情：**

```
┌─ ✅ step_1  获取最近一周订单数据 ──────────────────────────┐
│                                                            │
│  📊 执行结果                                               │
│  ─────────────────────────────────────────                │
│  调用 get_user_order 工具，返回 42 条订单记录。            │
│  关键字段: userId, orderId, amount, status, createTime    │
│                                                            │
│  💭 Thought                                                │
│  ─────────────────────────────────────────                │
│  用户要分析最近的订单数据，我需要先获取数据。              │
│  可用的工具是 get_user_order，参数 userId 必填。           │
│  我先默认用当前登录用户，获取最近 7 天的记录。            │
│                                                            │
│  🔧 工具调用 (2)                                          │
│  ─────────────────────────────────────────                │
│  ┌─ get_user_order ─────────────────────────────────┐     │
│  │ 输入: { userId: "U1001", status: "all" }          │     │
│  │ 耗时: 0.8s                                        │     │
│  │ 状态: ✅ 成功                                     │     │
│  │ 返回: 42 条订单                                   │     │
│  └───────────────────────────────────────────────────┘     │
│  ┌─ get_user_order ─────────────────────────────────┐     │
│  │ 输入: { userId: "U1001", status: "pending" }      │     │
│  │ 耗时: 0.5s                                        │     │
│  │ 状态: ✅ 成功                                     │     │
│  │ 返回: 3 条待处理订单                              │     │
│  └───────────────────────────────────────────────────┘     │
│                                                            │
│  🔍 RAG 检索 (1)                                          │
│  ─────────────────────────────────────────                │
│  ┌─ 知识库: 订单管理规范 ──────────────────────────┐      │
│  │ 分 1 chunk · score 0.82 · 320 token             │      │
│  │ 订单金额超过单笔 ...（截取摘要）                  │      │
│  └─────────────────────────────────────────────────┘      │
│                                                            │
│  🎯 反思评估                                               │
│  ─────────────────────────────────────────                │
│  评分: 9.2 / 10 · 第 1 次就达标                            │
│  · 覆盖完整性: 9 · 数据准确性: 9.5 · 上下文相关性: 9       │
│                                                            │
│  📝 产出摘要                                               │
│  ─────────────────────────────────────────                │
│  成功获取用户 U1001 最近 7 天的 42 条订单记录，           │
│  总金额 ¥128,450，包含 3 笔待处理订单。                    │
│                                                            │
│  ⏱ 2026-10-28 14:23:15 · 3.2s · 1,240 token               │
└────────────────────────────────────────────────────────────┘
```

### 5.3 HITL 交互弹窗

当步骤标记为 HITL 时，执行到该步暂停并弹窗：

```
┌─ 保存报告到知识库 ─────────────────────────────┐
│                                                 │
│  Agent 即将把「异常交易分析报告」保存到知识库：  │
│                                                 │
│  📋 报告预览                                     │
│  ──────────────────────────────────────────────  │
│  【异常交易分析报告】                             │
│  分析周期: 2026-10-21 ~ 2026-10-28              │
│  检测到 3 笔异常:                                │
│  · O12345 ¥58,000  单笔金额超限                  │
│  · O12346 ¥120    高频小额（30 分钟内 15 笔）     │
│  · O12347 ¥8,500  异地消费                        │
│  ──────────────────────────────────────────────  │
│                                                 │
│  📂 选择目标知识库:                              │
│  [下拉: 金融风控知识库 / 客户交易知识库]         │
│                                                 │
│  📝 文档标题:                                    │
│  [异常交易分析报告_2026-10-28]                    │
│                                                 │
│  ⚠️ 保存后会触发向量化处理，约需 10-30 秒        │
│                                                 │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐          │
│  │  ✅ 确认 │  │  ✏️ 修改 │  │  ❌ 取消 │          │
│  └─────────┘  └─────────┘  └─────────┘          │
└─────────────────────────────────────────────────┘
```

### 5.4 聊天窗口管理新增 Agent 配置

在「编辑聊天窗口」弹窗底部新增 Agent 配置区：

```
┌─ 聊天窗口编辑 ──────────────────────────────────────────┐
│                                                          │
│  基本信息                                                │
│  ────────────────────────────────────────────────────── │
│  窗口名称: [金融数据分析助手]                            │
│  知识库:   [订单管理规范] [交易风控知识库]                │
│  模型:     [通义 qwen-plus]                              │
│  评估方案: [METRICS / LLM_JUDGE / BOTH / NONE ▾]        │
│  工具:     [get_user_order] [保存到知识库] [echo]        │
│                                                          │
│  🤖 Agent 配置                                           │
│  ────────────────────────────────────────────────────── │
│                                                          │
│  ☑ 启用 Agent 自动规划执行                               │
│                                                          │
│  最大计划步骤数:  [15] ▾  (范围 3-20)                    │
│  单步最大工具轮次: [5]  ▾  (范围 1-10)                   │
│  反思最大重试次数: [3]  ▾  (范围 0-5，0=关闭反思)        │
│  反思评分阈值:     [7.0]/10                              │
│                                                          │
│  Planner 模型:  [独立选择 ▾] [自动继承]                  │
│  Executor 模型: [独立选择 ▾] [自动继承]                  │
│  Critic 模型:   [独立选择 ▾] [自动选 2 个不同 provider]  │
│                                                          │
│  ☑ 执行中允许动态重规划                                  │
│  ☑ 启用 Human-In-The-Loop（计划可标记需人工确认节点）    │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 5.5 新增 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/sessions/{sessionId}/plan` | 手动触发重新规划（当前计划失败/暂停后） |
| GET | `/api/agent/sessions/{sessionId}/state` | 查询当前 Agent 计划 + 步骤状态（刷新页面后恢复面板） |
| POST | `/api/agent/steps/{stepId}/hitl-confirm` | HITL 步骤用户确认/修改输入后提交 |
| POST | `/api/agent/plans/{planId}/cancel` | 取消整个计划 |
| POST | `/api/agent/steps/{stepId}/retry` | 重试单个失败步骤 |
| POST | `/api/agent/steps/{stepId}/skip` | 跳过单个失败步骤（标记 skipped，触发依赖重评估） |

---

## 六、分阶段落地计划

### Phase 1 — 单 Agent Plan-Execute 闭环（P0）

**目标**：用户输入 → Planner 生成计划 → StepExecutor 逐步骤 ReAct 执行 → 计划完成。不含反思、不含 HITL。

| 周 | 任务 | 产出 |
|----|------|------|
| 1 | rag-agent 模块骨架 + AgentProperties 配置 + Agent 数据库迁移 | 模块创建 + 表 + 配置类 |
| 2 | Planner（GOAP prompt + LangChain4j 结构化输出）+ AgentStateRepository | 计划生成 + 状态持久化 |
| 3 | StepExecutor（复用现有 Tool Calling + RAG，包进 step 上下文） | 单步执行器 |
| 4 | AgentOrchestrator 核心循环 + 拓扑排序 + 步骤间 context 传递 | 编排器主循环 |
| 5 | SSE 事件扩展 + 前端 AgentPlanTree 组件 + 窗口配置 UI | 前后端联调 |
| 6 | 集成测试 + 边界 case（计划失败降级、工具不可用降级、进程崩溃恢复） | 测试通过 |

### Phase 2 — 反思 + HITL + 动态重规划（P1）

| 周 | 任务 |
|----|------|
| 1 | ReflectionEngine（复用现有 LLM-as-Judge，针对单步骤做 Faithfulness/Correctness） |
| 2 | 反思重试循环 + 前端 step_reflection 事件渲染 |
| 3 | HITL 支持：Planner 标记 hitl=true 的步骤 → 前端弹窗 → 后端 hitl-confirm API |
| 4 | 动态 re-plan：步骤失败触发 Planner 重新生成剩余步骤 |
| 5 | Agent 执行控制：暂停/恢复/终止/重试单步 API |

### Phase 3 — 真多 Agent 协作（架构评估完成，按需开发）

#### 当前系统的 Agent 本质

**代码级结论**：当前 `AgentOrchestrator` 是**单 Agent 编排器**——Planner / StepExecutor / ReflectionEngine 是同一大 Agent 的内部组件，用不同 prompt 构造做不同阶段任务，**没有多个独立 Agent 对象、没有消息总线、没有 GroupChat 对话路由**。

| 多 Agent 必备要素 | 当前是否具备 |
|-----------------|-------------|
| 多个独立 Agent 对象/类 | ❌ 仅 `AgentOrchestrator` |
| Agent 间消息总线（send/broadcast/group chat） | ❌ 无 `sendMessage`/`GroupChat` |
| Agent 各自独立 system prompt + 记忆 | ❌ 所有状态落库 `AgentPlan`/`AgentStep`，无独立对话历史 |
| Agent 自主决策是否继续/终止 | ❌ 终止条件由 Orchestrator 硬编码 |
| 工作流引擎 fan-out/fan-in | ⚠️ 是**确定性节点并行**，不是 Agent 对话 |

#### 2026 主流多 Agent 框架心智模型

| 框架 | 心智模型 | 协作机制 | 2026 状态 |
|------|---------|---------|----------|
| **LangGraph** | 有向状态图 | 显式 Edge 路由 + Send() fan-out/fan-in | 生产标准，90M+/月下载 |
| **CrewAI** | 角色 Crew | Role/Goal/Backstory + Sequential/Hierarchical 调度 | 增长最快，47.8k stars |
| **AutoGen → Microsoft Agent Framework** | 对话消息传递 | GroupChatManager 动态选下一个发言者 | AutoGen 已维护模式，MAF 1.0 GA 2026.4 |
| **MAF (AutoGen 继任)** | Graph-based workflow | LangGraph 路线 + checkpointing + MCP/A2A 原生 | 已偏离 AutoGen 对话模型转向 LangGraph |

核心洞察：**2026 年生产级多 Agent 框架正在从"对话驱动"向"状态图驱动"收敛**——LangGraph 和 Microsoft Agent Framework 都采用显式状态流转 + 条件路由。

#### 是否有必要开发真多 Agent？

| 场景 | 是否需要真多 Agent | 当前系统能否覆盖 |
|------|-------------------|-----------------|
| 多专家辩论（法务 Agent 说合规、合规 Agent 说风险、CEO Agent 说 ROI） | ✅ 需要 | ❌ 无法覆盖（开放讨论 + 角色独立 + 动态路由） |
| 代码-测试-修复循环（Coder → Tester → Coder 直到通过） | ✅ 需要 | ❌ 无法覆盖（Agent 自主终止） |
| 研究分工（Researcher → Analyst → Writer → Reviewer） | ✅ 需要 | ❌ 无法覆盖（角色独立记忆） |
| 结构化业务流程（审批流、合同审查、发票处理） | ❌ 不需要 | ✅ 工作流 DAG 已覆盖 |
| RAG 问答增强（检索 → LLM → 反思评分） | ❌ 不需要 | ✅ Agent Plan-and-Execute 已覆盖 |
| 并行研究多源（N 个 Researcher 并行搜不同数据源） | ⚠️ 部分 | ⚠️ 工作流 fan-out 能做，但 Agent 不是独立的 |

**建议**：**先不做**，除非出现以下业务信号：
1. 真实用户明确需要"多专家辩论""代码-测试-修复循环""研究分工"这类对话式协作场景
2. 开发投入可控——核心工作量约 3-5 周（AgentInstance 抽象 + MessageBus + GroupChat 路由 + 前端面板）

#### 如确需开发：架构演进建议

**不引入 CrewAI/AutoGen/LangGraph 的 Java port**（生态薄、维护成本高、与 Spring Boot 冲突），在现有 `rag-agent` 模块增量扩展：

```
AgentOrchestrator（已有，单 Agent 编排）
  │
  ├── [现有链路] Planner → StepExecutor → ...
  │
  └── [新增] MultiAgentRouter
       │
       ├── AgentInstance（新增：每个 Agent 对象）
       │    ├── systemPrompt（独立）
       │    ├── 独立工具白名单
       │    ├── 独立对话历史（记忆）
       │    └── 独立模型路由
       │
       ├── MessageBus（新增）
       │    ├── send(to, message)
       │    ├── broadcast(message)
       │    └── subscribe(agentId, handler)
       │
       └── GroupChatManager（新增：对话路由）
            ├── 下一个发言者决策
            ├── 终止条件判断
            └── HITL 对话链暂停
```

**增量实施路径**：

| 阶段 | 能力 | 预估 |
|------|------|------|
| P0 核心抽象 | AgentInstance + MessageBus 原语，让当前单 Agent 也跑在新接口上 | 3-5 天 |
| P1 角色分层模式 | CrewAI 风格：声明 role/goal，按顺序/层级调度 | 3-5 天 |
| P2 对话式 GroupChat | AutoGen 风格：GroupChatManager 动态选下一个发言者、终止条件 | 5-7 天 |
| P3 前端可视化 | 多 Agent 对话面板：气泡流、Agent 身份标识、消息路由动画 | 5-7 天 |

**不做的事**：
- 不要一开始就做完整 GroupChat——先做 P1 角色分层模式，覆盖 80% 多 Agent 场景
- 不要把工作流引擎重写成 Agent 对话——DAG 确定性并行和 Agent 对话式协作是互补关系

#### 与工作流引擎的边界

| 维度 | 当前 Agent | 工作流引擎 | 真多 Agent（如开发） |
|------|-----------|-----------|---------------------|
| 链路来源 | LLM 运行时动态生成计划 | 人工预先编排的固定 DAG | 多 Agent 对话运行时协商 |
| 稳定性 | 每次结果可能不同 | 固定可复现、可审计 | 可能不同但角色约束下较稳定 |
| 适用 | 开放探索型任务 | 固定 SOP、需审核、需复用 | 专家协作、研究分工、代码迭代 |
| 状态持久化 | AgentPlan/AgentStep | WorkflowRun/NodeRun/Version | 每个 Agent 独立对话历史 + 共享上下文 |

---

> **文档修订（2026-10-28）**：测试体系与质量门禁更新 —— 单元测试 374 项、功能 e2e 113 项、压测 7 场景 8/8 全绿；测试代码统一英文命名 + 中文注释并纳入 CI 静态检查。本次改动不涉及本文档描述的架构与接口设计变更，完整数据见 `docs/test-report-20261028.md`。
