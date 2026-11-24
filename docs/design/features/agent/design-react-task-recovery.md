# ReAct / Agent 任务在服务不可用时的持久化与恢复设计

> **版本**：v1.0
> **日期**：2026-11-18
> **适用版本**：当前 `main` 工作区（Spring Boot 3.4.5 / PostgreSQL 15+ / Redis / 多模块 Maven）
> **分析范围**：单 Agent（ReAct Plan-and-Execute）、多 Agent 协作、可视化工作流引擎
> **结论来源**：仓库源码 + 本地开发库 `wgh_rag_dev` 只读快照（2026-11-18）
> **不在范围**：不修改业务代码、不执行 DDL、不修改数据库、不重启服务

---

## 0. 结论摘要

1. **任务状态是持久化的，但“谁在执行”和“是否还活着”没有持久化**。三套运行表（`wgh_agent_plan/step`、`wgh_workflow_run/node_run`、`wgh_m_agent_run/step`）都只有业务状态，没有执行者 ID、租约、心跳、版本号或失败原因字段。进程消失后，数据库无法区分“正在执行”与“早已死亡”。
2. **单 Agent 的 ReAct 循环跑在聊天请求派生出的裸线程里**，不是可管理线程池；多 Agent 每个 run 用 `new Thread`；工作流用独立 `ThreadPoolTaskExecutor`。三者都依赖进程内内存态（`frontier`、`pendingIn`、`blackboard`、`RunContext`），重启即丢。
3. **`update_time` 不能当心跳**。`MetaObjectHandlerImpl` 使用 `strictUpdateFill`，只在字段为 null 时填充；实体从 DB 读出后 `updateTime` 非空，`updateById` 会把旧值写回。实测 `wgh_agent_step` 中存在 `start_time` 晚于 `update_time` 的行，证明长时间执行的步骤其 `update_time` 不会推进。
4. **没有任何超时、清理、对账或启动修复任务**。全仓库无 `@Scheduled`，仅有分片上传清理与工作流 CRON 触发器两个独立调度器，均不处理运行表。
5. **现有恢复入口只覆盖 HITL**：`resume` 要求 `plan.status=PAUSED` 且存在 `HITL_PENDING` 步骤；RUNNING 状态没有任何恢复接口。工作流支持 HITL 断点续跑和单步调试；多 Agent 的 Crew `resume` 实际是“整体重跑”。
6. **取消不是协作式中断**：`cancel()` 只改数据库，执行线程读取的是启动时载入的内存 `plan` 对象，仍会继续执行并落库后续步骤。多 Agent 同理。
7. **多实例没有互斥**：无分布式锁、无执行者标识、无选主；同一 plan 的 `hitl-confirm`/`retry` 并发调用会导致重复执行。`retryStep` 也没有校验步骤必须是 FAILED/SKIPPED（注释与实现不一致）。
8. **本地库已存在脏数据**：1 个 Agent 计划 `RUNNING` 停留 15 天 23 小时；4 个工作流运行 `RUNNING` 分别停留 20 天 21 小时（3 条）与 1 天 19 小时；1 个多 Agent 运行停在 `PLANNING` 20 天 16 小时。详见 §1.9。
9. **推荐方案**：数据库做唯一事实源，采用「租约 + 心跳 + 过期扫描回收 + 检查点续跑 + 幂等步骤 + 协作式取消 + 优雅停机」；不引入 Temporal 类持久化执行引擎。按 L0（止血：租约/回收/失败可见）→ L1（自动续跑）→ L2（工具副作用幂等与人工介入）→ L3（事件重放/outbox）分层落地。

---

## 1. 现状核查

### 1.1 持久化结构与状态取值

#### 单 Agent

表定义：`rag-backend/sql/schema/01-schema.sql#L530-L616`

- `wgh_agent_plan`：`status`、`current_step_index`、`max_steps`、`finish_time`、`create_time`、`update_time`。
- `wgh_agent_step`：`status`、`start_time`、`end_time`、`retry_count`、`max_retries`、`result_summary`、`thought`、`action_tool`、`observation`。
- **没有**：`executor_id`、`lease_expire_time`、`heartbeat_time`、`version`、`error_code`、`attempt`。`wgh_agent_step` 甚至没有 `error_message` 列，失败原因只能落在 `observation` 或日志里。
- 索引只有 `(session_id)`、`(status)`、`(plan_id)`、`(status)`，没有面向“活跃状态 + 时间”的扫描索引。

状态常量：`rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/constant/AgentConstants.java#L8-L16`

```
PENDING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED / SKIPPED / SUCCESS / HITL_PENDING
```

- Plan 实际使用：`RUNNING`（创建即 RUNNING，不是 PLANNING）、`PAUSED`、`COMPLETED`、`FAILED`、`CANCELLED`。`createPlanTx` 直接写 `RUNNING`（`PlanLifecycleService.java#L98-L114`），所以 Schema 默认值 `PLANNING` 实际不会被写入。
- Step 实际使用：`PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`SKIPPED`、`HITL_PENDING`。

实体：`AgentPlan.java#L10-L35`、`AgentStep.java#L9-L38`。字段与表一一对应，无执行者/租约字段。

#### 多 Agent

- 实体：`rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/entity/MultiAgentRun.java#L19-L82`、`MultiAgentStep.java#L15-L78`
- 表：`rag-backend/sql/schema/01-schema.sql#L814-L889`
- 状态：`rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/constant/MultiAgentConstants.java#L8-L15`（`PENDING/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED/SKIPPED/HITL_PENDING`）
- `wgh_m_agent_run` 有 `start_time`、`finish_time`、`duration_ms`、`current_round`，**没有**租约/心跳/执行者。
- 关键事实：`createRun` 写入 `status=PLANNING`（`MultiAgentService.java#L41-L77`），只有在调用 `GET /runs/{runId}/stream` 时 `execute()` 才把它改成 `RUNNING`（`MultiAgentRuntime.java#L74-L77`）。因此“创建了 run 但没打开流/流打开前进程退出”会永久停在 `PLANNING`。
- 步骤只在 Agent 执行**成功后**才插入，且固定 `status=SUCCESS`（`CrewCoordinator.java#L422-L443`；GraphCoordinator 亦然）。没有 RUNNING 步骤记录，也没有步骤开始时间落库（`start_time` 是事后用 `now - duration` 推算的，见 `CrewCoordinator.java#L437-L439`）。

#### 工作流

- 实体：`WorkflowRun.java#L14-L69`、`WorkflowNodeRun.java#L14-L60`
- 状态：`WorkflowConstants.java#L22-L32`（额外有 `DEBUG_PAUSED`）
- `WorkflowRun` 有 `context_json`（变量池、节点输出、HITL 待恢复节点）、`graph_snapshot`、`executed_nodes/total_nodes`、`start_time/finish_time/duration_ms`，**没有**租约/心跳/执行者。`context_json` 是天然的“节点级检查点”。
- `RunContext` 持久化字段见 `rag-workflow/src/main/java/com/wgh/rag/workflow/dto/RunContext.java#L14-L48`：`vars`、`nodeOutputs`、`loopIterations`、`pendingNodeIds`、`safeMode`、调试游标。**没有普通运行的 `frontier` / `pendingIn`**。

### 1.2 写入时机（是否每步开始/结束都落库）

单 Agent（`PlanLifecycleService.java`）：

| 时机 | 写入 | 位置 |
|---|---|---|
| 计划生成后 | 插入 plan（RUNNING）+ 逐条插入 steps（PENDING） | `createPlanTx#L98-L114`、`persistSteps#L117-L131` |
| 每步开始 | step → RUNNING + `start_time`，同时更新 `plan.current_step_index` | `markStepRunningTx#L161-L169` |
| Reflection 重试 | 只更新 `retry_count` | `updateRetryCountTx#L183-L188` |
| Reflection 通过 | 写 `reflection_score/feedback` | `saveReflectionScoreTx#L191-L197` |
| 每步结束 | 写 thought/observation/result_summary/end_time/token，step → SUCCESS/FAILED/HITL_PENDING | `persistStepResultTx#L200-L223` |
| 计划完成/失败/取消 | 更新 plan 终态 + `finish_time` | `markPlanCompleteTx#L233-L235`、`failPlanTx#L238-L244`、`cancelPlanAndSkipUnfinishedSteps#L247-L255` |

结论：**步骤开始和结束都会落库**，粒度是“步骤级”，不是“工具调用级”。进程在 `StepExecutor.execute` 内部（LLM 调用、工具调用、Reflection）崩溃时，DB 只会留下 step=RUNNING 和 `start_time`，没有任何中间进度。

工作流：

| 时机 | 写入 | 位置 |
|---|---|---|
| 运行创建 | `wgh_workflow_run` RUNNING | `WorkflowService.createRun#L394-L411`、`runHeadless#L423-L432` |
| 节点开始 | 插入 `workflow_node_run` RUNNING + `start_time` | `WorkflowEngine.insertNodeRun#L755-L772` |
| 节点结束 | 节点 SUCCESS/FAILED + 输出/end_time/duration | `WorkflowEngine#L618-L627`、`#L589-L596` |
| 每个节点成功后 | `context_json` + run 状态 RUNNING 落库 | `persistContext#L793-L798` |
| 运行结束 | run 终态 + finish_time | `finishRun#L960-L976` |

