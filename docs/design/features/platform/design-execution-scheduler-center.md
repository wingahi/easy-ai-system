# 任务调度中心统一规划（Chat / 工作流 / 计划任务 / Agent / 多 Agent）

> **版本**：v1.1（2026-11-24 同步落地状态）
> **日期**：2026-11-19（初稿）/ 2026-11-24（落地状态同步）
> **性质**：规划文档。不修改业务代码、不改配置、不执行 DDL、不重启服务、不做 git 操作。
> **定位**：本文是「任务调度中心」的统一规划层，**不推翻既有设计**，而是把两份既有设计中的 S0–S3 与 A–D 口径收敛成一份可执行排期，并补齐此前未单独列出的两类执行体（单 Agent、计划任务）。
> **复用来源（结论以此为准）**：
> - [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md)：lane 模型、租户策略、作业表、管理端、S0–S3
> - [design-distributed-tenant-scheduling.md](design-distributed-tenant-scheduling.md)：A–D 合并路径、集群口径、独立服务升级条件
> - [design-react-task-recovery.md](../agent/design-react-task-recovery.md)：租约、心跳、回收、恢复边界 L0–L3
> - [multi-tenancy-analysis-2026-11-18.md](../../../review/multi-tenancy-analysis-2026-11-18.md)：阶段 0–4 与 G-01～G-15 薄弱点
> **未验证/推断项**：见 §9

---

## 0. 结论摘要

