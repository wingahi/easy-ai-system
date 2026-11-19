# 统一调度器与按租户调度策略设计

> **版本**：v1.0
> **日期**：2026-11-18
> **适用版本**：当前 `main` 工作区（Spring Boot 3.4.5 / PostgreSQL / Redis / 多模块 Maven）
> **关联文档**：
> - `docs/design/features/agent/design-react-task-recovery.md`（租约、心跳、回收、恢复边界）
> - `docs/review/multi-tenancy-analysis-2026-11-18.md`（租户模型与阶段划分）
> - `docs/design/features/platform/design-execution-scheduler-center.md`（2026-11-19 统一规划：五类执行体承接范围与 S0–S3 排期，结论以本文为准）
> - `docs/design/features/platform/design-multi-tenancy-isolation-roadmap.md`（2026-11-19 多租户落地规划）
> **分析方式**：源码逐文件核对 + 现有配置读取；未修改代码、未执行 DDL、未操作数据库、未重启服务
> **未验证/推断项**：见 §9

---

## 0. 结论摘要

1. **当前是“每个功能各自造线程”的碎片化模型**：聊天与多 Agent 用裸 `new Thread`（无上限、不可排水），工作流用共享池（2/8/100），工作流引擎每个 run 再建 4 个 worker + 1 个 dispatcher，Crew 每个 run 再建 4-8 个 worker，AGENT 节点每次执行再建 1 个独立线程。线程总数没有全局上限。
2. **后台型任务与交互式任务混在同一个工作流池里**：聊天窗口工作流、手动运行、定时/Webhook 静默运行都提交到 `workflowExecutor`（`WorkflowService.java#L332`、`#L372`、`#L435`），一个租户的批量任务可以占满 8 个槽位和 100 个队列位。
3. **失败边界有明确缺陷**：`WorkflowService` 先插入 `RUNNING` 的 run，再 `workflowExecutor.execute(...)`；池满时默认 `AbortPolicy` 抛 `TaskRejectedException`，run 会永久停在 `RUNNING`（`WorkflowService.java#L327-L353`、`#L409`、`#L423-L444`）。这与上一份文档 §1.9 的脏数据是同一类问题。
4. **多实例下定时任务会重复触发**：`WorkflowTriggerService.init` 在每个实例加载全部 CRON 触发器并在本地调度（`WorkflowTriggerService.java#L51-L63`、`#L157-L171`），没有选主或分布式锁；`ConnectorScheduler` 同样在每个实例启动（`ConnectorScheduler.java#L34-L49`）。N 个副本 = N 倍触发。
5. **评测根本没有异步任务**：`EvalDatasetService.evaluate` 标注 `@Transactional`，在 HTTP 请求线程里逐条检索、逐条 judge，可能运行数分钟并长期占用一个数据库连接（`EvalDatasetService.java#L181-L205`、`RetrievalEvaluationService.java#L170-L181`）。
6. **上下文只传了 MDC，没有传用户/租户**：`MdcContext` 只复制 `requestId`（`MdcContext.java#L19-L50`）；异步线程里 `create_by` 退化为 `system`、配额退化为 `anonymous`（多租户文档 G-06、`DefaultQuotaUserResolver.java#L17-L24`）。直接把这些任务搬进调度器，会丢失归属与租户配额。
7. **判断一（后台型）：应抽取到统一调度器**。以“持久化作业表 + 租约领取 + 心跳 + 分 lane worker 池”承载：评测、工作流静默/CRON/Webhook、连接器同步、文件处理、存储清理。收益是失败可恢复、集群单飞、可观测、可按租户限流。
8. **判断二（交互式）：不应直接进持久化作业队列，但必须换成受管线程池**。聊天 ReAct、多 Agent 运行与 SSE 强绑定，事件是细粒度、要求低延迟；进入 DB 队列会引入领取延迟并放大断线复杂度。第一阶段做“受管交互 lane + 准入控制 + 协作式取消”，第二阶段再通过“事件缓冲 + 重连”把多 Agent/工作流演进为可 attach 的后台作业。
9. **统一到什么程度**：统一 **准入（Admission）、租户策略、作业模型、指标、lane 抽象**；不统一物理线程池。推荐三 lane：`INTERACTIVE`（进程内、低延迟、按租户公平）、`BACKGROUND`（持久化、租约、重试）、`MAINTENANCE`（advisory lock 单飞、1-2 线程）。
10. **租户策略**：新增 `wgh_tenant_schedule_policy(tenant_id, lane)`，配置并发上限、排队上限、权重、优先级、拒绝策略、时间窗、日/分钟配额；`tenant_id=0` 作为默认/匿名桶。与 `rag-quota` 的边界是：**quota 管“额度/用量”，scheduler 管“并发/排队/公平”**，不互相写表。
11. **可视化**：管理端新增调度总览、策略配置、队列与在跑任务、失败/重试、单租户视图五块；接口统一在 `/api/admin/scheduler/*`，新增 `scheduler:view/manage` 权限并全量审计。
12. **落地顺序**：S0 止血（受管池 + 有界队列 + 单飞 + 指标 + 优雅停机）→ S1 后台作业表 + 后台 lane + 评测/触发迁移 → S2 租户策略全面生效 + 管理端写操作 → S3 集群语义与死信/事件重放。

---

## 1. 现状：所有异步与定时执行盘点

### 1.1 全量清单