结论：工作流有**节点级检查点**，但调度游标（frontier/pendingIn）在内存里，重启后无法从“节点 N 之后”继续（HITL 除外）。

多 Agent：只有 run 状态与“成功步骤”落库，**没有步骤开始落库**，也没有上下文快照。Crew sequential 每完成一个 agent 插一条成功记录（`CrewCoordinator.java#L132`、`#L181`、`#L205`、`#L224`）；并行模式在 `executeParallelNode` 成功后插入（`#L316-L352`）。Graph / GroupChat 类似。`blackboard` 是进程内实现（`rag-agent-multi/.../blackboard/InMemoryBlackboard.java`），重启丢失。

### 1.3 `update_time` 不是心跳（重要发现）

`rag-backend/rag-data/src/main/java/com/wgh/rag/data/mybatis/MetaObjectHandlerImpl.java#L31-L35`：

```java
public void updateFill(MetaObject metaObject) {
    this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    ...
}
```

`strictUpdateFill` 仅在字段值为 `null` 时填充。执行链路中 `AgentPlan`/`AgentStep` 都是从 DB `select` 出来的，`updateTime` 已非空，后续 `updateById` 不会刷新它，反而把旧值写回。

实测证据（本地库，2026-11-18）：

```
plan_id=64, step_index=1, step_id=step_1, status=RUNNING
  start_time  = 2026-11-02 10:06:32.981
  update_time = 2026-11-02 10:06:29.122   <-- 早于 start_time
```

**因此不能把 `update_time` 直接当作心跳字段**。新增心跳必须是独立列，并用显式 SQL（或 `MetaObjectHandler` 之外的更新语句）写入；同理，回收任务的自定义 SQL 必须显式 `SET update_time = now()`。

### 1.4 执行链路与内存态依赖

#### 单 Agent

入口：`ChatController.streamChat` → `RagChatService.streamChat`（`rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L130-L132`），SSE 超时 30 分钟（`ChatConstants.java#L37`、`RagChatService.java#L165`）。

真正执行：

- `RagChatService.java#L197-L198`：每个聊天请求 `new Thread(MdcContext.wrap(...))`，**不是受管线程池**，无法排水、无法统计、无法中断。
- `RagChatService.java#L418-L469`：`agentEnabled` 时调用 `agentOrchestrator.orchestrate(...)`，同步执行完整计划。
- `AgentOrchestrator.executeSteps#L276-L488`：`for` 循环逐步执行；每步 `markStepRunningTx` → `StepExecutor.execute`（同步 ReAct tool-calling 循环）→ Reflection → `persistStepResultTx`。
- `StepExecutor.java#L79-L282`：模型调用与工具调用全在同一个线程里同步完成；`BlockingModelFailover` 只在“首个模型响应前”切换，避免工具副作用重复（`StepExecutor.java#L40-L42`、`#L128-L135`）。

进程内状态：

- `stateEntries`（已完成步骤摘要）、`aggregatedSources`、`retries`、`stepPrompt` 都在 `executeSteps` 的栈上。
- 工具执行预算 `ToolTaskBudget` 是内存对象（`StepExecutor.java#L208`）。
- HITL 通过“线程返回 + 数据库 PAUSED”协作（`HitlGate.java#L42-L54`），这是唯一的跨进程恢复点。

**重启后果**：无论进程是优雅退出还是 `kill -9`，DB 留下 `plan=RUNNING`、当前 `step=RUNNING`、其余 `PENDING`；没有任何线程会再推进它们。SSE 连接断开，前端只能轮询到一个永远不变的状态。

#### 多 Agent

- `MultiAgentController.streamRun#L83-L102`：每个 run 一个 `new Thread`；SSE 超时 10 分钟。
- `MultiAgentRuntime.execute#L59-L119`：内存 `MultiAgentContext` + `InMemoryBlackboard`；Coordinator 内 `frontier/pendingIn/submitted` 均为局部内存对象。
- `CrewCoordinator.runParallel#L273-L313`：每次运行新建 `ThreadPoolExecutor`，`dispatcher.join(10min)` 后 `shutdownNow()`。
- HITL 会持久化 `HITL_PENDING` 步骤（`GraphCoordinator.java#L147-L169`、`GroupChatCoordinator.java#L64-L86`），但 Crew 的 `resume` 是**整体重跑**（`CrewCoordinator.java#L83-L88`），没有“跳过已完成”的实现（注释自称“实际可根据 step 状态跳过”，但未实现）。

**重启后果**：run 停在 RUNNING；已成功步骤有记录但没有足够的“执行到哪”的游标；`blackboard`、当前轮次上下文丢失；Crew 即使人工 resume 也会重复调用所有 agent。

#### 工作流

- `WorkflowService.run#L321-L354`、`runForChat#L363-L391`、`runHeadless#L414-L446`：提交到 `workflowExecutor`（`WorkflowExecutorConfig.java#L15-L28`，core=2/max=8/queue=100，`waitForTasksToCompleteOnShutdown=true`，`awaitTerminationSeconds=30`）。
- `WorkflowEngine.executeLoop#L434-L518`：调度线程 + 4 个 worker 的 `ThreadPoolExecutor`；`frontier`、`pendingIn`、`submitted` 都是内存对象。
- **额外风险**：`dispatcher.join(5 * 60 * 1000L)` 超时后，`finally` 无条件 `executor.shutdownNow()`（`WorkflowEngine.java#L508-L517`）。也就是说，即使进程健康，只要单个节点执行超过 5 分钟，worker 会被中断，而 run 不会被置为 FAILED，直接永久停在 RUNNING。
- 工作流的检查点 `context_json` 在每节点成功后写入（`#L793-L798`），但恢复入口 `resume()` 只接受 HITL 的 `pendingNodeIds`（`#L85-L97`），不能从普通检查点重建调度游标。

**重启后果**：run 停在 RUNNING；`context_json` 保存了已成功节点输出，但缺少 frontier/pendingIn，无法安全续跑；`workflow_node_run` 中可能有 RUNNING 节点（若崩溃在节点内）。

### 1.5 现有恢复入口与覆盖范围

| 入口 | 触发条件 | 覆盖范围 | 位置 | 关键限制 |
|---|---|---|---|---|
| HITL 确认（单 Agent） | `plan=PAUSED` 且存在 `HITL_PENDING` step | 只从 HITL 暂停点继续剩余 PENDING 步骤 | `AgentController#L96-L133`、`AgentOrchestrator.resume#L145-L190` | 非 PAUSED 直接拒绝（`#L151`）；REST 同步执行，长计划可能超过 HTTP 超时；无租约，可被并发调用 |
| 单步重试（单 Agent） | 注释称 FAILED/SKIPPED，**实际无状态校验** | 重置单个 step 并执行 | `AgentController#L148-L170`、`AgentOrchestrator.retryStep#L212-L246` | 可对 RUNNING step 调用 → 双重执行；执行完不会继续后续步骤 |
| 跳过步骤（单 Agent） | 无校验 | 把 step 置 SKIPPED | `AgentOrchestrator.skipStep#L249-L256` | 注释说会继续后续，实际没有；plan 状态不变 |
| 取消（单 Agent） | 任意非终态 | DB 置 CANCELLED + 未完成 step → SKIPPED | `AgentOrchestrator.cancel#L195-L200`、`PlanLifecycleService#L247-L255` | 执行线程用内存 `plan` 判断取消（`AgentOrchestrator#L292`），**不会停止**，后续仍会执行并落库 |
| 工作流 HITL 恢复 | `run=PAUSED` + `HITL_PENDING` 节点 | 从 `pendingNodeIds` 继续 | `WorkflowService.prepareHitlResume#L471-L509`、`WorkflowEngine.resume#L85-L97` | 仅 HITL；无超时/过期 |
| 工作流单步调试 | `run=DEBUG_PAUSED` | 按调试游标推进 | `WorkflowService.debugStep#L542-L592`、`WorkflowEngine.debugStep#L110-L333` | 仅调试会话，不是崩溃恢复 |
| 多 Agent HITL 恢复 | `run=PAUSED` | Graph/GroupChat 可从 HITL 步骤继续；Crew 整体重跑 | `MultiAgentRuntime.resumeFromHitl#L122-L171` | 只支持 PAUSED；无检查点跳过 |
| SSE 断线重连/事件重放 | 无 | **不存在** | 全仓库无 `Last-Event-ID`/事件表；`WorkflowService.emitters` 是进程内 Map（`#L61-L62`、`#L693-L707`） | 断线后只能靠前端轮询状态接口 |
| 前端状态恢复 | 页面刷新 | 轮询 `GET /api/agent/plans/{id}/state` 或 `GET /api/agent/sessions/{id}/plan` | `AgentController#L52-L93`、`#L188-L232`；`ChatPlayground.vue#L1065-L1118` | 仅展示；轮询无上限、无“失联”判定 |