1. **调度中心的定义**：不是新增一个独立调度服务，而是应用内的「准入（Admission）+ lane + 持久化作业 + 租约 + 租户策略 + 统一指标」组件（`rag-scheduler` 模块），多实例之间以 PostgreSQL 为唯一协调事实源。独立服务的升级条件已在 [design-distributed-tenant-scheduling.md](design-distributed-tenant-scheduling.md) §4.4 列出，当前明确**不满足**。
2. **统一承接的五类执行体**：聊天（RAG/ReAct）、工作流任务（手动/聊天/静默/CRON/Webhook）、计划任务（CRON、连接器同步、上传清理、语义缓存清理）、单 Agent（Planner/Executor/Reflection/HITL）、多 Agent（Crew/Graph/GroupChat）。
3. **关键判断不变（复用）**：后台型与交互式分开治理——`INTERACTIVE`（进程内受管池、低延迟、按租户公平）、`BACKGROUND`（持久化、租约、重试）、`MAINTENANCE`（advisory lock 单飞）。统一的是准入、策略、作业模型、指标与 lane 抽象，**不统一物理线程池**。
4. **本次补齐的现状缺口（新增结论）**：
   - **单 Agent 当前完全跑在 HTTP 请求线程内**：HITL 恢复走 `agentOrchestrator.resume(...)` 同步调用（[AgentController.java](../../../../rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/AgentController.java#L117-L120)），长计划会长期占用 Tomcat 线程与 DB 连接，且无并发上限——这是与「多 Agent 裸线程」相反的另一类无界问题，之前两份调度文档未单列。
   - **计划任务没有「任务」概念**：CRON、连接器、上传清理都是每实例自建线程/调度器（见 §1.2），无执行记录、无重试、无失败可见性，因此「计划任务」只能靠 advisory lock 止血，不能算统一承接。
5. **落地顺序（复用 A–D，不新造阶段）**：S0 止血（受管池 + 有界队列 + 单飞 + 指标 + 优雅停机 + 单 Agent 移出请求线程）→ S1 后台作业化（作业表 + 后台 lane + 评测/静默工作流/计划任务迁移）→ S2 租户策略全面生效 + 管理端写操作 → S3 集群语义、事件重放与死信。
6. **与多租户的关系**：调度中心从 S1 起强制携带 `tenant_id`（作业表字段 + 策略表 + 运行槽），S0 阶段允许 `tenant_id=0` 默认桶；不在调度器内定义租户模型。
7. **与向量库 / K8s 的关系**：调度中心是 K8s 多副本的前置条件（单飞、租约、集群口径），也是向量库重建/回填任务的执行载体（重建、批量回填、切换集合都应作为 BACKGROUND 作业）。

---

## 1. 统一承接范围与现状判定

### 1.1 五类执行体对照

> 「现状」列直接引用既有文档的代码证据；「阶段」列为本文建议的首次纳入阶段。

| # | 执行体 | 现状线程模型 | 现状缺陷 | 目标 lane | 首次纳入阶段 |
|---|---|---|---|---|---|
| J1 | 聊天 RAG / ReAct | 每请求一个裸 `new Thread`（`RagChatService#L197-L221`） | 无上限、不可排水、断线不可续 | `INTERACTIVE` | S0 |
| J2 | 工作流（聊天） | 共享 `workflowExecutor`（core 2 / max 8 / queue 100） | 与后台任务混池，池满悬挂 RUNNING | `INTERACTIVE`（独立池） | S0 |
| J3 | 工作流（手动） | 同上 | 同上 | `BACKGROUND` | S1 |
| J4 | 工作流（静默 / CRON / Webhook） | 同上 + 每实例本地 CRON 调度 | 与交互式争抢；N 副本 N 倍触发 | `BACKGROUND` + `MAINTENANCE`（触发单飞） | S0 单飞 / S1 作业化 |
| J5 | 计划任务：连接器同步 | 每实例 1 调度线程，同步执行（`ConnectorScheduler#L34-L49`） | 重复抓取；无执行记录 | `MAINTENANCE` | S0 单飞 / S1 作业化 |
| J6 | 计划任务：上传会话清理 | 每实例 1 调度线程（`UploadSessionCleaner#L40-L55`） | 重复删除（幂等但浪费） | `MAINTENANCE` | S0 单飞 / S1 作业化 |
| J7 | 计划任务：语义缓存清理 | 每次写入节奏触发（`SemanticCacheService#L223-L232`） | 多实例重复清理 | `MAINTENANCE` | S1 |
| J8 | 单 Agent（计划执行 / HITL 恢复） | **HTTP 请求线程内同步执行**（`AgentController#L117-L120`） | 长计划占用 Tomcat 线程 + DB 连接；无并发上限；请求超时即用户侧失败但执行继续 | `INTERACTIVE`（S0）→ `BACKGROUND` + 轮询（S1） | S0 |
| J9 | 多 Agent（Crew / Graph / GroupChat） | 每 run 一个裸线程 + 每 run 4–8 worker | 无全局上限；线程数随并发线性放大 | `INTERACTIVE`（S0）→ `BACKGROUND` + attach（S2） | S0 |
| J10 | 评测 | HTTP 请求线程 + `@Transactional` 长事务 | 占用连接数分钟；无法重试 | `BACKGROUND` | S1 |

> J8 的「请求线程」证据：`AgentController` 的 HITL 恢复直接调用 orchestrator 并同步返回结果；聊天链路的 Agent 分支同样在请求线程内推进（见 [design-react-task-recovery.md](../agent/design-react-task-recovery.md) §1.4）。

### 1.2 计划任务的单飞口径（复用）

| 任务 | 单飞手段 | 备注 |
|---|---|---|
| CRON 工作流触发 | `pg_try_advisory_lock(hashtext('wgh:cron:'||triggerId))` | 取不到锁直接跳过本轮；锁随连接释放 |
| 连接器同步 | `pg_try_advisory_lock(hashtext('wgh:connector:'||connectorId))` | 按连接器粒度，而不是全局 |
| 上传清理 | `pg_try_advisory_lock(hashtext('wgh:cleanup:upload'))` | 全局单飞 |
| 语义缓存清理 | `pg_try_advisory_lock(hashtext('wgh:cleanup:semcache'))` | 可容忍重复，单飞只降噪 |

**原则**：advisory lock 只解决「同一时刻只跑一份」，不解决「失败可见 / 可重试 / 可观测」；后者必须靠 S1 的作业化。S0 阶段允许计划任务继续用定时器 + 单飞，但必须接入统一指标。

### 1.3 判定标准（复用，不重造）

沿用 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §1.2 的三问：

1. 是否必须绑定活跃 HTTP/SSE 连接才产生用户价值？
2. 事件是否细粒度、低延迟（token / 步骤级）？
3. 断线后用户是否需要「接着看」同一段输出？

补充两条本项目特有的判定：

4. **是否已经在请求线程里同步执行超过 1 秒？**（J8 命中 → 至少移出请求线程）
5. **是否需要一个可查询的执行记录？**（计划任务命中 → 长期必须作业化，即便当前只做单飞）

---

## 2. 目标架构

### 2.1 总体结构（复用既有三 lane 图，补充作业来源）

```
                       ┌──────────────────────────────────────┐
  J1/J2/J8/J9 ────────▶│  AdmissionController                 │
  （请求内发起）        │  - 全局上限 / 租户策略 / 时间窗       │
                       │  - permit 或 429/503                 │
                       └───────────────┬──────────────────────┘
                                       │
      ┌────────────────────────────────┼────────────────────────────────┐
      │                                │                                │
┌─────▼──────────────┐   ┌─────────────▼──────────────┐   ┌─────────────▼─────────────┐
│ INTERACTIVE        │   │ BACKGROUND                 │   │ MAINTENANCE               │
│ 进程内受管池        │   │ wgh_execution_job + 租约    │   │ advisory lock 单飞         │
│ 有界队列 + 按租户公平│   │ claim / 心跳 / 重试 / 死信   │   │ 1–2 线程                   │
│ J1 J2 J8 J9        │   │ J3 J4 J5 J6 J7 J8 J9 J10   │   │ 定时触发、清理、对账        │
└─────┬──────────────┘   └─────────────┬──────────────┘   └─────────────┬─────────────┘
      │                                │                                │
      └────────────────────────────────┴────────────────────────────────┘
                                       │
                    统一：TenantSchedulePolicy、SchedulerRuntimeRegistry、
                    Micrometer 指标、审计、TenantContext 显式传递、错误码
```

### 2.2 作业模型（复用 `wgh_execution_job`，不新增表）

字段与索引沿用 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §5.1。本文只补充三类新增 `job_type` 的约定：

| job_type | lane | biz_key 语义 | payload 关键字段 | 幂等要求 |
|---|---|---|---|---|
| `EVAL_RUN` | BACKGROUND | `eval_dataset:{datasetId}:v{version}` | datasetId、version、topK、模型 | 同版本同日只允许一个在跑 |
| `WF_RUN` | BACKGROUND | `workflow:{workflowId}:{runId}` | workflowId、runId、triggerType | runId 已存在则跳过 |
| `AGENT_PLAN` | BACKGROUND（S1 起） | `plan:{planId}` | planId、sessionId、resumeStepId | 同 plan 只允许一个执行者 |
| `CONNECTOR_SYNC` | MAINTENANCE | `connector:{connectorId}:{slot}` | connectorId、batchSize | 同 connector 单飞 |
| `UPLOAD_CLEANUP` | MAINTENANCE | `upload-cleanup:{yyyyMMddHHmm}` | 阈值小时数 | 同 slot 单飞 |
| `SEMCACHE_CLEANUP` | MAINTENANCE | `semcache-cleanup:{yyyyMMddHH}` | 保留策略 | 幂等 |
| `KB_REBUILD` | BACKGROUND | `kb:{kbId}:rebuild:{targetCollection}` | kbId、targetCollection、目标模型/维度 | 同 kb 只允许一个重建（复用 `rebuild_status` CAS） |
| `VECTOR_BACKFILL` | BACKGROUND | `vector:{collection}:{batchNo}` | collection、游标、目标后端 | 按批次幂等 |
| `QUOTA_REFUND` | MAINTENANCE | `QUOTA_REFUND:recurring:{bucket}` | 无（扫描 `wgh_quota_charge`） | 桶内幂等；执行侧单飞 |

> `KB_REBUILD` / `VECTOR_BACKFILL` 是向量库规划（[design-vector-store-and-search-engine.md](design-vector-store-and-search-engine.md)）的执行载体；本文只约定它们走 BACKGROUND，不定义回填细节。
>
> **周期提交约定（2026-11-20 新增）**：需要按固定间隔提交的维护作业实现 `rag-execution` 的 `RecurringJob`，
> 由 `JobDispatcher` 按 `epochMillis / interval` 分桶提交，`bizKey=jobType:recurring:{bucket}`；
> 多实例同桶由 `(job_type, biz_key)` 唯一键去重，执行侧仍复用租约与维护车道 Redis 单飞。
> 业务侧不新增 `@Scheduled`，也不用进程内锁；Redis 只做锁，不作为待执行数据的存储。
> 首个落地为配额退款补偿 `QUOTA_REFUND`。

### 2.3 作业生命周期（复用状态机，补充「提交即失败」边界）

```
PENDING ──claim──▶ LEASED ──start──▶ RUNNING ──▶ SUCCEEDED
   │                  │                 │
   │                  │                 ├──▶ FAILED（可重试，attempt < max）
   │                  │                 └──▶ FAILED → DEAD（超过 max 或不可重试错误码）
   ├──cancel────────▶ CANCELLED
   └──queue timeout─▶ FAILED(JOB_QUEUE_TIMEOUT)
```

关键约束（S0 必须修）：
- **禁止先插业务状态再提交**。工作流当前先插 `RUNNING` 再 `workflowExecutor.execute(...)`，池满抛 `TaskRejectedException` 后永久 RUNNING（`WorkflowService#L327-L353`、`#L409`、`#L423-L444`）。S0 的修复口径：提交失败立即把 run/plan 置 `FAILED(SCHEDULER_REJECTED)`，或先占位再插库。
- 交互式任务不写作业表，但**必须写运行时指标**（活跃/排队/拒绝），否则同样无法发现悬挂。

### 2.4 统一治理面

| 组件 | 职责 | 阶段 |
|---|---|---|
| `AdmissionController` | 全局 + 租户准入，返回 permit / 429 / 503 | S0（仅全局）/ S2（租户） |
| `SchedulerRuntimeRegistry` | 每实例 lane 快照（运行/排队/拒绝） | S0 |
| `PolicyCache` | 策略本地缓存 + epoch 轮询 | S1 |
| `FairQueue` | 后台加权公平选择（`running / weight` 最小租户优先） | S2 |
| `Reaper` | 租约过期回收（30s 扫描，TTL 60s） | S0（复用恢复文档） |
| `JobHandler` SPI | 每类作业的执行入口，按 `job_type` 注册 | S1 |

---

## 3. 分阶段落地路径（S0–S3，与 A–D 对齐）

> 本节不新造阶段编号。S0–S3 与 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §6 完全一致；A–D 与 [design-distributed-tenant-scheduling.md](design-distributed-tenant-scheduling.md) §5.1 一致。本文只补充 J8/J5/J6/J7 的纳入动作与验收。

| 调度阶段 | 对应合并阶段 | 多租户阶段 | 恢复阶段 | 本阶段是否可上多副本 |
|---|---|---|---|---|
| S0 止血 | A | 阶段 0 | L0 | 可（2 副本最低门槛） |
| S1 后台作业化 | B | 阶段 1 | L1 | 可 |
| S2 策略与可视化写操作 | C | 阶段 2 | L2 | 可 |
| S3 集群语义与重放 | D | 阶段 3 | L3 | 可（精确口径） |

### 3.1 S0 止血

**改动**
1. 聊天、多 Agent 去掉裸 `new Thread`，改用受管 `INTERACTIVE` 池（有界队列 + 明确拒绝策略）。
2. **单 Agent 移出请求线程**：`INTERACTIVE` 池执行，接口改为「返回 planId + 轮询状态」或「SSE + 后台执行」；HITL 恢复同样走受管池。这是本文相对既有文档的**新增必要项**，否则 J8 的长计划仍会占满 Tomcat 线程。
3. 工作流拆 `workflowInteractiveExecutor` / `workflowBackgroundExecutor`，修复「先插 RUNNING 再提交」。
4. CRON / 连接器 / 上传清理 / 语义缓存清理加 advisory lock 单飞。
5. 所有池注册 Micrometer（活跃/队列/拒绝/耗时）；新增只读运行时快照接口。
6. `server.shutdown=graceful` + drain 协调器；在途任务落明确终态或可恢复标记。
7. 调度任务显式携带 `tenantId` / `userId`（允许为空，落 `tenant_id=0`）。

**验收**
- 500 并发聊天不再无界建线程；超出上限返回 429/503 而非 OOM。
- 池满时工作流 run / Agent plan 不停留在 RUNNING。
- 两副本部署：同一 CRON 只触发一次、同一连接器只同步一次。
- 单 Agent 长计划不再占用 HTTP 线程超过 1 秒（提交即返回）。
- SIGTERM 后在途任务有终态或可恢复标记。

**风险**：引入拒绝会改变「无限排队」的既有行为，需要前端展示 429；池大小需压测。

### 3.2 S1 后台作业化

**改动**
1. `wgh_execution_job` + 租约 claim + 心跳 + 重试 + 死信。
2. 迁移评测（J10）、静默/CRON/Webhook 工作流（J4）、连接器（J5）、上传清理（J6）、语义缓存清理（J7）到 BACKGROUND / MAINTENANCE。
3. **单 Agent 转 BACKGROUND 作业**：`AGENT_PLAN` 作业 + 状态查询接口；HITL 恢复以「确认动作」触发而不是同步执行。
4. `wgh_tenant_schedule_policy` + 默认策略；管理端只读（总览、作业列表、失败列表）。
5. 准入顺序调整为 `admission → quota`。
6. 作业提交接口返回 `202 + jobId`，前端改轮询。

**验收**
- 评测提交后立即返回 jobId，HTTP 线程占用 <100ms，不持有长事务。
- 进程崩溃后 ≤90s 离开 RUNNING，单实例重启后作业继续领取且不重复执行。
- 计划任务在管理端可见「上次执行时间 / 结果 / 耗时 / attempt」。

**风险**：评测接口契约由同步变异步，需要调用方适配；作业 payload 需版本化。

### 3.3 S2 租户策略与可视化写操作

**改动**
1. 策略完整生效：并发、排队、权重、优先级、时间窗、日/分钟配额、拒绝策略。
2. `wgh_tenant_runtime_slot` 集群占槽；后台加权公平。
3. 管理端策略编辑、试算、单租户视图、作业重试/取消/回收；权限与审计。
4. 交互式准入读取策略缓存；`tenant_id=0` 默认桶与匿名低配额。
5. 多 Agent / 聊天工作流转「后台作业 + SSE attach」（依赖事件缓冲）。

**验收**
- 一个租户提交 100 个后台作业，不影响其他租户交互式 P95。
- 策略修改 60s 内全实例生效；运行中作业按提交时快照执行。
- 策略变更与作业操作 100% 审计。

### 3.4 S3 集群语义与重放

**改动**：交互式集群级并发（DB slot 或 Redis token bucket）；`wgh_execution_event` + SSE `Last-Event-ID` 重放；死信管理页；RLS 下调度器使用非超级用户连接。

**验收**：多副本租户并发精确；断线重连可补齐事件；死信可批量处置。

---

## 4. 与现有模块的改造点

| 模块 | 改造 | 阶段 |
|---|---|---|
| **新增 `rag-scheduler`** | 实体/Mapper、`JobSubmitter`、`JobDispatcher`、`JobHandler` SPI、`AdmissionController`、`PolicyCache`、`FairQueue`、`SchedulerRuntimeRegistry` | S0（最小）/ S1 |
| `rag-app` | 注册三 lane 线程池、调度器、优雅停机协调器；配置项 | S0 |
| `rag-chat` | ReAct 改受管池；准入在配额之前；SSE 事件不变 | S0 |
| `rag-agent` | 单 Agent 执行移出请求线程；HITL 恢复改异步 + 状态查询；续跑复用恢复文档 L1 | S0（移线程）/ S1（作业化） |
| `rag-agent-multi` | 去 `new Thread` 与 per-run 池；S2 转后台作业 + attach | S0 / S2 |
| `rag-workflow` | 拆交互/后台池；修复提交失败边界；静默/CRON/Webhook 改 `JobSubmitter`；游标外置 | S0 / S1 |
| `rag-kb` | 评测、连接器、上传清理、语义缓存清理、KB 重建改作业；`KB_REBUILD` 处理器 | S0（单飞）/ S1 |
| `rag-quota` | 不改语义；S1 调整准入顺序；S2 加租户维度 | S1 / S2 |
| `rag-common` | `TaskDecorator` 统一传递 `RequestContext`；错误码常量 | S0 |
| `rag-log` | 策略变更、作业重试/取消/回收、租户视图查看写审计 | S2 |
| `rag-controller` | `SchedulerAdminController`、租户侧只读接口、权限常量 | S1（只读）/ S2（写） |
| `rag-frontend` | 5 个管理页 + 路由 + 权限守卫；单 Agent 页面改轮询 | S1（只读）/ S2（写） |

---

## 5. 管理端与可观测（复用）

页面、接口、指标、权限与审计沿用 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §4，本文只补充：

| 补充项 | 说明 |
|---|---|
| 作业类型维度 | 指标标签增加 `jobType`（`EVAL_RUN` / `AGENT_PLAN` / `CONNECTOR_SYNC` / `KB_REBUILD` …），便于按业务线看失败率 |
| 「计划任务」视图 | 复用 `/admin/scheduler/jobs`，默认过滤 `lane=MAINTENANCE`，列展示「上次执行 / 下次预计 / 上次耗时」 |
| 「单 Agent」视图 | 在单租户视图内增加「运行中计划数 / 平均步骤耗时 / 等待 HITL 数」 |
| 告警阈值 | `wgh.scheduler.lease.expired` 连续 5 分钟 >0、`queued` 持续 10 分钟 > 0、`slot.drift` ≠ 0 均告警 |

---

## 6. 风险与不做的代价

### 6.1 主要风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 引入拒绝策略 | 现有前端未处理 429/503 | S0 同步改前端提示；灰度期提高上限 |
| 单 Agent 改异步 | 接口契约变化，HITL 交互路径变长 | 保持「同步短计划」兼容开关，按 plan 步骤数路由 |
| 公平调度实现错误 | 小租户饥饿或大租户抢占 | dry-run + 灰度 + `scheduler.wait.seconds` P95 监控 |
| 槽位漂移 | 并发上限失真 | 定期对账 + `slot.drift` 告警 |
| 作业 payload 版本演进 | 旧作业无法被新代码执行 | payload 带 `schemaVersion`，不兼容时置 `DEAD(INCOMPATIBLE_PAYLOAD)` |

### 6.2 不做的代价（承接既有量化结论）

- 不做受管池：并发聊天/多 Agent 无上限建线程，一次流量峰值可能 OOM。
- 不做单 Agent 移出请求线程：长计划占满 Tomcat 线程，聊天与其它接口一起 502。
- 不做后台作业化：评测长期占用连接；发布重启丢任务；计划任务无失败可见性。
- 不做集群单飞：N 副本 N 倍触发工作流与连接器，模型成本翻倍。
- 不做租户公平：一个租户可占满执行槽与队列，其他租户 429。

---

## 7. 验收标准与测试场景

### 7.1 验收标准

沿用 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §7.1 的 A1–A10，补充：

| 编号 | 标准 |
|---|---|
| A11 | 单 Agent 提交后 1 秒内返回 planId；执行在受管池内完成，可查状态 |
| A12 | 计划任务（CRON/连接器/清理）在管理端有执行记录与失败原因 |
| A13 | 五类执行体均无裸 `new Thread` 承载业务执行（含单 Agent） |
| A14 | 任一执行体被拒绝时，业务状态不停留 RUNNING，且前端有明确提示 |

### 7.2 测试场景（补充既有 T1–T14）

| 编号 | 场景 | 期望 |
|---|---|---|
| T15 | 单 Agent 100 步长计划 + 20 并发聊天 | 聊天 P95 不劣化；计划不受 HTTP 超时影响 |
| T16 | 单 Agent HITL 等待期间重启 | 重启后可继续确认；不重复执行已完成步骤 |
| T17 | 两副本同时到连接器同步时间 | 同一连接器只同步一次，另一实例记录 skip |
| T18 | 上传清理两副本同时触发 | 只执行一次，无重复删除 IO |
| T19 | 评测作业执行中进程被 kill | 租约过期后重试；attempt 递增；不重复入库 |
| T20 | KB 重建与检索并发 | 重建期间检索继续用旧 collection；切换后新检索命中新表 |

---

## 8. 与其它三块的衔接

| 块 | 衔接点 |
|---|---|
| 多租户 | S1 起作业表/策略/运行槽全部带 `tenant_id`；准入在配额之前；详见 [design-multi-tenancy-isolation-roadmap.md](design-multi-tenancy-isolation-roadmap.md) |
| 向量库/搜索引擎 | `KB_REBUILD`、`VECTOR_BACKFILL` 作为 BACKGROUND 作业；重建/回填/切换的可观测性由调度中心提供；详见 [design-vector-store-and-search-engine.md](design-vector-store-and-search-engine.md) |
| K8s 编排 | S0 的租约/单飞/优雅停机是多副本前置条件；HPA 扩容必须在 S0 之后；详见 [design-kubernetes-orchestration.md](design-kubernetes-orchestration.md) |

---

## 9. 落地状态（2026-11-24）

> 本节为实施后回填，逐条对照 §3 阶段项与代码；未完成项明确标注，不把规划当现状。

### 9.1 S0 止血

| 项 | 状态 | 代码 / 说明 |
|---|---|---|
| 三车道受管池 + 有界队列 | 已完成 | `rag-scheduler` `LaneExecutors` + `SchedulerProperties.lane.*` |
| 全局准入 + 显式拒绝 | 已完成 | `AdmissionController`（交互/后台/维护三口径；拒绝码 `SCHEDULER_REJECTED`） |
| 维护作业单飞 | 已完成 | `SingleFlightGuard`（Redis TTL；不可用时失败关闭） |
| 指标与运行时快照 | 已完成 | `SchedulerMetrics` + `GET /api/admin/scheduler/overview`、`/runtime` |
| K0 优雅停机与探针分组 | 已完成 | `application.yml` 停机/探针分组，`config/migrations/20261124/01_graceful-shutdown-probes.yml` |
| 普通对话接入调度中心 | 已完成 | 交互车道登记 `DefaultInteractiveRunFacade` + `ChatRunProperties` |
| 单 Agent 移出请求线程 | 部分完成 | HITL 恢复已改为 `AGENT_PLAN` 后台作业；首轮计划执行仍在聊天流式链路（见 §9.4） |

### 9.2 S1 后台作业化

| 项 | 状态 | 代码 / 说明 |
|---|---|---|
| 作业表 + 租约 + 心跳 + 重试 + 死信 | 已完成 | `wgh_execution_job`、`JobStore`、`JobDispatcher`、`JobRunner` |
| 评测 `EVAL_RUN` | 已完成 | `EvalRunJobHandler`；接口 202 + `GET .../evaluate/runs/{executionId}` 轮询；未启用调度时保留同步返回 |
| 静默/CRON/Webhook 工作流 `WF_RUN` | 已完成 | `WorkflowRunJobHandler`，`WorkflowService` 提交均走 `JobSubmitter` |
| 连接器 / 上传清理 / 语义缓存 / 配额退款 / 恢复扫描 | 已完成 | `KB_CONNECTOR_SYNC`、`KB_UPLOAD_CLEANUP`、`KB_SEMCACHE_CLEANUP`、`QUOTA_REFUND`、`RUN_RESUME_SCAN` |
| 单 Agent `AGENT_PLAN` | 部分完成 | HITL 确认续跑已后台化（bizKey `agent-hitl:{stepId}`）；首轮 ReAct 计划仍走聊天 SSE |
| 租户字段与默认策略 | 已完成 | 作业表 `tenant_id`（VARCHAR(64)，单租户 `default`）+ `wgh.scheduler.default-policy` |
| 提交返回 202 + jobId | 已完成 | 评测、单 Agent HITL；工作流聊天沿用既有 SSE 契约（事件重放承载断线续传） |

### 9.3 S2 / S3

| 项 | 状态 | 代码 / 说明 |
|---|---|---|
| `wgh_tenant_schedule_policy` + `PolicyCache` | 已完成 | 30s 轮询 + 管理端写后主动 reload；表缺失时回退默认策略 |
| 准入读取租户策略 | 已完成 | 并发/排队/拒绝方式；维护车道不受租户策略限制 |
| `wgh_tenant_runtime_slot` + `slot.drift` | 已完成（后台车道强制） | `TenantSlotManager` 执行前占槽、结束释放、周期按作业表校正；交互车道仍以作业/登记计数为准 |
| 管理端策略查询/更新/试算 | 已完成 | `GET/PUT /api/admin/scheduler/policies/{tenantId}`、`POST .../dry-run`（乐观锁 `version`） |
| 作业重试 / 强制回收 | 已完成 | `POST /jobs/{id}/retry`、`POST /jobs/{id}/reclaim`（`@Audit`） |
| 指标补齐 | 已完成 | `rejected`、`wait.seconds`、`job.duration`、`policy.load.failures`、`slot.drift` |
| 管理端前端页面 | 已完成，运行时端到端已验证 | `SchedulerCenter.vue` 页签：作业执行 / 租户策略 / 失败与死信 / 实例与对账；失败列表 `GET /jobs/failures`；`vite build` 通过，dev 运行时验证通过 |
| `wgh_execution_event` + `Last-Event-ID` | 已完成 | 事件总线订阅落库、`ExecutionEventStore` 重放、`GET /api/executions/{id}/events` SSE、7 天保留清理 |
| 多 Agent `BACKGROUND` + attach | 已完成，运行时端到端已验证 | 抽出传输无关 `AgentEventStream`（`SseAgentEventStream` / `JobAgentEventStream`）；新增 `AGENT_MULTI_RUN`、`AGENT_MULTI_HITL` 作业与事件桥接；attach 失败时按兜底轮询收敛状态 |
| 聊天工作流 `BACKGROUND` + attach | 已完成，运行时端到端已验证 | `WorkflowService#runForChatBlocking` / `#resumeForChatBlocking`（确认与扣费独立事务提交后再续跑）+ `WF_CHAT_RUN` / `WF_CHAT_HITL` 作业；`ChatWorkflowService` 提交作业并 attach 到聊天 SSE |
| 运行时端到端（2026-11-24） | **已通过（dev）** | 调度页签、SSE attach / 重放、后台作业与计费结算全链路通过，含 5 条修复：attach 兜底轮询、`fromSeq=0` 全量重放、作业线程恢复属主角色、payload 过滤 `null`、续跑事务边界。真实 PostgreSQL 双副本下的回收 / 对账与跨副本实时 tail 仍未验证 |
| 行级安全（RLS） | **已完成（dev 生效；prod/test 未切换）** | 41 表 / 52 策略已在 `wgh_rag_dev` 启用，应用以 `wgh_app` + 事务级 GUC 连接；`prod` / `wgh_rag_test` 仍为 `postgres`。详见 [多租户隔离路线图 §3.5](design-multi-tenancy-isolation-roadmap.md) |

### 9.4 与初稿的偏差（需后续确认）

1. **租户标识类型**：策略表/槽位表使用 `VARCHAR(64)`（与 `wgh_execution_job` 一致），不采用初稿的 `BIGINT`，避免调度域出现两种租户标识。
2. **策略字段生效范围**：`daily_job_limit`、`rate_per_minute`、`time_window_json` 已落表与透出，但准入暂未强制执行（只执行并发/排队/拒绝方式）。
3. **跨副本实时订阅**：事件表支持断线/跨副本**重放**；跨副本**实时 tail** 仍依赖执行实例内存总线，未做集群广播。
4. **多 Agent / 聊天工作流**：均已抽传输无关事件出口并提交后台作业 + attach；多 Agent 与聊天工作流的 HITL 续跑均已后台化（聊天 HITL 的确认/扣费先独立事务提交，再同步续跑）。
5. **验证记录（2026-11-24）**：`rag-controller -am` 编译 EXIT=0；`rag-scheduler` 单元测试 EXIT=0（40 个用例，含 `AdmissionControllerTest` 调整后的后台排队语义）；`rag-frontend` `vite build` EXIT=0；**运行时端到端已在 dev 通过**（调度页签、SSE attach / 重放、多 Agent 与聊天工作流后台化、HITL 扣费与结算），期间修复 attach 兜底轮询、`fromSeq=0` 全量重放、作业线程恢复属主角色、payload 过滤 `null`、续跑事务边界 5 项。仓库内暂无独立 E2E 报告，以上以本轮实施记录为准；真实双副本与跨副本实时 tail 仍未验证。

### 9.5 静态自检不一致点（2026-11-24）

| # | 不一致点 | 处理 |
|---|---|---|
| 1 | `wgh.scheduler.tenant-max-concurrent` 已不被读取（由 `default-policy.*` 取代） | 保留键避免存量配置启动失败；新配置请使用 `default-policy` |
| 2 | `daily_job_limit` / `rate_per_minute` / `time_window_json` 已落表并透出，但准入未强制执行 | 明确标注为「已建模、未启用」，不把字段存在当作能力 |
| 3 | `/api/executions/**` 无 `@RequiresPermission`，只依赖登录态 + ownerUserId 校验 | 系统执行（无 owner）仅管理员可查；如需细粒度权限需新增权限码 |
| 4 | 管理端调度读/写共用 `PERM_AUDIT_VIEW` | 未拆 `scheduler:view` / `scheduler:manage`，避免本轮扩大 RBAC 面 |
| 5 | 事件表对每个事件（含高频 DELTA）同步落库 | 存在写放大风险；如遇瓶颈按事件类型过滤或异步批量写入 |
| 6 | 跨副本仅支持事件表重放，不支持实时 tail | 多副本实时 tail 依赖集群广播，未在本轮实现 |
| 7 | 聊天工作流 HITL 的确认/扣费与续跑拆成「独立事务提交 → 作业线程执行」两段 | 与既有异步路径语义一致；失败时由 `executeHitlResume` 的计费收口处理 |
| 8 | 交互式车道未接入 `wait.seconds` / `job.duration` | 指标当前覆盖后台/维护作业；交互式沿用运行登记观测 |

---

## 10. 未验证与推断声明
1. **运行时功能验证已通过（2026-11-24，dev）**：调度页签、SSE attach / 重放、后台作业与计费结算已端到端跑通（含 §9.4 的 5 条修复）；以下为仍未验证或推断项。
2. **未做运行压测**：线程数、排队时长、连接池耗尽均为公式估算。
3. **单 Agent 请求线程时长**：结论来自 `AgentController` 同步调用路径，未实测长计划耗时分布。
4. **生产副本数未知**：仓库只有单实例 compose；「多副本重复触发」是代码层面成立。
5. **E15 审计线程池参数**：仓库无自定义 `TaskExecutor`，按 Spring Boot 默认推断。
6. **前端 429 处理成本**：未核对全部请求封装，仅确认现有页面无统一 429 组件。
7. **计划任务执行记录**：当前无表可查，本文的「执行记录」依赖 S1 作业表，不是现状。

---

## 附录 A：证据索引

| 结论 | 证据 |
|---|---|
| 聊天每请求裸线程 | `rag-backend/rag-chat/.../service/RagChatService.java#L197-L221` |
| 工作流池 2/8/100 | `rag-backend/rag-workflow/.../config/WorkflowExecutorConfig.java#L15-L28` |
| 工作流先插 RUNNING 后提交 | `rag-backend/rag-workflow/.../service/WorkflowService.java#L327-L353`、`#L409`、`#L423-L444` |
| 多 Agent 每 run 裸线程 | `rag-backend/rag-controller/.../MultiAgentController.java#L86-L99` |
| 单 Agent 同步执行（HITL 恢复） | `rag-backend/rag-controller/.../AgentController.java#L117-L120` |
| CRON 每实例注册 | `rag-backend/rag-workflow/.../service/WorkflowTriggerService.java#L51-L63`、`#L157-L171` |
| 连接器每实例调度 | `rag-backend/rag-kb/.../connector/ConnectorScheduler.java#L34-L49` |
| 上传清理每实例 | `rag-backend/rag-kb/.../service/UploadSessionCleaner.java#L40-L55` |
| 语义缓存清理无单飞 | `rag-backend/rag-kb/.../service/SemanticCacheService.java#L223-L232` |
| 评测同步 + 长事务 | `rag-backend/rag-kb/.../service/EvalDatasetService.java#L181-L205` |
| 既有 S0–S3 | `docs/design/features/platform/design-unified-scheduler-and-tenant-policy.md` §6 |
| 既有 A–D | `docs/design/features/platform/design-distributed-tenant-scheduling.md` §5 |
| 恢复 L0–L3 | `docs/design/features/agent/design-react-task-recovery.md` §4 |