| # | 执行体 | 入口 | 线程模型 | 池参数 / 队列 / 拒绝策略 | 上限保护 | 生命周期 |
|---|---|---|---|---|---|---|
| E1 | 聊天 RAG + ReAct | `ChatController.streamChat` → `RagChatService.streamChat` | **每请求一个裸线程** | 无池、无队列、无拒绝策略 | **无**（仅 SSE 30min 超时） | 随 JVM |
| E2 | 多 Agent run | `MultiAgentController.streamRun` | **每 run 一个裸线程** | 无池 | **无**（SSE 10min 超时） | 随 JVM |
| E3 | 工作流（手动/聊天/静默） | `WorkflowService.run/runForChat/runHeadless` | 共享 `workflowExecutor` | core=2, max=8, queue=100；默认 `AbortPolicy`；shutdown 等 30s | 顶层 run 有界 | Spring 管理 |
| E4 | 工作流引擎内部调度 | `WorkflowEngine.executeLoop` | 每 run：1 dispatcher + 4 worker | core=max=4，queue=500，`AbortPolicy`；`join(5min)` 后 `shutdownNow` | 每 run 有界，**全局 = 并发 run × 4** | **每 run 创建/销毁** |
| E5 | Crew 并行 | `CrewCoordinator.runParallel` | 每 run：1 dispatcher + 4-8 worker | core=4, max=8, queue=200，`AbortPolicy`；`join(10min)` 后 `shutdownNow` | 每 run 有界，**全局 = 并发 run × 8** | **每 run 创建/销毁** |
| E6 | 工作流 AGENT 节点 | `AgentNodeExecutor.execute` | **每节点每次执行一个单线程池** | `newSingleThreadExecutor`，无队列上限；超时后 `shutdownNow` | 单节点超时（默认 60s） | 每节点创建/销毁 |
| E7 | CRON 工作流触发 | `WorkflowTriggerService` | `workflowTriggerScheduler`（2 线程） | pool=2；`ScheduledFuture` 存内存 Map | 无（同 cron 并发触发未防抖） | Spring 管理 |
| E8 | URL 连接器同步 | `ConnectorScheduler` | 1 个调度线程，**同步执行**同步逻辑 | pool=1；fixed delay | 单轮串行；batchSize 限制候选数 | Spring 管理 |
| E9 | 上传会话清理 | `UploadSessionCleaner` | 1 个调度线程 | pool=1；fixed delay | 单轮串行 | Spring 管理 |
| E10 | 文件解析/切分/入库 | `KnowledgeFileService` | **静态** `ThreadPoolExecutor` | 4/8，queue=50，**CallerRunsPolicy**，daemon | 有界；饱和时回压 Tomcat 线程 | 进程级，静态 |
| E11 | 查询变换（rewrite/multi-query/HyDE） | `QueryTransformService` | 每实例一个 **cachedThreadPool** | 无上限、无队列；`future.get(timeout)` | 仅单次超时 | 进程级 |
| E12 | 上下文压缩 | `AbstractLlmCompressionHandler` | **静态** fixed pool | 2 线程，无界 `LinkedBlockingQueue`（`newFixedThreadPool`） | **无**（队列无界） | 进程级，静态 |
| E13 | LLM-as-Judge | `LlmJudgeService` | 实例级 fixed pool | 默认 4（可配 `judgeConcurrency`），无界队列 | 有并发数，无排队上限 | 进程级 |
| E14 | 检索评测 | `EvalDatasetService.evaluate` | **HTTP 请求线程，同步** | 无池、无队列 | **无**；`@Transactional` 长事务 | 请求 |
| E15 | 审计日志 | `AuditLogService.saveAsync` | `@Async` → Boot 默认 `applicationTaskExecutor` | 未显式配置（Boot 默认 core=8、max/queue 近无界，见 §9） | **无** | Spring 管理 |
| E16 | 工具调用 | `ToolInvoker.invoke` | **调用方线程内串行**，不并行 | 无池；每任务预算 50 次/120s，失败最多 2 次 | 有预算，无全局并发 | 调用方 |

> 代码位置见附录 A。E15 的默认参数是“未配置即 Boot 默认”的推断，仓库内没有自定义 `TaskExecutor` Bean。

### 1.2 交互式 vs 后台型的判定标准

判定三个问题：

1. **是否必须绑定一个活跃的 HTTP/SSE 连接才能产生用户价值？**
2. **事件是否细粒度、低延迟（token/步骤级）？**
3. **断线后用户是否需要“接着看”同一段输出？**

| 执行体 | 绑定 SSE | 事件粒度 | 断线后可恢复 | 判定 |
|---|---|---|---|---|
| E1 聊天 ReAct | 是（`RagChatService#L421-L429` 直接 `emitter.send`） | token/步骤级 | 仅靠轮询计划状态 | **交互式** |
| E2 多 Agent | 启动时绑定；事件为 agent/round 级 | 中粒度 | 可轮询 `getRunState` | **交互式，但可演进为后台 + attach** |
| E3 工作流（聊天） | 启动时绑定；事件为 node 级 | 中粒度 | `getRunStateBySession` | **交互式，可后台化** |
| E3 工作流（手动/静默/CRON/Webhook） | 否（静默 sink 为空） | 节点级 | 是 | **后台型** |
| E14 评测 | 否（同步返回结果） | 无 | 否（请求超时即丢） | **后台型（当前误放在请求线程）** |
| E8 连接器同步 | 否 | 无 | 是 | **后台型（维护类）** |
| E9 上传清理 | 否 | 无 | 是 | **维护类** |
| E10 文件处理 | 否（前端轮询文件状态） | 无 | 是 | **后台型** |
| E11/E12/E13 | 是（调用方等待结果） | 无 | 否 | **请求内联型，不应入队** |

**关键区分**：E1 的 `sseEventSink` 在发送失败时只记日志并继续执行（`RagChatService.java#L421-L429`），所以“执行”本身已经与连接解耦了一半；但事件没有缓冲，断线后无法补发。这决定了它**可以后台化，但不能直接进“领取-执行-完成”的队列**，需要先有事件缓冲（见 §2.3）。

### 1.3 现有配置与开关

| 配置 | 值 | 位置 | 说明 |
|---|---|---|---|
| 工作流执行池 | core 2 / max 8 / queue 100 | `WorkflowExecutorConfig.java#L15-L28` | 未设置拒绝策略 → `ThreadPoolTaskExecutor` 默认 `AbortPolicy` |
| 工作流触发池 | pool 2 | `WorkflowExecutorConfig.java#L31-L38` | 与执行池隔离 |
| 工作流引擎 worker | 4 | `WorkflowEngine.java#L45-L47` | 每 run 创建 |
| Crew worker | 4-8 | `CrewCoordinator.java#L49-L50` | 每 run 创建 |
| 文件处理 | 4/8/50 + CallerRuns | `KnowledgeFileService.java#L61-L73` | 静态，无配置项 |
| 上下文压缩 | 2 | `AbstractLlmCompressionHandler.java#L26-L30` | 静态，无配置项 |
| Judge 并发 | 默认 4 | `LlmJudgeService.java#L58-L73`、`RagEvaluationProperties` | 可按配置调整 |
| 查询变换超时 | 构造注入 `timeoutMs` | `QueryTransformService.java#L54-L62` | 池本身无上限 |
| SSE 超时 | 聊天 30min，多 Agent 10min，工作流 30min | `ChatConstants.java#L37`、`MultiAgentController.java#L86`、`WorkflowService.java#L702` | 超时只关连接，不终止执行 |
| DB 连接池 | 开发 20 / 生产 50 | `application.yml#L19-L21`、`application-prod.yml#L23-L25` | 所有 lane 共享 |
| 工具预算 | 50 次 / 120s / 2 次尝试 | `ToolFallbackProperties.java#L20-L42` | 任务级，不是全局 |

**没有任何按租户/用户隔离的池、信号量或队列**。全仓库没有 `TenantContext` 的调度使用，也没有租户级并发计数。

### 1.4 现状问题（代码依据）

#### P1 可观测性

- 裸线程只有线程名，没有活跃数、队列长度、拒绝数、等待时长指标；`MdcContext` 只传 `requestId`（`MdcContext.java#L19-L50`）。
- 没有任何“当前有多少任务在跑/在排队”的查询接口；上一份文档 §1.9 的 RUNNING 脏数据无法通过指标发现。
- 工作流池、Crew 池、压缩池、Judge 池都没有 Micrometer 包装；只能看日志。

#### P2 上限与放大