**结论**：现有能力只能覆盖“HITL 主动暂停”和“人工重试单个步骤”，完全没有覆盖“执行进程消失”。而且取消/重试本身还引入了重复执行风险。

### 1.6 超时、清理、对账任务

- 全仓库无 `@Scheduled`（检索结果为空）。
- 仅有：
  - `UploadSessionCleaner`：清理 `UPLOADING` 的分片上传会话（`rag-kb/.../UploadSessionCleaner.java#L40-L70`）。
  - `ConnectorScheduler`：知识库连接器同步调度。
  - `WorkflowTriggerService`：CRON / Webhook 触发工作流（`WorkflowTriggerService.java#L51-L68`）。
- **没有任何任务扫描 `wgh_agent_plan`、`wgh_agent_step`、`wgh_workflow_run`、`wgh_workflow_node_run`、`wgh_m_agent_run`、`wgh_m_agent_step`。**
- 没有启动时状态修复（无 `ApplicationReadyEvent` / `SmartLifecycle` 相关执行对账）。

### 1.7 多实例部署

- 部署编排为单实例：`docker-compose.yml#L9-L70` 中 `backend` 只有 1 个服务，无 `deploy.replicas`。
- 代码中**没有**分布式锁/选主/执行者标识：
  - 全仓库检索 `setIfAbsent / SETNX / RedisLock / Redisson / advisory lock / leader`，只命中公开访问限流与无关文本，没有执行互斥实现。
  - Redis 只用于限流、分享密码锁定、Widget 授权（`application.yml#L135-L143`），不可用时降级为内存。
- 结论：一旦横向扩容，同一 plan 可能被两个实例的 HTTP 请求同时推进（HITL 确认、重试），或未来的扫描任务被多实例重复执行。当前“单实例”只是部署约束，不是代码保证。

### 1.8 优雅停机

- 没有 `server.shutdown: graceful`，没有 `spring.lifecycle.timeout-per-shutdown-phase`（`application.yml#L1-L43`、`application-prod.yml#L6-L33`）。
- 工作流线程池配置了排水：`waitForTasksToCompleteOnShutdown=true`、`awaitTerminationSeconds=30`（`WorkflowExecutorConfig.java#L24-L25`），但排水结束后**没有**把仍在执行的 run 标记为失败/可恢复。
- 单 Agent 的 `new Thread` 不在 Spring 生命周期内；`SseEmitter.onCompletion/onTimeout` 只调用 `cancellation.cancel()` 终止 LLM 流（`RagChatService.java#L168-L180`），Agent 分支未传递该 cancellation，客户端断开不会停止 Agent（`RagChatService.java#L421-L429` 注释也说明“Agent 仍应继续执行”）。
- 多 Agent 的 `new Thread` 同理，SIGTERM 后随 JVM 一起消失。

### 1.9 历史脏数据实测（问题规模证据）

只读查询（本地开发库，未修改任何数据）：

```sql
SELECT 'workflow_run' AS t, id::text, status, coalesce(update_time,start_time) AS last_touch,
       now() - coalesce(update_time,start_time) AS age
FROM wgh_workflow_run WHERE deleted=0 AND status IN ('RUNNING','PLANNING')
UNION ALL
SELECT 'agent_plan', id::text, status, update_time, now()-update_time
FROM wgh_agent_plan WHERE deleted=0 AND status IN ('RUNNING','PLANNING')
UNION ALL
SELECT 'm_agent_run', id::text, status, coalesce(update_time,start_time), now()-coalesce(update_time,start_time)
FROM wgh_m_agent_run WHERE deleted=0 AND status IN ('RUNNING','PLANNING')
ORDER BY 4;
```

结果（2026-11-18）：

| 表 | id | status | 最后更新时间 | 已停留 |
|---|---:|---|---|---|
| `wgh_workflow_run` | 33 | RUNNING | 2026-10-28 11:37:23 | 20 天 21 小时 |
| `wgh_workflow_run` | 36 | RUNNING | 2026-10-28 11:37:26 | 20 天 21 小时 |
| `wgh_workflow_run` | 37 | RUNNING | 2026-10-28 11:39:27 | 20 天 21 小时 |
| `wgh_m_agent_run` | 3 | PLANNING | 2026-10-28 16:21:44 | 20 天 16 小时 |
| `wgh_agent_plan` | 64 | RUNNING | 2026-11-02 10:06:29 | 15 天 23 小时 |
| `wgh_workflow_run` | 1472 | RUNNING | 2026-11-16 13:28:27 | 1 天 19 小时 |

按状态汇总（节选）：

| 表 | 状态 | 数量 | 其中 >1h 未更新 |
|---|---|---:|---:|
| `wgh_agent_plan` | RUNNING | 1 | 1 |
| `wgh_agent_step` | RUNNING | 1 | 1 |
| `wgh_agent_step` | PENDING | 5 | — |
| `wgh_agent_plan` | PAUSED | 1 | —（HITL，非崩溃） |
| `wgh_agent_step` | HITL_PENDING | 1 | — |
| `wgh_workflow_run` | RUNNING | 4 | 4 |
| `wgh_workflow_run` | PAUSED | 11 | —（HITL） |
| `wgh_workflow_node_run` | HITL_PENDING | 11 | — |
| `wgh_m_agent_run` | PLANNING | 1 | 1 |
| `wgh_m_agent_run` | PAUSED | 1 | — |

两个可复现的“崩溃指纹”：

1. `agent_plan=64`：`step_1` 为 RUNNING（`start_time=10:06:32.98`，`update_time=10:06:29.12`），`step_2` 为 SUCCESS（`start_time=10:06:29.14`，`end_time=10:06:32.96`），`step_3/4` 为 PENDING。`step_1` 的开始时间晚于 `step_2` 的完成时间，说明 `step_1` 在 `step_2` 成功后曾被单独重试（`retryStep` 会重置 `start_time`），随后进程消失，整条计划再没人推进；同时该行 `update_time` 早于 `start_time`，印证 §1.3。
2. `workflow_run=1472`：所有 node_run 都是 SUCCESS（最后一个 LLM 节点 `end_time=13:28:27.706`），run 的 `update_time=13:28:27.710` 仍是 RUNNING。说明进程在“最后一个节点成功之后、`finishRun` 之前”被终止。

> 口径说明：以上是本地开发库快照，不代表生产规模；但机制与代码路径一致，具有代表性。HITL 的 PAUSED/HITL_PENDING 属于“等待人工”，不是崩溃脏数据，但同样缺少过期策略。

### 1.10 现状缺陷清单

| 编号 | 缺陷 | 影响 | 证据 |
|---|---|---|---|
| D1 | 无执行者/租约/心跳 | 无法判定任务是否还活着 | §1.1 |
| D2 | `update_time` 不刷新 | 不能作为存活依据 | §1.3 |
| D3 | 无超时/回收/对账 | 崩溃后永久停留 | §1.6、§1.9 |
| D4 | 单 Agent 裸线程执行 | 无法排水、无法中断、无并发上限 | `RagChatService#L197-L198` |
| D5 | 取消非协作式 | 取消后仍在执行并落库 | `AgentOrchestrator#L195-L200`、`#L292` |
| D6 | `retryStep` 无状态校验 | 可对 RUNNING 步骤重复执行 | `AgentController#L148-L170` |
| D7 | 无分布式互斥 | 多实例/HITL 双提交重复执行 | §1.7 |
| D8 | 无启动对账 | 重启后历史 RUNNING 永远无人处理 | §1.6 |
| D9 | 工作流 5 分钟 join 后强杀 worker | 长节点导致假死 RUNNING | `WorkflowEngine#L508-L517` |
| D10 | 多 Agent 步骤只记成功、无上下文快照 | 无法续跑，Crew resume 整体重跑 | `CrewCoordinator#L83-L88`、`#L422-L443` |
| D11 | 前端无限轮询、无失联判定 | 用户永远看到“执行中”；请求放大 | `ChatPlayground.vue#L1065-L1092`、`MultiAgentPlayground.vue#L737-L759` |
| D12 | 无 SSE 重放 | 刷新/断线丢失中间过程 | §1.5 |

---

## 2. 方案设计

### 2.1 主流做法对比