- E1/E2 完全无界：并发聊天请求数 = 线程数。每个线程 1MB 栈 + 模型 HTTP 连接 + 可能的 DB 连接，容量不可控。
- E4 的 4 个 worker 是“每个 run”创建，不是全局 4 个；8 个并发 run = 32 个 worker，另有 8 个 dispatcher。
- E5 同理，8 个并发 Crew = 32-64 个 worker。
- E6 每个 AGENT 节点再建 1 个线程；嵌套工作流/多 Agent 会继续放大。
- E12 的无界队列 + 固定 2 线程是典型的“延迟无限增长”配置。

#### P3 公平性

- `workflowExecutor` 是单一 FIFO 队列：租户 A 提交 100 个长流程即可占满队列，租户 B 的手动运行排在其后；超过 100 直接拒绝。
- 交互式（聊天工作流）和后台式（CRON/Webhook）共享同一个池与队列，后台批量任务会把交互式请求排到后面。
- E13 的 4 个 judge 线程是全局共享，一个租户的大评测会拖慢其他租户。
- 没有权重、没有按租户配额、没有时间窗。

#### P4 失败边界

- **拒绝即悬挂**：`createRun` 先插入 RUNNING（`WorkflowService.java#L409`），随后 `workflowExecutor.execute`（`#L332`、`#L372`、`#L435`）。池满抛 `TaskRejectedException`，没有 catch 标记失败；run 永久 RUNNING。聊天路径还会让 emitter 永不完成。
- **静默失败**：`runHeadless` 的异常只 `markRunFailed`，但拒绝发生在提交阶段，不在 try 内（`#L435-L444`）。
- **强制中断**：`WorkflowEngine` 在 `join(5min)` 后无条件 `shutdownNow`（`#L508-L517`）；`CrewCoordinator` 在 `join(10min)` 后同样（`#L307-L312`）。长任务被中断但 run 不落终态。
- **超时只关连接**：SSE 超时不取消执行（`RagChatService#L168-L180` 只对普通流式生效，Agent 分支未接 cancellation）。
- **评测长事务**：`@Transactional` 包裹整个评测（`EvalDatasetService#L181`），期间占用连接；失败回滚但已消耗模型调用。

#### P5 停机

- 无 `server.shutdown: graceful`；E1/E2 的裸线程不受 Spring 管理，SIGTERM 后直接消失。
- E3 有 30s 排水（`WorkflowExecutorConfig#L24-L25`），但排水后不标记未完成 run。
- E4/E5/E6 的内层池在 finally 中 `shutdownNow`，本身就会中断正在执行的节点。

#### P6 多实例

- `WorkflowTriggerService.init` 在每个实例读取并注册全部 CRON（`#L51-L63`），`fire` 无原子抢占（`#L142-L151`）→ **N 副本 N 倍触发**。
- `ConnectorScheduler` 每个实例都跑（`#L34-L49`）→ 重复抓取、重复入库/覆盖。
- `UploadSessionCleaner` 每个实例都跑；删除是幂等的，但会产生重复 IO。
- 没有任何实例级/集群级选主、advisory lock 或 job claim。

#### P7 上下文丢失

- `MdcContext.wrap` 只复制 MDC（`requestId`），不复制 `LoginUser`/租户（`MdcContext.java#L19-L50`）。
- 异步线程创建资源时 `create_by=system`（多租户文档 G-06）；配额解析落到 `anonymous`（`DefaultQuotaUserResolver.java#L17-L24`）。
- 因此调度器必须显式携带 `tenant_id`/`owner_user_id`，不能依赖 ThreadLocal。

---

## 2. 是否应抽取到统一调度器

### 2.1 明确判断

| 执行体 | 是否进统一调度器 | 形态 | 理由 |
|---|---|---|---|
| E14 评测 | **是（最高优先）** | `BACKGROUND` 持久化作业 | 长耗时、无 SSE、当前阻塞请求线程并占长事务；最适合“提交-轮询” |
| E3 静默/CRON/Webhook 工作流 | **是** | `BACKGROUND` 持久化作业 | 已经脱离请求；需要集群单飞与重试 |
| E8 连接器同步 | **是** | `MAINTENANCE` 作业 | 需要集群单飞、避免重复抓取 |
| E9 上传清理 | **是** | `MAINTENANCE` 作业 | 单飞即可，逻辑简单 |
| E10 文件处理 | **是（S1）** | `BACKGROUND` 作业 | 已有有界池，但缺少租户公平、重试与可观测 |
| E1 聊天 ReAct | **否（进受管交互 lane，不进持久化队列）** | `INTERACTIVE` 受管池 | SSE 细粒度事件、首字延迟敏感；需要先有事件缓冲才能脱离请求 |
| E2 多 Agent | **S0 进受管池；S2 可转后台作业** | `INTERACTIVE` → `BACKGROUND` | 事件是 agent/round 级，可缓冲；当前 `new Thread` 必须替换 |
| E3 聊天工作流 | **S0 独立池；S2 转后台作业 + attach** | `INTERACTIVE` → `BACKGROUND` | 事件是 node 级；与静默工作流混池是当前公平性问题的主因 |
| E11 查询变换 | **否** | 请求内联 | 结果决定检索，必须同步等待；只需有界化 |
| E12 上下文压缩 | **否** | 请求内联 | 同上；需给固定池加有界队列与拒绝降级 |
| E13 LLM Judge | **否（但评测作业内部可复用）** | 请求内联 / 作业内并行 | 单次 judge 需要立即结果；要限制全局并发并加排队上限 |
| E15 审计 | **否** | 轻量异步 | 写失败可丢弃；换成小容量有界池即可 |

### 2.2 “统一到什么程度”

**推荐结构：统一治理面，分离执行面。**

```
                    ┌──────────────────────────────┐
                    │  AdmissionController          │
                    │  - 租户策略/全局上限/时间窗    │
                    │  - 返回 permit 或 429          │
                    └──────────┬───────────────────┘
                               │
        ┌──────────────────────┼────────────────────────┐
        │                      │                        │
┌───────▼────────┐   ┌─────────▼─────────┐   ┌──────────▼─────────┐
│ INTERACTIVE    │   │ BACKGROUND        │   │ MAINTENANCE        │
│ 进程内受管池    │   │ 持久化作业 + 租约  │   │ advisory lock 单飞 │
│ 有界队列        │   │ claim + 心跳       │   │ 1-2 线程           │
│ 按租户公平      │   │ 重试/死信          │   │ 定时/清理          │
└───────┬────────┘   └─────────┬─────────┘   └──────────┬─────────┘
        │                      │                        │
        └──────────────────────┴────────────────────────┘
                               │
                 统一：TenantSchedulePolicy、指标、审计、
                 TenantContext 显式传递、SchedulerRuntimeRegistry
```

- **统一**：准入控制、租户策略、作业元数据、指标命名、错误码、审计、运行时快照。
- **不统一**：线程池物理合并。把所有任务塞进一个池会造成头阻塞：一个 20 分钟的评测会挡住聊天首字。
- **作业持久化只用于 `BACKGROUND`**：交互式任务已经有 `wgh_agent_plan`/`wgh_m_agent_run`/`wgh_workflow_run` 作为业务状态，不需要再复制一份 job 行。
- **复用上一份文档的租约结论**：`executor_id + lease_expire_time + heartbeat_time + version`、`FOR UPDATE SKIP LOCKED`、DB `now()`、心跳 10s/TTL 60s、扫描 30s。