| 方案 | 核心机制 | 解决的问题 | 本系统适配成本 | 结论 |
|---|---|---|---|---|
| **租约/心跳 + 超时扫描回收** | 执行者定期续租；扫描器把过期活跃行判死或接管 | 进程消失后状态收敛 | 低：加列 + 定时任务 + 条件更新 | **采纳（L0 核心）** |
| **执行者选主 / 分布式锁** | 单写者或多写者互斥 | 多实例重复执行 | 低-中：PG advisory lock 或行级 claim | **采纳（行级 claim 优先）** |
| **幂等步骤 + 检查点续跑** | 步骤有 attempt/idempotency key；从最近检查点继续 | 崩溃后自动恢复、避免重复副作用 | 中：单 Agent 已有步骤级检查点；工作流需补游标；多 Agent 需补步骤开始落库 | **采纳（L1）** |
| **事务性 outbox** | 业务状态与事件同事务写 outbox，异步投递/重放 | SSE 事件可靠投递、外部副作用可追溯 | 中：新增表 + 投递器 | **部分采纳（L3，用于事件重放/工具日志）** |
| **状态机 + 补偿（Saga）** | 长事务拆本地事务，失败按逆序补偿 | 跨服务/外部写操作回滚 | 高：本系统工具副作用不可逆，补偿语义需业务定义 | **不整体采纳；仅做“副作用不可逆 → 人工介入”** |
| **持久化执行引擎（Temporal 类）** | 工作流即代码，事件溯源、定时器、重试内置 | 通用长时任务编排 | 很高：引入新集群/运维、重写编排层 | **暂不采纳；保留演进条件** |
| **启动时状态对账** | 启动扫描活跃行，修复或标记 | 重启后的残留状态 | 低：复用回收扫描的一次性执行 | **采纳（L0）** |
| **死信 + 人工介入** | 多次恢复失败进入死信队列，人工处置 | 不可自动恢复的失败 | 低：新增失败码 + 管理接口/页面 | **采纳（L1/L2）** |
| **SSE 断线重连 + 事件重放** | Last-Event-ID + 持久化事件流 | 刷新/断线不丢进度 | 中：事件表 + 重放接口 | **L3；L0 用状态轮询兜底** |
| **优雅停机** | 停止接单 → 排水 → 标记/持久化检查点 | 发布重启不制造脏数据 | 低：Spring 配置 + drain 协调器 | **采纳（L0）** |

### 2.2 推荐方案总览

**核心原则：数据库是唯一事实源；“执行中”必须带租约；任何终态都必须可解释。**

```
┌──────────────────────────────────────────────────────────────┐
│ 执行者（Web 请求线程 / 受管执行池）                            │
│  1. claim: 条件更新获取 lease(owner, expire)                  │
│  2. heartbeat: 每 10s 续租 + 更新当前 step 心跳                │
│  3. checkpoint: 每步/每节点成功即落库（现状已有）              │
│  4. release: 终态时清空 owner/lease                           │
└───────────────┬──────────────────────────────────────────────┘
                │ 条件更新（WHERE owner=me AND status=RUNNING）
┌───────────────▼──────────────────────────────────────────────┐
│ 回收器 RecoveryScheduler（每 30s，单实例执行或 SKIP LOCKED）   │
│  - 扫 status IN (RUNNING/PLANNING) AND lease_expire < now()   │
│  - 按策略：自动续跑 / 标记 FAILED(failure_code) / 进死信       │
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│ 前端：状态接口返回 lease/heartbeat/failureCode；轮询有上限     │
│  RUNNING 但心跳停滞 > 30s → 显示“执行节点失联，正在回收…”      │
└──────────────────────────────────────────────────────────────┘
```

**为什么不用 Temporal 类引擎**：
- 当前三套编排都在单体进程内，引入外部引擎意味着重写 Planner/Executor/WorkflowEngine 的调度层，且要新增集群运维。
- 现有表已经具备“步骤/节点级检查点”的基础，补齐租约与游标的成本远低于重写。
- 触发重新评估的条件：单日执行量 > 10 万、需要跨服务 Saga、需要精确的定时器/延迟任务、多租户隔离执行队列。

### 2.3 状态机与合法迁移

在现有状态集合上**不新增业务状态**（避免前端大改），新增 `failure_code` 区分失败原因。仅多 Agent 增加一个内部状态 `RECOVERING` 可选（见下）。

#### 单 Agent Plan

| 当前 | 事件 | 目标 | 约束 |
|---|---|---|---|
| — | 计划落库 | RUNNING | `createPlanTx` 保持现状 |
| RUNNING | 全部步骤完成 | COMPLETED | 写 finish_time |
| RUNNING | 步骤失败且无法重规划 | FAILED | `failure_code=STEP_FAILED` |
| RUNNING | 命中 HITL | PAUSED | 存在 HITL_PENDING 步骤 |
| RUNNING | 用户取消 | CANCELLED | 需协作式取消（§2.9） |
| RUNNING | 租约过期 | FAILED 或 RUNNING（接管） | `failure_code=EXECUTOR_LOST`；自动续跑时 owner 变更、`recover_count+1` |
| RUNNING | 超过 `plan-timeout` | FAILED | `failure_code=TIMEOUT` |
| PAUSED | HITL 确认 | RUNNING | 必须 claim 租约 |
| PAUSED | 人工取消 | CANCELLED | |
| FAILED | 人工 retry | RUNNING | 需要显式 claim；仅允许 `retryable=true` |
| COMPLETED | 任意 | — | 终态 |
| CANCELLED | 任意 | — | 终态（人工 retry 除外） |

#### 单 Agent Step

| 当前 | 事件 | 目标 |
|---|---|---|
| PENDING | 开始执行 | RUNNING（写 start_time、attempt+1） |
| RUNNING | 成功 | SUCCESS（写 end_time/result_summary） |
| RUNNING | 失败 | FAILED（写 `failure_code`） |
| RUNNING | HITL | HITL_PENDING（plan → PAUSED） |
| RUNNING | 租约过期 | FAILED（`EXECUTOR_LOST`）或 PENDING（可安全重试时） |
| PENDING/RUNNING/HITL_PENDING | 取消 | SKIPPED |
| FAILED | 人工重试 | PENDING → RUNNING |
| HITL_PENDING | 用户确认 | SUCCESS |

禁止：`SUCCESS → RUNNING`、`SKIPPED → RUNNING`（除人工显式重置且记录审计）、`FAILED → SUCCESS` 直跳。

#### 工作流 Run / NodeRun

| 当前 | 事件 | 目标 |
|---|---|---|
| RUNNING | 全部节点完成 | COMPLETED |
| RUNNING | 节点失败且 STOP | FAILED |
| RUNNING | HITL | PAUSED |
| RUNNING | 取消 | CANCELLED |
| RUNNING | 租约过期 | 自动续跑（有游标）或 FAILED(`EXECUTOR_LOST`) |
| PAUSED | HITL 确认 | RUNNING |
| DEBUG_PAUSED | 继续调试 / 结束 | DEBUG_PAUSED / COMPLETED / FAILED |
| NodeRun RUNNING | 租约过期 | FAILED(`EXECUTOR_LOST`)；节点重试策略见 §2.7 |

#### 多 Agent Run

| 当前 | 事件 | 目标 |
|---|---|---|
| PLANNING | 打开 stream | RUNNING |
| PLANNING | 超过 `planning-timeout`（如 30m） | FAILED(`RUN_NOT_STARTED`) |
| RUNNING | 正常完成 | COMPLETED |
| RUNNING | 异常 | FAILED |
| RUNNING | HITL | PAUSED |
| RUNNING | 取消 | CANCELLED |
| RUNNING | 租约过期 | FAILED(`EXECUTOR_LOST`)（L0）；L1 若已有步骤级检查点可接管 |
| PAUSED | HITL 确认 | RUNNING |

**实现建议**：新增 `failure_code`（`EXECUTOR_LOST / TIMEOUT / SHUTDOWN / STEP_FAILED / RUN_NOT_STARTED / TOOL_STATE_UNKNOWN`），前端据此展示不同文案；不把失败原因塞进 `error_message` 之外的自由文本。

### 2.4 租约与心跳

#### 新增字段（三套运行表统一）

| 表 | 新增列 | 类型 | 说明 |
|---|---|---|---|
| `wgh_agent_plan` | `executor_id` | VARCHAR(64) | 执行实例 ID |
| | `lease_expire_time` | TIMESTAMP | 租约到期（用 DB `now()` 计算） |
| | `heartbeat_time` | TIMESTAMP | 最近心跳 |
| | `version` | INT DEFAULT 0 | 乐观锁，每次状态迁移 +1 |
| | `recover_count` | INT DEFAULT 0 | 已自动恢复次数 |
| | `failure_code` | VARCHAR(64) | 失败原因 |
| | `deadline_time` | TIMESTAMP | 计划硬截止 |
| `wgh_agent_step` | `executor_id` / `heartbeat_time` / `version` / `attempt` / `failure_code` / `idempotency_key` / `tool_side_effect` | 同上 | `idempotency_key` 唯一索引 |
| `wgh_workflow_run` | `executor_id` / `lease_expire_time` / `heartbeat_time` / `version` / `failure_code` / `frontier_json` | 同上 | `frontier_json` 存调度游标（L1） |
| `wgh_workflow_node_run` | `executor_id` / `attempt` / `idempotency_key` | | 节点级幂等 |
| `wgh_m_agent_run` | `executor_id` / `lease_expire_time` / `heartbeat_time` / `version` / `failure_code` / `context_snapshot_json` | | 快照用于 L1 续跑 |

> 说明：`idempotency_key` 用于“同一次逻辑执行”的重复提交防护，取值如 `plan:{planId}:step:{stepId}:attempt:{n}`；`tool_side_effect` 取值 `NONE/READ/WRITE/UNKNOWN`。

#### 参数