### 2.3 现在不该动的部分

| 对象 | 不该动的理由 | 允许的最小改动 |
|---|---|---|
| 聊天 SSE 协议与事件顺序 | 前端已依赖事件名与顺序；重写会破坏体验 | 只替换执行线程，不改事件 |
| ReAct 内部工具循环 | 工具语义、Reflection、预算已稳定 | 加取消检查点与工具日志（见上一份文档） |
| `workflowExecutor` 的 Bean 名 | 多处注入 | 新增独立池，旧 Bean 保留过渡 |
| 查询变换/压缩的同步语义 | 结果参与检索，异步化会改变链路 | 只加有界队列与降级 |
| 审计 `@Async` | 失败可丢弃，不是关键路径 | 换有界执行器，队列满丢弃并计数 |
| 租户模型本身 | 属于多租户阶段 1-2 的工作 | 调度器只消费 `tenant_id`，不定义租户 |

---

## 3. 按租户配置调度策略

### 3.1 与多租户模型的对接

严格复用 `docs/review/multi-tenancy-analysis-2026-11-18.md` 的定义与阶段，不另造概念：

- **租户**：`wgh_tenant(id BIGINT, code VARCHAR)`；一个用户属于一个租户（文档 §5.2）。
- **上下文**：`TenantContext` ThreadLocal；异步/调度任务显式传递（文档 §5.2、阶段 1 第 3 条）。
- **阶段对齐**：
  - 阶段 0（先修越权）：调度器不依赖租户，先做受管池与单飞。
  - 阶段 1（租户模型与身份上下文）：调度器开始读写 `tenant_id`，策略表上线。
  - 阶段 2（数据面全量隔离）：策略、指标、审计、作业表全部带 `tenant_id`。
  - 阶段 3（企业级加固）：集群级配额语义、RLS 兜底、计量。
- **当前事实**：`wgh_user.tenant_id` 是死字段（文档 G-14），配额/审计无租户维度（G-10）。因此 S0/S1 必须有“无租户默认桶”，不能假设租户一定存在。

### 3.2 策略模型

一张表，按 `(tenant_id, lane)` 唯一：

| 字段 | 含义 | 默认（tenant 0） |
|---|---|---|
| `enabled` | 是否启用策略；false 时回退默认策略 | 1 |
| `max_concurrent` | 该租户在该 lane 的并发上限 | INTERACTIVE 2 / BACKGROUND 2 |
| `max_queue` | 该租户在该 lane 的排队上限 | 20 / 200 |
| `weight` | 公平调度权重（1-100） | 10 |
| `priority_boost` | 作业基础优先级加成 | 0 |
| `reject_policy` | `REJECT` / `QUEUE` | INTERACTIVE=REJECT，BACKGROUND=QUEUE |
| `queue_timeout_seconds` | 排队超时，超时转失败 | 300 |
| `daily_job_limit` | 每租户每日作业数上限（0=不限） | 0 |
| `rate_per_minute` | 每分钟提交上限（0=不限） | 0 |
| `time_window_json` | 允许运行的时间窗 | null（全天） |
| `remark` / `version` | 备注与乐观锁 | — |

补充原则：
- **`max_concurrent` 是“运行中”上限，不含排队**；`max_queue` 是“等待中”上限。
- **交互式与后台分开**，避免“后台批量把交互槽占满”。
- **权重只影响排队顺序**，不做抢占；正在执行的任务不因权重变化被中断。
- **时间窗只约束后台**；交互式落在窗外时按 `reject_policy` 返回 429（可配置为仍放行）。
- **禁止在策略里配置线程池大小**：池大小是实例级资源，租户策略只做准入与配额。

### 3.3 存储与生效

**存储**：`wgh_tenant_schedule_policy`（见 §5.1）。写入走管理端接口，带 `version` 乐观锁。

**生效**：
1. `PolicyCache` 本地缓存（`ConcurrentHashMap<tenantId, Map<lane, Policy>>` + `loadedAt`）。
2. 每实例每 30-60s 轮询 `max(update_time)` 或 `policy_epoch`，有变更则全量重载；管理端写入后可通过 Redis pub/sub 广播失效（Redis 不可用时靠轮询兜底）。
3. 请求路径**只读本地缓存**，绝不查库；缓存未命中时使用默认策略并异步加载。
4. 策略变更写审计（before/after JSON），并递增 `epoch`，旧作业按提交时快照执行（避免运行中被突然改变上限）。

**语义**：
- 策略变更**不追溯**：已在排队的作业保留提交时的策略快照（`wgh_execution_job.policy_snapshot_json`），新提交使用新策略。
- 管理端提供“试算”：给定策略与当前负载，返回预计可接纳/排队/拒绝数量，不真正提交。

### 3.4 公平调度算法

**交互式 lane**：
- 每租户一个 `Semaphore(max_concurrent)`；获取失败立即按 `reject_policy` 返回 429（`TENANT_CONCURRENCY_EXCEEDED`）。
- 每租户一个等待计数；超过 `max_queue` 直接拒绝。
- 由于是进程内，等待队列由 `ThreadPoolExecutor` 的队列承担；**需要自己实现按租户计数的有界队列**（`RejectedExecutionHandler` 中判断租户，不能只按全局队列长度）。

**后台 lane（加权公平）**：
- 调度器按 `priority DESC, scheduled_at ASC` 基本排序，但每轮只从“当前在跑数 / weight”最小的租户取作业（加权公平队列，WFQ 简化版）：
  ```
  对每个有 PENDING 的租户计算 score = running / max(weight,1)
  选择 score 最小的租户，再按 priority/scheduled_at 取该租户最早作业
  ```
- 租户数量预计 < 1000，集中式扫描成本可接受；扫描在 dispatcher 单线程内完成。
- 不做跨租户抢占。

**维护 lane**：按 `job_type` 用 `pg_try_advisory_lock(hashtext('wgh:job:'||job_type))` 单飞；拿不到锁直接跳过本轮。

### 3.5 与 `rag-quota` 的边界

| 维度 | `rag-quota` | `scheduler` |
|---|---|---|
| 回答的问题 | “还能不能用/用多少” | “现在能不能同时跑/排第几” |
| 计数对象 | 聊天条数、token（用户/日或月） | 运行中作业数、排队数、提交速率 |
| 存储 | `wgh_quota_usage`、`wgh_user_quota_config` | `wgh_execution_job`、`wgh_tenant_schedule_policy`、`wgh_tenant_runtime_slot` |
| 失败语义 | 429 `QUOTA_EXCEEDED`，不执行 | 429 `TENANT_CONCURRENCY_EXCEEDED` / `TENANT_QUEUE_FULL`，不执行 |
| 租户维度 | 阶段 2 增加 `tenant_id`（文档 §3.7 改造点） | 从设计起就带 `tenant_id` |

**不互相写表**：
- scheduler 不更新 `wgh_quota_usage`；
- quota 不读取调度策略；
- 唯一交汇点是 `tenant_id` 与“准入顺序”。

**顺序问题（必须明确）**：当前聊天在 `RagChatService#L149` 先 `checkAndIncrementChat`，再创建 emitter 与执行线程。如果调度器在之后拒绝，用户已被扣一次对话额度。改造后的顺序必须是：

```
admission acquire → quota checkAndIncrement → execute → release
```

或 quota 在拒绝时提供补偿（`decrementChat`）。推荐前者，避免补偿逻辑。

### 3.6 默认值与降级

- **无租户/未登录**：`tenant_id=0` 的默认桶；匿名用户共享一个低配额桶（建议 INTERACTIVE 1、BACKGROUND 1），避免匿名流量挤占正式租户。
- **租户存在但无策略**：使用 `tenant_id=0` 的默认值（不是“不限”）。
- **策略加载失败**：使用最后一次成功缓存；无缓存则用 `application.yml` 中的 `wgh.scheduler.default-policy`；仍无则用代码默认值。
- **数据库不可用**：交互式 lane 仍可基于本地签名/缓存运行；后台 lane 无法 claim，保持 PENDING 并计数。
- **Redis 不可用**：策略失效广播降级为轮询；集群计数使用 PostgreSQL，不依赖 Redis。

### 3.7 多实例下的语义

| lane | 语义 | 实现 |
|---|---|---|
| INTERACTIVE | **S1：实例内上限**；集群上限 = 单实例 × 副本数（当前部署单实例，可接受） | 本地 Semaphore |
| INTERACTIVE | **S3：集群上限** | `wgh_tenant_runtime_slot` 原子加减，或 Redis token bucket（Redis 可用时） |
| BACKGROUND | **集群上限（从一开始）** | claim 时原子占槽：`wgh_tenant_runtime_slot`；作业结束释放 |
| BACKGROUND | 单作业只被一个实例执行 | `FOR UPDATE SKIP LOCKED` + `lease_owner` + `version` |
| MAINTENANCE | 集群单飞 | `pg_try_advisory_lock` |

`wgh_tenant_runtime_slot` 占槽 SQL（示意）：

```sql
INSERT INTO wgh_tenant_runtime_slot(tenant_id, lane, running, update_time)
VALUES (:tenantId, :lane, 1, now())
ON CONFLICT (tenant_id, lane)
DO UPDATE SET running = wgh_tenant_runtime_slot.running + 1, update_time = now()
WHERE wgh_tenant_runtime_slot.running < :maxConcurrent
RETURNING running;
```

返回 0 行 = 满槽，作业留在 PENDING。释放时 `running = greatest(running-1,0)`。需要定期对账修正（进程崩溃导致的槽泄漏），复用上一份文档的回收器。

### 3.8 拒绝与错误码

| 场景 | HTTP | 错误码 | 前端文案 |
|---|---|---|---|
| 租户并发已满 | 429 | `TENANT_CONCURRENCY_EXCEEDED` | “当前租户并发已达上限，请稍后重试” |
| 租户队列已满 | 429 | `TENANT_QUEUE_FULL` | “排队已满，请稍后重试” |
| 超出时间窗 | 429 | `OUTSIDE_SERVICE_WINDOW` | “当前不在服务时段” |
| 日作业数超限 | 429 | `TENANT_DAILY_LIMIT_EXCEEDED` | “今日任务数已达上限” |
| 全局容量不足 | 503 | `SCHEDULER_OVERLOADED` | “系统繁忙，请稍后重试” |
| 后台作业排队超时 | — | `JOB_QUEUE_TIMEOUT`（作业终态） | 任务详情展示 |

响应头建议：`Retry-After`、`X-WGH-Queue-Position`（后台作业）。

---

## 4. 可视化

### 4.1 管理端页面

| 页面 | 路由（建议） | 内容 | 数据来源 |
|---|---|---|---|
| 调度总览 | `/admin/scheduler` | 三 lane 卡片：运行中/上限、排队数、拒绝数、租约过期数；TopN 租户；24h 趋势 | `SchedulerRuntimeRegistry` + `wgh_execution_job` 聚合 + Prometheus |
| 策略配置 | `/admin/scheduler/policies` | 租户 × lane 列表；编辑并发/队列/权重/时间窗/配额；启用/停用；版本与变更历史；“试算” | `wgh_tenant_schedule_policy` |
| 队列与在跑任务 | `/admin/scheduler/jobs` | 过滤（lane/type/status/tenant/时间）；列：ID、类型、租户、状态、attempt、等待时长、运行时长、lease owner、心跳；操作：重试、取消、强制回收；详情抽屉 | `wgh_execution_job` |
| 失败与重试 | `/admin/scheduler/failures` | FAILED/DEAD 列表、错误码分布、批量重试、导出 | 同上 |
| 单租户视图 | `/admin/scheduler/tenants/:id` | 策略、当前并发/排队、配额用量（跳转配额页）、最近作业、拒绝原因分布 | 策略表 + 作业表 + `rag-quota` 只读接口 |

现有管理端结构可复用：`rag-frontend/src/views/admin/`（已有 `QuotaManagement.vue`、`UserManagement.vue` 等），路由在 `router/index.js#L39-L44`，管理页用 `meta.requiresAdmin` 守卫。

### 4.2 接口

统一前缀 `/api/admin/scheduler`，写操作加 `@Audit`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/policies` | 分页/过滤租户策略 |
| GET | `/policies/{tenantId}` | 单租户全部 lane 策略 |
| PUT | `/policies/{tenantId}` | 更新（body 含 `version`）；返回新版本 |
| POST | `/policies/{tenantId}/dry-run` | 试算，不落库 |
| GET | `/jobs` | 分页查询作业（lane/status/tenant/type/时间） |
| GET | `/jobs/{id}` | 作业详情（payload 脱敏可选） |
| POST | `/jobs/{id}/retry` | 重试（重置 PENDING、attempt+1） |
| POST | `/jobs/{id}/cancel` | 取消（PENDING→CANCELLED；RUNNING 走协作取消） |
| POST | `/jobs/{id}/reclaim` | 强制回收（管理员确认租约丢失） |
| GET | `/runtime` | 当前实例运行时快照（lane 运行/排队/拒绝） |
| GET | `/tenants/{tenantId}/overview` | 单租户聚合 |
| GET | `/metrics/summary` | 供仪表盘的趋势/汇总 |

租户侧可选：`GET /api/tenant/scheduler/my`（租户管理员查看本租户并发与配额），权限 `scheduler:view` + 租户范围校验。

### 4.3 指标与埋点

| 指标 | 类型 | 标签 | 用途 |
|---|---|---|---|
| `wgh.scheduler.running` | Gauge | `lane`, `tenant` | 并发水位 |
| `wgh.scheduler.queued` | Gauge | `lane`, `tenant` | 排队深度 |
| `wgh.scheduler.wait.seconds` | Timer | `lane`, `tenant` | 排队时长 P50/P95 |
| `wgh.scheduler.rejected` | Counter | `lane`, `tenant`, `reason` | 拒绝原因分布 |
| `wgh.scheduler.job.duration` | Timer | `jobType`, `outcome` | 作业耗时与成功率 |
| `wgh.scheduler.lease.expired` | Counter | `lane` | 租约过期（进程异常） |
| `wgh.scheduler.slot.drift` | Gauge | — | 槽位与实际运行数偏差 |
| `wgh.scheduler.policy.load.failed` | Counter | — | 策略加载失败 |

现有指标入口已具备：`management.endpoints.web.exposure.include=health,info,metrics,prometheus`（`application.yml#L239-L249`），管理端口独立（`WGH_MANAGEMENT_PORT`，默认 9090）。