| 参数 | 建议值 | 理由 |
|---|---|---|
| 心跳间隔 | 10s | 足够轻量；单实例 100 个并发任务 = 10 QPS 更新 |
| 租约 TTL | 60s | 6 个心跳周期，容忍 5 次丢包/短 GC |
| 扫描间隔 | 30s | 最坏判死延迟 = 60s + 30s + 执行时间 ≈ 90s |
| 单步硬超时 | 20min | 覆盖长工具/大模型；超时由心跳线程或扫描器判定 |
| 单计划硬超时 | 60min | 防止“永远进行中” |
| `PLANNING` 超时 | 30min | 多 Agent 创建未启动 |
| HITL 过期 | 7d（可配） | 过期后转 FAILED(`HITL_EXPIRED`)，避免永久 PAUSED |
| 最大自动恢复次数 | 1（L0）→ 3（L1） | 防止崩溃循环 |
| 排水等待 | 30s | 与工作流线程池一致 |

**时间基准**：所有租约比较使用数据库 `now()`，不要用应用进程时间，避免多实例时钟漂移。

**心跳实现**：
- 单 Agent：一个 `AgentHeartbeatScheduler`（`ThreadPoolTaskScheduler`，poolSize=1），每 10s 扫描本实例正在执行的 planId 集合（进程内 `ConcurrentHashMap<Long, LeaseHandle>`），对每个 plan 执行：
  ```sql
  UPDATE wgh_agent_plan
  SET heartbeat_time = now(), lease_expire_time = now() + interval '60 seconds',
      update_time = now()
  WHERE id = ? AND executor_id = ? AND status = 'RUNNING';
  ```
  同步对当前 RUNNING step 执行同样更新（带 `WHERE id=? AND executor_id=? AND status='RUNNING'`）。
  如果返回 0 行 → 租约已被回收/被其他实例接管 → 设置该任务的协作取消标志，线程在下一个安全点退出（不再写库）。
- 工作流 / 多 Agent 同理，各自维护活跃 run 集合。

### 2.5 回收扫描（Reaper）

#### 扫描条件

- 单 Agent：`status IN ('RUNNING','PLANNING') AND deleted=0 AND (lease_expire_time IS NULL OR lease_expire_time < now())`
- 工作流：`status='RUNNING' AND deleted=0 AND (lease_expire_time IS NULL OR lease_expire_time < now())`
- 多 Agent：同上，额外包含 `PLANNING AND create_time < now()-30min`
- 步骤/节点：`status='RUNNING' AND heartbeat_time < now()-60s`（由 run 回收时一并处理）

#### 并发安全（多实例）

推荐 **`FOR UPDATE SKIP LOCKED` + 条件更新**，不依赖 Redis：

```sql
WITH expired AS (
  SELECT id
  FROM wgh_agent_plan
  WHERE deleted = 0
    AND status IN ('RUNNING','PLANNING')
    AND (lease_expire_time IS NULL OR lease_expire_time < now())
  ORDER BY lease_expire_time NULLS FIRST
  LIMIT 100
  FOR UPDATE SKIP LOCKED
)
UPDATE wgh_agent_plan p
SET status = 'FAILED',
    failure_code = 'EXECUTOR_LOST',
    finish_time = now(),
    executor_id = NULL,
    lease_expire_time = NULL,
    heartbeat_time = now(),
    update_time = now(),
    version = version + 1
FROM expired e
WHERE p.id = e.id
RETURNING p.id, p.session_id, p.current_step_index;
```

要点：
- 自定义 SQL 不会触发 MyBatis-Plus 的逻辑删除与自动填充，必须显式 `deleted=0`、显式 `update_time=now()`。
- 同一事务内把对应 RUNNING step 置 `FAILED`（`failure_code` 同上、`end_time=now()`），并写审计。
- 自动续跑模式（L1）下，不直接置 FAILED，而是条件更新 `executor_id = :newOwner, lease_expire_time = now()+60s, recover_count = recover_count + 1 WHERE id = ? AND version = ?`，然后投递恢复任务。
- 扫描器本身可加 PG advisory lock（`pg_try_advisory_lock`）降低空扫，但正确性由 `SKIP LOCKED` + 条件更新保证。

#### 启动对账

在 `ApplicationReadyEvent` 中执行一次与扫描器相同的逻辑（不必等待 30s）。建议加一个短延迟（如 5s）等本地资源就绪。启动对账与周期扫描共用 `RecoveryService`，避免两套逻辑。

### 2.6 崩溃在“步骤执行中”和“工具已产生副作用”时的判定

DB 现有信息只能告诉我们“步骤开始了”，不能告诉我们“工具执行到哪”。因此分两层：

#### 第一层：步骤级判定（L0，不需要新表）

用 `step.status + start_time + heartbeat_time + attempt` 判断：

| 场景 | 判定 | 处理 |
|---|---|---|
| step=PENDING，plan 崩溃 | 尚未开始 | 可直接续跑 |
| step=RUNNING，`tool_side_effect` 为 NONE/READ | 可安全重试 | 重置 PENDING，attempt+1，续跑 |
| step=RUNNING，`tool_side_effect` 为 WRITE/UNKNOWN | 结果未知 | 置 FAILED(`TOOL_STATE_UNKNOWN`)，人工确认 |
| step=SUCCESS | 已完成 | 续跑跳过 |
| step=HITL_PENDING | 等待人工 | 保持 PAUSED，不自动回收 |

`tool_side_effect` 的来源：步骤声明 `requiresTools` + 工具元数据。当前 `ToolConfig` 没有统一的读写分类，需要在工具注册表补 `sideEffect` 元数据；缺失时默认 `UNKNOWN`（保守）。

#### 第二层：工具调用级日志（L1/L2，新增表）

新增 `wgh_agent_tool_call`：

```sql
CREATE TABLE IF NOT EXISTS wgh_agent_tool_call (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL,
  step_id VARCHAR(32) NOT NULL,
  attempt INT NOT NULL,
  call_seq INT NOT NULL,
  tool_name VARCHAR(100) NOT NULL,
  side_effect VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  status VARCHAR(16) NOT NULL DEFAULT 'STARTED',  -- STARTED / SUCCESS / FAILED / UNKNOWN
  idempotency_key VARCHAR(160) NOT NULL,
  arguments_digest VARCHAR(64),
  result_digest VARCHAR(64),
  start_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  end_time TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted SMALLINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_tool_call_idem
  ON wgh_agent_tool_call(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_agent_tool_call_plan_step
  ON wgh_agent_tool_call(plan_id, step_id, status);
```

写入时机：`StepExecutor` 在调用 `ToolInvoker.invoke` **之前**插入 `STARTED`，返回后更新 `SUCCESS/FAILED`。崩溃恢复时：
- 存在 `STARTED` 且 `side_effect != READ` → 该步骤 `TOOL_STATE_UNKNOWN`，禁止自动重试。
- 存在 `STARTED` 且 `side_effect = READ` → 可安全重放。
- 全部 `SUCCESS` 但步骤未落库 → 可基于结果重建（可选）。

**幂等键**：`planId:stepId:attempt:callSeq:toolName`。注意 `attempt` 变化意味着重试，会生成新键；因此工具自身仍需幂等（例如写操作工具要求业务幂等键）。对于无法幂等的写工具，正确策略是**不自动重试**，而不是假装幂等。

### 2.7 重启后恢复执行的边界

| 运行类型 | 可自动续跑 | 只能标记失败/人工处理 |
|---|---|---|
| 单 Agent | ① 崩溃发生在两个 step 之间（下一步 PENDING）；② 当前 step RUNNING 且无写工具、无 `STARTED` 工具日志；③ 自动恢复次数未超限 | ① step 已产生写副作用且结果未知；② 自动恢复次数超限；③ 计划 JSON 损坏；④ 用户已取消；⑤ 崩溃在 Planner 阶段（尚无 plan 行，只能重发） |
| 工作流 | ① 崩溃在两个节点之间且已持久化 frontier（L1）；② RUNNING 节点为 LLM/RETRIEVAL/VARIABLE/CONDITION/EXPRESSION 等无外部副作用节点 | ① TOOL/AGENT/WORKFLOW 节点执行中；② 子流程运行中；③ 无 frontier 的旧数据（L0 只能 FAILED）；④ HITL_PENDING（保持 PAUSED，等待人工或过期） |
| 多 Agent | L1 之后：GRAPH/GROUP_CHAT 在无工具节点之间 | L0 全部 FAILED(`EXECUTOR_LOST`)；Crew 不自动续跑（会重复调用所有 agent）；任何已产生工具副作用的 agent 步骤 |

**单 Agent 自动续跑实现要点**：
- 新增 `AgentOrchestrator.resumeAfterCrash(Long planId, List<LlmModel> chain, ...)`：不要求 PAUSED；claim 租约后加载 DB steps，取“当前 RUNNING step → 重置 PENDING”或“第一个 PENDING”，按 `plan_json` 恢复 StepDTO，构建已完成步骤摘要，调用现有 `executeSteps` 的剩余列表。
- 必须复用 `plan_json` + DB steps 双源校验：DB steps 是执行事实，`plan_json` 是步骤定义；若某 stepId 在 `plan_json` 中不存在 → 放弃自动恢复，标记失败。
- 恢复执行入口必须是**受管线程池**（如新增 `agentRecoveryExecutor`，core=2/max=4/queue=50），不能再用裸线程。