### 4.4 权限与审计

- 新增权限：
  - `scheduler:view`：查看总览、策略、作业、租户视图；
  - `scheduler:manage`：修改策略、重试/取消/回收作业。
- 复用 `@RequiresPermission`（`AuthConstants.java#L10-L27`）与 `@Audit`（`AuditLogService`）。
- 审计内容：操作人、租户、动作、before/after（策略）、作业 ID、结果、IP。
- 敏感数据：作业 `payload_json` 可能含用户输入；列表接口只返回摘要，详情接口需 `scheduler:manage`，并记录查看审计。
- 平台管理员与租户管理员边界：租户管理员只能看/改本租户策略（阶段 1 引入 `TenantContext` 后强制过滤）；平台管理员可跨租户并审计。

---

## 5. 表结构、配置与模块

### 5.1 新增表（建议迁移脚本，本次不执行）

```sql
-- 后台作业（持久化 + 租约）
CREATE TABLE IF NOT EXISTS wgh_execution_job (
  id                BIGSERIAL PRIMARY KEY,
  job_type          VARCHAR(64)  NOT NULL,
  lane              VARCHAR(16)  NOT NULL DEFAULT 'BACKGROUND',
  tenant_id         BIGINT       NOT NULL DEFAULT 0,
  owner_user_id     BIGINT,
  biz_key           VARCHAR(200),
  payload_json      JSONB        NOT NULL DEFAULT '{}',
  policy_snapshot_json JSONB,
  priority          INT          NOT NULL DEFAULT 100,
  status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  scheduled_at      TIMESTAMP    NOT NULL DEFAULT now(),
  not_before        TIMESTAMP,
  lease_owner       VARCHAR(64),
  lease_expire_time TIMESTAMP,
  heartbeat_time    TIMESTAMP,
  attempt           INT          NOT NULL DEFAULT 0,
  max_attempts      INT          NOT NULL DEFAULT 3,
  timeout_seconds   INT,
  error_code        VARCHAR(64),
  error_message     TEXT,
  result_json       JSONB,
  finish_time       TIMESTAMP,
  version           INT          NOT NULL DEFAULT 0,
  create_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  update_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  create_by         VARCHAR(50)  DEFAULT 'system',
  update_by         VARCHAR(50)  DEFAULT 'system',
  deleted           SMALLINT     DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_exec_job_biz
  ON wgh_execution_job(job_type, biz_key)
  WHERE biz_key IS NOT NULL AND deleted = 0;
CREATE INDEX IF NOT EXISTS idx_exec_job_claim
  ON wgh_execution_job(lane, status, scheduled_at)
  WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_exec_job_tenant
  ON wgh_execution_job(tenant_id, status)
  WHERE deleted = 0;

-- 租户调度策略
CREATE TABLE IF NOT EXISTS wgh_tenant_schedule_policy (
  id                BIGSERIAL PRIMARY KEY,
  tenant_id         BIGINT      NOT NULL DEFAULT 0,
  lane              VARCHAR(16) NOT NULL,
  enabled           SMALLINT    NOT NULL DEFAULT 1,
  max_concurrent    INT         NOT NULL DEFAULT 2,
  max_queue         INT         NOT NULL DEFAULT 20,
  weight            INT         NOT NULL DEFAULT 10,
  priority_boost    INT         NOT NULL DEFAULT 0,
  reject_policy     VARCHAR(16) NOT NULL DEFAULT 'REJECT',
  queue_timeout_seconds INT     NOT NULL DEFAULT 300,
  daily_job_limit   INT         NOT NULL DEFAULT 0,
  rate_per_minute   INT         NOT NULL DEFAULT 0,
  time_window_json  JSONB,
  remark            VARCHAR(500),
  version           INT         NOT NULL DEFAULT 0,
  create_time       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  update_time       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  create_by         VARCHAR(50) DEFAULT 'system',
  update_by         VARCHAR(50) DEFAULT 'system',
  deleted           SMALLINT    DEFAULT 0,
  CONSTRAINT uk_tenant_lane UNIQUE (tenant_id, lane)
);

-- 集群级租户运行槽位
CREATE TABLE IF NOT EXISTS wgh_tenant_runtime_slot (
  tenant_id   BIGINT      NOT NULL,
  lane        VARCHAR(16) NOT NULL,
  running     INT         NOT NULL DEFAULT 0,
  update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, lane)
);
```

### 5.2 配置项

```yaml
wgh:
  scheduler:
    enabled: true
    instance-id: ${WGH_INSTANCE_ID:${HOSTNAME:local}}
    lease-ttl: 60s
    heartbeat-interval: 10s
    claim-interval: 2s
    policy-refresh-interval: 30s
    lanes:
      interactive:
        core-size: 4
        max-size: 16
        queue-capacity: 100
        thread-name-prefix: sched-interactive-
      background:
        core-size: 2
        max-size: 8
        queue-capacity: 500
        thread-name-prefix: sched-background-
      maintenance:
        core-size: 1
        max-size: 2
        queue-capacity: 50
        thread-name-prefix: sched-maintenance-
    default-policy:
      interactive: { max-concurrent: 2, max-queue: 20, weight: 10, reject-policy: REJECT }
      background:  { max-concurrent: 2, max-queue: 200, weight: 10, reject-policy: QUEUE }
    anonymous-tenant-id: 0
```

配套：
- `server.shutdown: graceful`、`spring.lifecycle.timeout-per-shutdown-phase: 45s`。
- 保留旧 `workflowExecutor` Bean 作为过渡，S1 后由 `schedulerBackground` 替代。

### 5.3 模块改造