**工作流自动续跑实现要点**：
- 在 `RunContext` 增加 `runFrontier`、`runPendingIn`、`runSubmitted`，每节点成功后随 `context_json` 落库（`persistContext`）。
- 引擎新增 `resumeFromCheckpoint(run, graph, validation, ctx, sink)`：用持久化游标重建 `frontier/pendingIn/submitted`，不复用 `resume()` 的 HITL 语义。
- 恢复前必须校验 `graph_snapshot` 与游标一致（节点 ID 存在性）。

**多 Agent 自动续跑（L1）**：
- 步骤“开始即落库”（RUNNING + start_time），成功后更新为 SUCCESS 并写输出摘要。
- `MultiAgentContext` 增加可序列化快照（vars、lastOutput、pending 后继），每步后落 `context_snapshot_json`。
- Crew 的 resume 改为“按 step_index 跳过已 SUCCESS 的 agent”；在执行前检查 `idempotency_key`。

### 2.8 多实例下的互斥

1. **执行前 claim**：所有入口（首次执行、HITL 确认、retry、自动恢复）统一调用：
   ```sql
   UPDATE wgh_agent_plan
   SET executor_id = :me,
       lease_expire_time = now() + interval '60 seconds',
       heartbeat_time = now(),
       version = version + 1,
       update_time = now()
   WHERE id = :planId
     AND deleted = 0
     AND (executor_id IS NULL
          OR executor_id = :me
          OR lease_expire_time < now())
   RETURNING version;
   ```
   返回 0 行 → 说明已被其他实例持有，接口返回 409/业务错误，前端提示“任务正在其他实例执行”。
2. **状态迁移带版本号**：`WHERE id=? AND version=? AND executor_id=:me`，避免“回收器刚判死、旧线程又写回”的脑裂。
3. **HITL 确认必须 claim**：当前 `AgentController.hitlConfirm` 直接调用 `resume`（`#L117-L120`），并发双击会两次执行。Go 修复：先 claim，后恢复；确认操作幂等（step 状态 CAS `HITL_PENDING → SUCCESS`）。
4. **retryStep 校验状态**：只允许 `FAILED/SKIPPED`（与注释一致），并对 plan claim。
5. **回收器互斥**：`FOR UPDATE SKIP LOCKED` 足够；可选 `pg_try_advisory_lock(hashtext('wgh-execution-reaper'))` 降低空扫。
6. **Redis 不作为正确性依赖**：当前 Redis 可降级为内存（`application.yml#L135-L143`），租约必须落在 PostgreSQL。

### 2.9 优雅停机

目标：**发布重启不产生新的脏数据；无法完成的任务明确标记为可恢复或失败。**

1. `application.yml` 增加：
   ```yaml
   server:
     shutdown: graceful
   spring:
     lifecycle:
       timeout-per-shutdown-phase: 45s
   ```
2. 新增 `ExecutionDrainCoordinator implements SmartLifecycle`（phase 大于 Web 服务器）：
   - `stop()` 时设置 `draining=true`，新请求直接拒绝（`503` 或业务提示）；
   - 等待在途 Agent/工作流/多 Agent 任务，最多 `shutdown-drain`（30s）；
   - 仍未完成的：把 plan/run 置为 `FAILED` + `failure_code=SHUTDOWN`，或（若开启自动恢复）清空 `executor_id`、`lease_expire_time=now()` 让重启后被接管；
   - step/node 置 `FAILED(SHUTDOWN)` 或保留 RUNNING 供恢复策略判断（推荐置 FAILED 并保留 `attempt`）。
3. 单 Agent 的 `new Thread` 改造为受管 `ThreadPoolTaskExecutor`（`agentChatExecutor`，core=4/max=16/queue=100，`waitForTasksToCompleteOnShutdown=true`），否则无法排水。注意：聊天 SSE 的响应线程与执行线程分离后，SSE 仍由请求线程返回 emitter，执行在池中完成。
4. 工作流：`WorkflowExecutorConfig` 已有 30s 排水，补齐排水后的状态标记；`WorkflowEngine` 的 `shutdownNow` 前应先把未完成节点标记为可恢复/失败。
5. 多 Agent：`new Thread` 同样改为受管池 `multiAgentExecutor`。

### 2.10 前端如何感知超时与失败

后端：
- `GET /api/agent/plans/{planId}/state` 增加字段：`executorId`（可脱敏）、`heartbeatTime`、`leaseExpireTime`、`failureCode`、`recoverCount`、`startedAt`、`elapsedMs`、`stalled`（服务端计算：`status='RUNNING' && heartbeatTime < now()-30s`）。
- 新增 SSE 心跳事件 `plan_heartbeat`（每 15s，data: planId/stepId/elapsedMs/workerState），让前端在连接存活时也能发现“心跳停滞”。
- 工作流/多 Agent 的状态接口同样返回 `heartbeatTime/leaseExpireTime/failureCode`。

前端（现状与改造）：
- 现状：`ChatPlayground.vue#L1065-L1092` 每 2s 轮询且无上限；`MultiAgentPlayground.vue#L737-L759` 相同；工作流 `#L1221-L1244` 相同。只要后端状态不变，就会永远轮询。
- 改造：
  1. 轮询指数退避：2s → 4s → 8s，上限 30s；
  2. `stalled=true` 或 `heartbeatTime` 超过 60s 未变 → 显示黄色横幅“执行节点失联，正在回收（预计 ≤90s）”；
  3. 收到 `failureCode=EXECUTOR_LOST/TIMEOUT/SHUTDOWN` → 红色失败态，展示原因与“重试/取消”按钮；
  4. 轮询总时长超过 `plan-timeout + 5min` → 停止轮询，提示“任务可能已中断，请刷新或重试”；
  5. SSE `onerror` 后不要直接把本地状态置 FAILED（`MultiAgentPlayground.vue#L510-L516` 目前会），应置为“连接中断，正在核对”，以服务端状态为准。

### 2.11 SSE 断线重连与事件重放

- L0 不做事件重放，靠“状态接口 + 轮询”兜底；这已经能恢复面板，只是丢失中间事件动画。
- L3 可选：
  - 新增 `wgh_execution_event`（`run_type/run_id/seq/event_name/payload_json/create_time`），事件与状态迁移同事务写入；
  - SSE 支持 `Last-Event-ID`，重连时按 `seq > last` 重放；
  - 需要设置 `seq` 单调（每 run 一个序列），并做保留期清理。
- 不建议用 Redis Stream 作为唯一事件存储（当前 Redis 可降级），事件表仍落 PostgreSQL。

---

## 3. 改造清单

### 3.1 模块与文件

| 模块 | 改造内容 |
|---|---|
| `rag-agent` | `AgentPlan/AgentStep` 增加字段；新增 `ExecutionLeaseService`、`AgentHeartbeatScheduler`、`AgentRecoveryService`、`AgentRecoveryExecutor`；`AgentOrchestrator` 增加 `resumeAfterCrash`、协作式取消检查点；`StepExecutor` 增加工具副作用分类与 `wgh_agent_tool_call` 写入；`PlanLifecycleService` 状态迁移改为 CAS |
| `rag-agent-multi` | `MultiAgentRun/Step` 增加字段；步骤开始即落库；新增 `MultiAgentRecoveryService`；Crew resume 支持跳过已完成；`context_snapshot_json` 快照 |
| `rag-workflow` | `WorkflowRun/NodeRun` 增加字段；`RunContext` 增加运行游标；新增 `resumeFromCheckpoint`；`WorkflowEngine` 去掉 5 分钟强杀，改为超时可配 + 标记；`WorkflowService` 增加恢复入口 |
| `rag-controller` | `AgentController` 增加 claim/恢复/状态字段；新增管理端 `POST /api/admin/execution/recover`（按 id 或批量）；`MultiAgentController`/`WorkflowController` 状态接口补字段 |
| `rag-app` | 配置项、调度器注册、`ExecutionDrainCoordinator`、`server.shutdown=graceful` |
| `rag-data` | 可选：修正 `MetaObjectHandlerImpl` 的 `updateFill` 为强制覆盖（或改为 `setFieldValByName`），避免 `update_time` 长期不更新；注意全局影响需评估 |
| `rag-frontend` | 轮询退避、stalled/失败码展示、停止条件 |

> `MetaObjectHandlerImpl` 的修正影响所有表，建议单独评估；执行恢复所需的 `heartbeat_time/lease_expire_time` 不依赖它。

### 3.2 表结构变更（建议迁移脚本，本次不执行）

```sql
-- 单 Agent
ALTER TABLE wgh_agent_plan
  ADD COLUMN IF NOT EXISTS executor_id       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS lease_expire_time TIMESTAMP,
  ADD COLUMN IF NOT EXISTS heartbeat_time    TIMESTAMP,
  ADD COLUMN IF NOT EXISTS version           INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS recover_count     INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS failure_code      VARCHAR(64),
  ADD COLUMN IF NOT EXISTS deadline_time     TIMESTAMP;

ALTER TABLE wgh_agent_step
  ADD COLUMN IF NOT EXISTS executor_id       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS heartbeat_time    TIMESTAMP,
  ADD COLUMN IF NOT EXISTS version           INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS attempt           INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS failure_code      VARCHAR(64),
  ADD COLUMN IF NOT EXISTS idempotency_key   VARCHAR(160),
  ADD COLUMN IF NOT EXISTS tool_side_effect  VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

-- 工作流
ALTER TABLE wgh_workflow_run
  ADD COLUMN IF NOT EXISTS executor_id       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS lease_expire_time TIMESTAMP,
  ADD COLUMN IF NOT EXISTS heartbeat_time    TIMESTAMP,
  ADD COLUMN IF NOT EXISTS version           INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS recover_count     INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS failure_code      VARCHAR(64),
  ADD COLUMN IF NOT EXISTS frontier_json     JSONB;

ALTER TABLE wgh_workflow_node_run
  ADD COLUMN IF NOT EXISTS executor_id       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS attempt           INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS idempotency_key   VARCHAR(200);

-- 多 Agent
ALTER TABLE wgh_m_agent_run
  ADD COLUMN IF NOT EXISTS executor_id            VARCHAR(64),
  ADD COLUMN IF NOT EXISTS lease_expire_time      TIMESTAMP,
  ADD COLUMN IF NOT EXISTS heartbeat_time         TIMESTAMP,
  ADD COLUMN IF NOT EXISTS version                INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS recover_count          INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS failure_code           VARCHAR(64),
  ADD COLUMN IF NOT EXISTS context_snapshot_json  JSONB;

ALTER TABLE wgh_m_agent_step
  ADD COLUMN IF NOT EXISTS executor_id       VARCHAR(64),
  ADD COLUMN IF NOT EXISTS heartbeat_time    TIMESTAMP,
  ADD COLUMN IF NOT EXISTS attempt           INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS failure_code      VARCHAR(64),
  ADD COLUMN IF NOT EXISTS idempotency_key   VARCHAR(200),
  ADD COLUMN IF NOT EXISTS tool_side_effect  VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

-- 工具调用日志（L1）
CREATE TABLE IF NOT EXISTS wgh_agent_tool_call (...);  -- 见 §2.6
```

索引（建议 `CREATE INDEX CONCURRENTLY`，不在事务内执行）：

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_plan_active_lease
  ON wgh_agent_plan(status, lease_expire_time) WHERE deleted = 0;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_step_active
  ON wgh_agent_step(status, heartbeat_time) WHERE deleted = 0;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_run_active_lease
  ON wgh_workflow_run(status, lease_expire_time) WHERE deleted = 0;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_node_run_active
  ON wgh_workflow_node_run(status, start_time) WHERE deleted = 0;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_m_agent_run_active_lease
  ON wgh_m_agent_run(status, lease_expire_time) WHERE deleted = 0;
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_agent_step_idem
  ON wgh_agent_step(idempotency_key) WHERE idempotency_key IS NOT NULL AND deleted = 0;
```

存量脏数据修复脚本（**只生成执行建议，本次不执行**）：

```sql
-- 先把 2026-11-18 之前遗留的活跃行标记为失败，避免新回收器与旧数据混淆
UPDATE wgh_agent_step
SET status='FAILED', failure_code='EXECUTOR_LOST', end_time=now(), update_time=now()
WHERE deleted=0 AND status='RUNNING' AND start_time < now() - interval '1 hour';

UPDATE wgh_agent_plan
SET status='FAILED', failure_code='EXECUTOR_LOST', finish_time=now(), update_time=now()
WHERE deleted=0 AND status='RUNNING' AND update_time < now() - interval '1 hour';

UPDATE wgh_workflow_run
SET status='FAILED', failure_code='EXECUTOR_LOST', finish_time=now(), update_time=now(),
    error_message=coalesce(error_message,'执行进程失联，已由运维标记失败')
WHERE deleted=0 AND status='RUNNING' AND coalesce(update_time,start_time) < now() - interval '1 hour';

UPDATE wgh_m_agent_run
SET status='FAILED', failure_code='RUN_NOT_STARTED', finish_time=now(), update_time=now(),
    error_message=coalesce(error_message,'创建后未启动')
WHERE deleted=0 AND status='PLANNING' AND create_time < now() - interval '30 minutes';
```

### 3.3 配置项

```yaml
wgh:
  instance:
    id: ${WGH_INSTANCE_ID:${HOSTNAME:local}}
  execution:
    recovery:
      enabled: ${WGH_EXEC_RECOVERY_ENABLED:true}
      heartbeat-interval: 10s
      lease-ttl: 60s
      scan-interval: 30s
      scan-batch-size: 100
      auto-resume: ${WGH_EXEC_AUTO_RESUME:false}   # L0 默认关闭，L1 打开
      max-auto-resume: 1
      step-timeout: 20m
      plan-timeout: 60m
      planning-timeout: 30m
      hitl-expire: 7d
      shutdown-drain: 30s
      use-advisory-lock: true