| 模块 | 改造 |
|---|---|
| **新增 `rag-scheduler`** | `ExecutionJob`/`TenantSchedulePolicy`/`TenantRuntimeSlot` 实体与 Mapper；`JobSubmitter`、`JobDispatcher`（claim/心跳/回收）、`JobHandler` SPI、`AdmissionController`、`PolicyCache`、`FairQueue`、`SchedulerRuntimeRegistry` |
| `rag-app` | 注册 lane 线程池、调度器、优雅停机协调器；配置项 |
| `rag-kb` | 评测改为提交 BACKGROUND 作业（新增 `EvalJobPayload`/`EvalJobHandler`）；连接器同步改为 MAINTENANCE 作业；文件处理迁移到 BACKGROUND；上传清理迁移到 MAINTENANCE |
| `rag-workflow` | 静默/CRON/Webhook 改走 `JobSubmitter`；聊天工作流 S0 使用独立 `workflowInteractive` 池，S2 改后台作业 + attach；修复“先插 RUNNING 再提交”的失败边界 |
| `rag-agent-multi` | 去掉 `new Thread` 与 per-run 线程池，改用 `INTERACTIVE` lane 的共享池（S2 可转 BACKGROUND） |
| `rag-chat` | 聊天 ReAct 从裸线程改为 `INTERACTIVE` lane；准入在 quota 之前；SSE 事件保持 |
| `rag-quota` | 保持不变；阶段 2 增加租户维度（文档 §3.7），调度器只读 `tenant_id` |
| `rag-log` | 策略变更、作业重试/取消、租户视图查看写审计 |
| `rag-controller` | 新增 `SchedulerAdminController`、租户侧只读接口、权限常量 |
| `rag-frontend` | 新增 5 个页面与路由，复用管理端布局与权限守卫 |

---

## 6. 分阶段落地路径

### S0 止血（对应多租户阶段 0/1 之间）

**改动**
1. 聊天 ReAct、多 Agent 去掉裸 `new Thread`，改用受管 `INTERACTIVE` 池（有界队列 + `AbortPolicy` + 明确的 429/503）。
2. 工作流拆分 `workflowInteractiveExecutor` 与 `workflowBackgroundExecutor`，避免静默任务挤占交互任务。
3. 修复“先插 RUNNING 再提交”的边界：提交失败立即把 run/plan 置 FAILED(`SCHEDULER_REJECTED`)；或先尝试占位再插库。
4. CRON 触发与连接器同步加 `pg_try_advisory_lock` 单飞。
5. 指标：所有池注册 Micrometer（活跃/队列/拒绝/耗时）；新增运行时快照接口（只读）。
6. 优雅停机：`server.shutdown=graceful` + drain 协调器（见上一份文档 §2.9）。
7. 上下文：调度任务显式携带 `tenantId`/`userId`（先允许为空，落 `tenant_id=0`）。

**验收**
- 并发 500 聊天不再无界建线程；超过上限返回 429/503 而非 OOM。
- 工作流池满时 run 不再停在 RUNNING。
- 两实例部署时 CRON 只触发一次、连接器只同步一次。
- SIGTERM 后在途任务有明确终态或可恢复标记。

**风险**：引入拒绝会改变现有“无限排队”的行为，需要前端展示 429；需要压测确认池大小。

### S1 后台作业化（对应多租户阶段 1）

**改动**
1. `wgh_execution_job` + 租约 claim + 心跳 + 重试 + 死信。
2. 迁移评测、工作流静默/CRON/Webhook、连接器同步、文件处理、上传清理到 BACKGROUND/MAINTENANCE lane。
3. `wgh_tenant_schedule_policy` + 默认策略；管理端只读（总览、作业列表、失败列表）。
4. `rag-quota` 与准入顺序调整（admission → quota）。
5. 作业提交接口：`POST /api/eval/...` 返回 `202 + jobId`；前端改为轮询。

**验收**
- 评测不再占用 HTTP 线程与长事务；提交后立即返回 jobId，可在管理端看到 PENDING/RUNNING/SUCCEEDED。
- 作业进程崩溃后按上一份文档的租约回收，≤90s 离开 RUNNING。
- 单实例重启后后台作业继续领取，无重复执行。

**风险**：评测接口契约变化（同步→异步），需要前端与调用方适配；作业 payload 需要版本化。

### S2 租户策略与可视化写操作（对应多租户阶段 2）

**改动**
1. 策略完整生效：并发、排队、权重、优先级、时间窗、日/分钟配额、拒绝策略。
2. `wgh_tenant_runtime_slot` 集群占槽；后台 lane 加权公平调度。
3. 管理端策略编辑、试算、单租户视图、作业重试/取消/回收；权限与审计。
4. 交互式准入控制器读取策略缓存；`tenant_id=0` 默认桶与匿名低配额。
5. 多 Agent / 聊天工作流可演进为“后台作业 + SSE attach”（事件缓冲）。

**验收**
- 一个租户提交 100 个后台作业，不影响其他租户的交互式请求（P95 延迟不劣化）。
- 策略修改 60s 内全实例生效；无实例重启。
- 策略变更、作业操作全部有审计。
- 管理端能看到每租户并发、排队、拒绝原因。

**风险**：公平调度实现错误会导致饥饿；需要 dry-run 与灰度；集群槽位需要定期对账。

### S3 集群语义与重放（对应多租户阶段 3）

**改动**
1. 交互式 lane 的集群级并发（DB slot 或 Redis token bucket）。
2. `wgh_execution_event` 事件表 + SSE `Last-Event-ID` 重放。
3. 死信队列管理页、租户级用量/计费视图。
4. RLS 与应用角色接入后，调度器连接使用非超级用户（文档 G-15）。

**验收**：多副本下租户并发精确；断线重连可补齐事件；死信可批量处置。

### 不做的代价

| 不做 | 代价 |
|---|---|
| 不做受管池 | 并发聊天/多 Agent 无上限建线程；一次流量峰值可能 OOM；无法统计与排水 |
| 不做后台作业化 | 评测长期占用请求线程与 DB 连接；发布重启丢任务；无法重试 |
| 不做集群单飞 | N 副本 N 倍触发工作流/抓取连接器，重复消耗模型与存储 |
| 不做租户公平 | 一个租户可占满 8 个执行槽与 100 个队列位；其他租户 429 |
| 不做可视化 | 无法回答“谁在占容量”“为什么被拒绝”“任务卡在哪”，只能靠日志与用户投诉 |

量化（估算，非实测）：
- 工作流池 8 并发 × 平均 5 分钟，队列 100 → 最坏排队 500 分钟（8.3 小时）；第 101 个直接拒绝。
- 评测 1 次可能占用 1 个 Hikari 连接数分钟；开发库 20 连接下，5 个并发评测即可耗尽连接池。
- 8 个并发工作流 = 8 个 dispatcher + 32 个 worker = 40 个线程；再叠加 Crew 8×8=64 与 AGENT 节点线程，线程数可达数百，全部共享 20/50 个 DB 连接。
- 两副本 + 10 个 CRON 触发器 = 每小时 20 次触发（应为 10 次），模型成本翻倍。

---

## 7. 验收标准与测试场景

### 7.1 验收标准