```

### 3.4 指标

| 指标 | 类型 | 标签 | 用途 |
|---|---|---|---|
| `wgh.execution.reaped` | Counter | `kind=agent_plan/agent_step/workflow_run/m_agent_run`, `reason=executor_lost/timeout/run_not_started` | 回收量 |
| `wgh.execution.recovered` | Counter | `kind`, `mode=auto/manual`, `result=success/failed` | 恢复成功率 |
| `wgh.execution.lease.lost` | Counter | `kind` | 执行者失去租约（脑裂预警） |
| `wgh.execution.heartbeat.errors` | Counter | `kind` | 心跳写失败 |
| `wgh.execution.stale` | Gauge | `kind` | 当前过期活跃行数（>0 告警） |
| `wgh.execution.duration` | Timer | `kind`, `outcome` | 端到端耗时 |
| `wgh.workflow.node.duration` | Timer | `nodeType` | 定位长节点（配合 5 分钟问题） |

告警建议：
- `wgh.execution.stale{kind=agent_plan} > 0` 持续 5 分钟；
- `wgh.execution.reaped{reason=shutdown}` 在发布窗口激增；
- `wgh.execution.recovered{mode=auto,result=failed}` > 阈值。

---

## 4. 分层落地路径

| 层 | 范围 | 解决场景 | 代价 | 风险 |
|---|---|---|---|---|
| **L0 止血**（1-2 天） | 加列 + 心跳 + 回收器 + 启动对账 + 状态接口补字段 + 前端失联提示 + 协作式取消/claim | 进程被杀、OOM、重启导致的永久 RUNNING；多实例重复执行；用户能看到失败原因 | 低：不改编排主流程，只在状态读写处加 CAS | 回收策略若配置过激会把长任务误杀（用 TTL/超时可配 + 观测） |
| **L1 自动续跑**（3-5 天） | 单 Agent 从步骤检查点续跑；工作流持久化 frontier 并续跑；多 Agent 步骤开始落库 + 快照；死信与重试上限 | 大部分崩溃无需人工；发布重启后自动恢复 | 中：需要恢复入口、幂等键、租约接管 | 重复执行/副作用；依赖 `plan_json` 与 DB steps 一致性 |
| **L2 副作用安全**（3-5 天） | `wgh_agent_tool_call` + 工具读写分类 + 写工具不自动重试 + 人工确认工作流；HITL 过期 | 工具已产生副作用时避免二次写入 | 中：工具元数据治理 | 需要逐个工具标注 `sideEffect`，缺失默认 UNKNOWN 会导致较多人工 |
| **L3 可观测与重放**（可选） | `wgh_execution_event` outbox + SSE Last-Event-ID 重放 + 管理端死信页 | 断线重连不丢进度；审计与排障 | 中高：事件表增长与保留策略 | 事件顺序/幂等/清理 |

**推荐顺序**：L0 → L1 → L2 →（按需）L3。先做 L0 是因为它成本最低、直接消除“永久 pending/RUNNING”，并为后续自动恢复提供判定依据。

**不推荐**：
- 直接引入 Temporal/XXL-Job 类外部引擎（改造成本远超收益）；
- 用 Redis 锁作为唯一互斥（当前 Redis 可降级为内存）；
- 依赖 `update_time` 做心跳（§1.3 已证伪）。

---

## 5. 验收标准与测试场景

### 5.1 验收标准

| 编号 | 标准 | 判定方式 |
|---|---|---|
| A1 | 进程被 `kill -9` 后，Agent plan/step 在 ≤90s 内离开 RUNNING（自动续跑或 FAILED） | 查询 `status` 与 `failure_code`；观察指标 `wgh.execution.reaped` |
| A2 | 发布重启（SIGTERM）后，没有 `RUNNING` 超过 2 分钟的 run | 启动后 2 分钟执行 §1.9 查询 |
| A3 | 两个实例对同一 plan 并发 HITL 确认，只有 1 个执行 | 其中一个返回冲突；step 只 SUCCESS 一次；工具日志无重复 |
| A4 | 对 RUNNING step 调用 retry 被拒绝 | HTTP 4xx + 业务错误 |
| A5 | 取消后执行线程在下一个安全点停止，不再写入 SUCCESS | 取消后等待 30s，step 状态不再变化 |
| A6 | 写工具执行中崩溃，不自动重试，标记 `TOOL_STATE_UNKNOWN` | step/plan 状态 + 人工可见 |
| A7 | 工作流在节点间崩溃可续跑且不重复执行已成功节点 | `workflow_node_run` 计数不增加已成功节点 |
| A8 | 创建后从未打开的 Multi-Agent run 在 30min 内 FAILED(`RUN_NOT_STARTED`) | 状态查询 |
| A9 | 存量脏数据修复后，`status RUNNING 且 >1h` 计数为 0 | 汇总查询 |
| A10 | 正常任务无回归：完成率、耗时、事件序列与改造前一致 | 回归测试 + 指标对比 |

### 5.2 测试场景

| 编号 | 场景 | 步骤 | 期望 |
|---|---|---|---|
| T1 | 单 Agent LLM 调用中崩溃 | 构造慢模型 → 执行到 step RUNNING → `kill -9` | ≤90s 判死；无写工具时自动续跑或 FAILED |
| T2 | 单 Agent 写工具执行中崩溃 | 选择写操作工具 → 崩溃 | step `FAILED(TOOL_STATE_UNKNOWN)`，不自动重试；前端提示人工处理 |
| T3 | 单 Agent 步骤间崩溃 | 第 2 步 SUCCESS 后、第 3 步开始前杀掉 | 从第 3 步续跑，第 1/2 步不重复 |
| T4 | 工作流节点间崩溃 | 节点 N SUCCESS、N+1 未开始 | 从 N+1 续跑（L1） |
| T5 | 工作流长节点 >5min | LLM 节点 8 分钟 | 不再被 `shutdownNow` 强杀；run 正常完成或按超时失败 |
| T6 | 多 Agent 创建未启动 | `POST /runs` 后不打开 stream | 30min 后 `FAILED(RUN_NOT_STARTED)` |
| T7 | 双实例并发 HITL | 两个实例同时 POST 确认 | 仅一个成功；无重复工具调用 |
| T8 | 取消竞态 | 执行中 cancel，同时线程准备写 step 结果 | 版本号冲突，结果不覆盖 CANCELLED；线程停止 |
| T9 | 回收器并发 | 两个实例同时扫描到同一过期行 | `SKIP LOCKED` 只让一个处理，无重复续跑 |
| T10 | 时钟漂移 | 故意让实例时钟偏差 ±5min | 租约判断仍正确（使用 DB `now()`） |
| T11 | 前端失联感知 | 停止后端 2 分钟，前端保持页面 | 显示“执行节点失联/正在回收”，轮询退避，不无限打满 |
| T12 | 存量数据修复 | 在备份库执行修复 SQL | 活跃行归零；历史数据可追溯（failure_code/finish_time） |

> 按用户要求，本次不改代码、不跑测试；以上为后续实施的验收口径。

---

## 6. 不引入机制的风险量化

### 6.1 已有实测量级

| 指标 | 实测值（本地库，2026-11-18） |
|---|---|
| 单 Agent 计划最长停留 | 15 天 23 小时（plan 64） |
| 工作流运行最长停留 | 20 天 21 小时（run 33/36/37） |
| 工作流运行近期新增 | 1 天 19 小时（run 1472） |
| 多 Agent PLANNING 停留 | 20 天 16 小时（run 3） |
| 当前活跃脏行 | 6 条（跨 3 张主表） |
| 已 PAUSED 的 HITL 运行 | Agent 1 条 + 工作流 11 条 + 多 Agent 1 条 |

### 6.2 增长估算（推断，非实测）

- **每次进程终止 = 在途任务数条脏数据**。单 Agent 只要崩溃在 `executeSteps` 内，必然留下 1 条 RUNNING plan + 1 条 RUNNING step；工作流崩溃在节点内留下 RUNNING run + RUNNING node。
- 假设日均 100 次 Agent 运行、其中 20% 单次超过 5 分钟（≈20 条长任务），每天 2 次发布，且发布时平均有 2 条在途 → 每天新增约 4 条；一个月约 120 条。若按 5% 崩溃/发布命中率，约 30 条/月。
- 脏数据不会自愈，只会累积；`GET /state` 仍会返回 RUNNING，前端每 2s 轮询：单条脏任务若页面常开 8 小时 = 14,400 次请求/天；10 条并发脏任务 = 144,000 次/天，全部打到数据库。
- 用户影响：永远转圈、无错误提示；只能手动取消（且取消对在途线程无效，见 D5），或在页面上点“重试步骤”（可能对 RUNNING 步骤重复执行，见 D6）。
- 运维影响：无任何指标/告警能发现“卡住”，只能靠用户投诉；无法区分“慢”与“死”。

### 6.3 不改造的隐性成本

- **重复副作用**：发布重启 + 人工重试写工具 → 外部系统重复写入（订单、工单、消息）。
- **数据不可信**：运行记录无法作为审计依据（不知道是谁执行、为何失败）。
- **容量不可估算**：无法判断当前活跃执行数，无法做限流与扩容决策。

---

## 7. 未验证项与推断声明

1. **未做运行时故障注入**：本次未执行 `kill -9`、未重启服务、未改数据库。所有“崩溃后状态”结论来自代码路径与历史脏数据，不是本次实验复现。
2. **本地库快照不代表生产**：§1.9 的数字来自本地开发库 `wgh_rag_dev`；生产实例数量与流量未知。
3. **生产部署形态未知**：仓库只有单实例 `docker-compose.yml`；是否有 K8s 多副本、滚动发布策略，仓库内无法确认，因此“多实例重复执行”是代码层面的可能性，不是已发生的事实。
4. **工具副作用分类缺失**：`ToolConfig` 当前没有统一的 `sideEffect` 字段（未逐文件穷举全部工具执行器），方案中的 `WRITE/READ/UNKNOWN` 需要工具治理阶段补齐。
5. **`MetaObjectHandlerImpl` 的 `strictUpdateFill` 行为**：结论基于 MyBatis-Plus 语义与本地库 `start_time > update_time` 的实测；未通过单测再次验证其他表的 `update_time` 行为。
6. **前端轮询请求量估算**：按 2s 间隔线性计算，未计入浏览器休眠、页面关闭、请求失败重试等真实因素。
7. **工作流 5 分钟强杀**：`dispatcher.join(5min)` 后 `shutdownNow()` 是代码事实；“会导致长节点 run 永久 RUNNING”是基于控制流的推断，未实测构造 8 分钟节点。

---

## 附录 A：关键证据索引

| 结论 | 证据 |
|---|---|
| 单 Agent 表结构 | `rag-backend/sql/schema/01-schema.sql#L530-L616` |
| 工作流运行表结构 | `rag-backend/sql/schema/01-schema.sql#L722-L811` |
| 多 Agent 表结构 | `rag-backend/sql/schema/01-schema.sql#L814-L889` |
| 状态常量 | `AgentConstants.java#L8-L16`、`WorkflowConstants.java#L22-L32`、`MultiAgentConstants.java#L8-L15` |
| 单 Agent 执行循环 | `AgentOrchestrator.java#L276-L488` |
| 单 Agent 持久化 | `PlanLifecycleService.java#L98-L255` |
| 步骤执行器 | `StepExecutor.java#L79-L282` |
| HITL 闸门 | `HitlGate.java#L36-L76` |
| 聊天入口与线程 | `RagChatService.java#L130-L198`、`#L418-L505` |
| 取消非协作 | `AgentOrchestrator.java#L195-L200`、`#L292` |
| retry 无状态校验 | `AgentController.java#L148-L170`、`AgentOrchestrator.java#L212-L246` |
| 多 Agent 线程与运行 | `MultiAgentController.java#L83-L102`、`MultiAgentRuntime.java#L59-L171` |
| Crew resume 重跑 | `CrewCoordinator.java#L83-L88` |
| 多 Agent 步骤只记成功 | `CrewCoordinator.java#L422-L443` |
| 工作流引擎与 5 分钟强杀 | `WorkflowEngine.java#L434-L518` |
| 工作流检查点 | `WorkflowEngine.java#L793-L798`、`RunContext.java#L14-L48` |
| 工作流恢复 | `WorkflowService.java#L448-L527` |
| 工作流线程池排水 | `WorkflowExecutorConfig.java#L15-L28` |
| `update_time` 不刷新 | `MetaObjectHandlerImpl.java#L31-L35` |
| 无定时任务 | 全仓库 `@Scheduled` 检索为空；`UploadSessionCleaner.java#L40-L55`、`WorkflowTriggerService.java#L51-L68` |
| 前端无限轮询 | `ChatPlayground.vue#L1065-L1092`、`MultiAgentPlayground.vue#L737-L759`、`ChatPlayground.vue#L1221-L1244` |
| 无 SSE 重放 | 全仓库无 `Last-Event-ID`/事件表；`WorkflowService.java#L61-L62`、`#L693-L707` |
| 单实例部署 | `docker-compose.yml#L9-L70` |