| 编号 | 标准 |
|---|---|
| A1 | 所有异步执行体都能在线程名、指标、管理端看到；无裸 `new Thread` 承载业务执行 |
| A2 | 任何池满都不会留下 RUNNING 悬挂记录；被拒绝的请求有 429/503 与明确错误码 |
| A3 | 后台作业在进程崩溃后 ≤90s 离开 RUNNING（复用上一份文档的租约回收） |
| A4 | 两实例部署，同一 CRON 只触发一次、同一连接器只同步一次、同一作业只执行一次 |
| A5 | 单租户并发/排队达到策略上限时，仅该租户被拒绝，其他租户 P95 不受影响 |
| A6 | 策略变更 60s 内全实例生效，且已提交作业按提交时快照执行 |
| A7 | 评测提交后立即返回 jobId，HTTP 线程占用 <100ms，且不持有长事务 |
| A8 | 管理端可完成：查看总览、编辑策略、查看作业、重试/取消、查看单租户 |
| A9 | 策略与作业操作 100% 审计，权限不足返回 403 |
| A10 | 正常聊天首字延迟与改造前相比不劣化（阈值需压测确认） |

### 7.2 测试场景

| 编号 | 场景 | 期望 |
|---|---|---|
| T1 | 500 并发聊天请求 | 不超过 interactive 上限；超出返回 429/503；无 OOM |
| T2 | 8 个长工作流 + 1 个聊天工作流 | 聊天工作流不被后台任务阻塞（独立池） |
| T3 | 池满时提交工作流 | run 立即 FAILED(`SCHEDULER_REJECTED`)，不悬挂 |
| T4 | 两副本同时到 CRON 时间 | 只产生一个 run（advisory lock） |
| T5 | 评测 100 条用例 | 返回 jobId；后台执行；DB 连接不在请求期长期占用 |
| T6 | 作业执行中 kill -9 | ≤90s 被回收/重试；attempt 递增 |
| T7 | 租户 A 提交 100 个后台作业，租户 B 聊天 | B 的延迟与拒绝率不受影响；A 按权重排队 |
| T8 | 修改租户并发 2→1 | 60s 内新请求按 1 执行；已在跑的 2 个不被中断 |
| T9 | 策略缓存清空 + DB 正常 | 自动回源，无请求失败 |
| T10 | 策略缓存清空 + DB 不可用 | 使用 yml 默认值；记录 `policy.load.failed` |
| T11 | 两副本后台作业 claim | 只有一个实例执行；另一个 0 行 |
| T12 | 管理端越权 | 租户管理员只能看本租户；跨租户 403 |
| T13 | 优雅停机 | 新请求被拒；在途任务完成或标记；无悬挂 |
| T14 | 槽位计数漂移 | 对账任务修正；`slot.drift` 告警 |

---

## 8. 未验证与推断声明

1. **未做运行压测**：文中线程数、排队时长、连接池耗尽是公式估算，不是实测。
2. **E15 审计线程池参数**：仓库没有自定义 `TaskExecutor`，按 Spring Boot 3.4 默认（core=8，max/queue 近无界）推断；未通过运行时确认。
3. **生产副本数未知**：仓库只有单实例 `docker-compose.yml`；“多实例重复触发”是代码层面成立，未在生产验证。
4. **`wgh_user.tenant_id` 未启用**：来自多租户文档 G-14；未核对全部写入路径。
5. **工具调用并发**：确认 `StepExecutor` 内工具调用是串行（`for` 循环），未穷举所有工具执行器的内部线程；MCP/HTTP 客户端自身的连接池未逐一核对。
6. **评测是否被其他入口调用**：只确认 `EvalDatasetService.evaluate` 是同步链路；未穷举所有调用方。
7. **前端管理端布局复用**：只核对了路由与现有页面结构，未核对组件库限制。
8. **配额顺序调整的影响**：`checkAndIncrementChat` 在 `RagChatService#L149`，改为“准入→配额”会改变现有计数语义，需要产品确认是否允许拒绝前不计数。

---

## 附录 A：证据索引

| 结论 | 证据 |
|---|---|
| 聊天每请求裸线程 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L197-L221` |
| 聊天 SSE 30min | `rag-chat/.../constant/ChatConstants.java#L37`、`RagChatService.java#L165` |
| 聊天配额先计数 | `RagChatService.java#L149` |
| Agent SSE 发送失败仍继续 | `RagChatService.java#L421-L429` |
| 多 Agent 每 run 裸线程 | `rag-controller/.../MultiAgentController.java#L86-L99` |
| 多 Agent SSE 10min | `MultiAgentController.java#L86` |
| Crew 每 run 4/8 池 | `rag-agent-multi/.../crew/CrewCoordinator.java#L49-L50`、`#L273-L312` |
| 工作流池 2/8/100 | `rag-workflow/.../config/WorkflowExecutorConfig.java#L15-L28` |
| 工作流触发池 2 | `WorkflowExecutorConfig.java#L31-L38` |
| 工作流先插 RUNNING 后提交 | `rag-workflow/.../service/WorkflowService.java#L327-L353`、`#L409`、`#L423-L444` |
| 工作流引擎每 run 4 worker | `rag-workflow/.../engine/WorkflowEngine.java#L45-L47`、`#L460-L517` |
| AGENT 节点单线程池 | `rag-workflow/.../engine/executor/AgentNodeExecutor.java#L78-L95` |
| CRON 每实例加载 | `rag-workflow/.../service/WorkflowTriggerService.java#L51-L63`、`#L157-L171` |
| 连接器每实例调度 | `rag-kb/.../connector/ConnectorScheduler.java#L34-L49`、`#L59-L81` |
| 上传清理每实例 | `rag-kb/.../service/UploadSessionCleaner.java#L40-L55` |
| 文件处理 4/8/50 CallerRuns | `rag-kb/.../service/KnowledgeFileService.java#L61-L73` |
| 查询变换 cached pool | `rag-kb/.../service/QueryTransformService.java#L54-L62`、`#L108-L110` |
| 压缩静态 2 线程 | `rag-chat/.../compression/AbstractLlmCompressionHandler.java#L26-L41` |
| Judge 默认 4 | `rag-chat/.../evaluation/LlmJudgeService.java#L58-L73` |
| 评测同步 + 长事务 | `rag-kb/.../service/EvalDatasetService.java#L181-L205`、`RetrievalEvaluationService.java#L170-L181` |
| 审计 @Async | `rag-log/.../service/AuditLogService.java#L25-L32`、`rag-app/.../RagApplication.java#L18` |
| 工具串行 + 预算 | `rag-tool/.../fallback/ToolInvoker.java#L83-L111`、`ToolTaskBudget.java#L30-L44`、`ToolFallbackProperties.java#L38-L42` |
| MDC 只传 requestId | `rag-common/.../log/MdcContext.java#L19-L50` |
| 异步配额回落 anonymous | `rag-quota/.../service/DefaultQuotaUserResolver.java#L17-L24` |
| 多租户阶段划分 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L31-L39`、`#L545-L620` |
| 租户模型与上下文 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L493-L508` |
| 配额现状 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L294-L311` |
| 隔离薄弱点 G-06/G-10/G-14 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L390-L410` |
| 现有管理端路由 | `rag-frontend/src/router/index.js#L39-L44` |
| 现有权限常量 | `rag-backend/rag-auth/.../constant/AuthConstants.java#L10-L27` |
| 指标暴露 | `rag-app/src/main/resources/application.yml#L239-L249` |
| DB 连接池 | `application.yml#L19-L21`、`application-prod.yml#L23-L25` |
