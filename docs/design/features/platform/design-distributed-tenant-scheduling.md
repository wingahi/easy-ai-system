# 多实例集群下的租户隔离与多租户调度中心设计

> **版本**：v1.0
> **日期**：2026-11-18
> **适用版本**：当前 `main` 工作区（Spring Boot 3.4.5 / PostgreSQL / Redis / 多模块 Maven）
> **关联文档**：
> - `docs/review/multi-tenancy-analysis-2026-11-18.md`（租户模型与阶段 0-4）
> - `docs/design/features/agent/design-react-task-recovery.md`（租约、心跳、回收、恢复边界）
> - `docs/design/features/platform/design-unified-scheduler-and-tenant-policy.md`（lane、策略、作业表、可视化）
> - `docs/design/features/platform/design-execution-scheduler-center.md`（2026-11-19 统一规划：A–D 排期不变，补充单 Agent 与计划任务）
> - `docs/design/features/platform/design-kubernetes-orchestration.md`（2026-11-19 K8s 编排规划，多副本前置条件引自本文）
> **分析方式**：源码逐文件核对；未修改代码、未执行 DDL、未操作数据库、未重启服务
> **未验证/推断项**：见 §7

---

## 0. 结论摘要

1. **两处现状核实结果**：
   - **配额计数在 PostgreSQL，不在 Redis**：`wgh_quota_usage`，唯一键 `(username, date_key)`，通过 `UPDATE ... + INSERT` 原子累加；多实例共享同一份计数。但“先查后加”不是原子的，存在跨实例超发窗口（§1.1）。
   - **限流计数在 Redis（不可用时降级进程内存）**：key 形如 `wgh:pa:rl:{dimension}:{path}:{subject}`，**没有租户前缀**；Redis 正常时多实例共享，Redis 故障时各实例独立计数（代码注释已声明该限制），且 `distribute()` 会返回 false（§1.2）。
   - **语义缓存在 PostgreSQL + pgvector，不在 Redis**：`wgh_semantic_cache` 只按 `window_id` 隔离，向量 metadata 只写 `windowId`；没有 `tenant_id` 或用户维度。多实例共享同一 DB 不会“串实例”，但一旦窗口归属校验缺失（多租户文档 G-01/G-04），就会跨租户读到缓存答案（§1.3）。
2. **五类多实例问题中，只有三类是“扩容前必须”**：执行者标识与租约、定时任务单飞、`TenantContext` 跨线程传递。进程内状态外置（黑板/熔断器/游标）与 SSE 重放可以随恢复能力与集群化逐步做；本地磁盘在多副本下必须换成共享存储（当前 compose 甚至没有给 backend 挂卷，重启即丢，§2.4）。
3. **多租户调度中心当前应做成“内嵌在应用里的策略组件”，而不是独立调度服务**。理由是：作业量级未知、单体部署、DB 已是唯一事实源、独立服务会引入新的运维与网络故障域。升级为独立服务的触发条件见 §4.4（副本数、异构 worker、跨语言、作业量、独立扩缩容需求）。
4. **全局口径必须落在数据库**：后台并发用 `wgh_tenant_runtime_slot` 原子占槽，作业领取用 `FOR UPDATE SKIP LOCKED` + 租约；交互式在单副本阶段用实例内信号量，扩容前必须切到集群口径，否则“单租户 2 并发”会变成“每副本 2 并发”，策略失真。
5. **`TenantContext` 不能只靠 ThreadLocal**：`MdcContext` 现在只复制 `requestId`（`MdcContext.java#L19-L50`），异步线程里用户/租户全部丢失。正确做法是“提交时捕获不可变请求上下文 + 执行前设置 + finally 清理”，跨线程池用 `TaskDecorator`，作业用 payload 携带 `tenant_id`，消息队列（未来）用消息头。
6. **合并落地顺序**：A（多租户阶段 0 + 调度 S0 + 恢复 L0）→ B（阶段 1 + S1 + 恢复 L1）→ C（阶段 2 + S2）→ D（阶段 3 + S3）。扩容到 2 副本的最低门槛是 A 完成；B 完成前不应开启跨租户运营。

---

## 1. 两处现状核实

### 1.1 配额计数：PostgreSQL，多实例共享，但存在 TOCTOU

**存储与 key**

- 表：`wgh_quota_usage`（`sql/schema/01-schema.sql#L964-L981`），唯一约束 `uk_quota_user_date(username, date_key)`。
- 读取/累加：`QuotaService.java#L94-L132`，通过 `QuotaUsageMapper.upsertIncrement` 完成。
- Mapper 实现：`QuotaUsageMapper.java#L20-L55`，先 `UPDATE ... SET chat_count = chat_count + ?`，无行则 `INSERT`，并发首插冲突后回退 `UPDATE`。
- 没有 Redis 参与，也没有本地计数镜像。

**多实例是否共享**

- 是。所有实例连同一个 PostgreSQL，`UPDATE` 的行锁保证计数不丢。
- 但 `checkAndIncrementChat` 是“先 `usageOf` 判断是否超限，再 `upsertIncrement`”（`QuotaService.java#L99-L115`），两步之间没有原子条件。多实例并发时，多个请求可以同时读到 `chat_count = limit-1`，然后都成功加一，造成**少量超发**。单实例同样存在该竞态，只是窗口更窄。
- token 计数在响应结束后累加（`RagChatService.java#L1098`），属于事后计量，不参与事前准入。

**结论**：配额“计数共享”成立，“限流正确性”不成立。改造方向是把“判断 + 自增”合并成一条带条件的原子 SQL（§3.4）。

### 1.2 限流计数：Redis 优先，内存兜底，无租户前缀

- 实现：`DefaultPublicAccessStore`（`rag-public-access/.../DefaultPublicAccessStore.java`）。
- 滑动窗口与自增都用 Redis Lua 原子执行（`#L28-L50`），Redis 失败后 10 秒内不再重试并降级到进程内 `ConcurrentHashMap`（`#L52-L58`、`#L207-L225`）。
- 类注释明确写明：“降级期间不同实例各自计数，属于已知限制”（`#L20-L21`）。
- key 构造：`PublicAccessRateLimiter.buildKey`（`#L71-L73`）：
  ```
  wgh:pa:rl:{dimension}:{path}:{subject}
  ```
  `dimension` 为 `share-token` / `share-ip` / `api-key` / `api-ip`，`subject` 为 token、IP、API Key ID。**没有租户、没有用户、没有实例**。
- 其他 Redis key：分享密码失败计数 `wgh:pa:pwd:`（`SharePasswordGuard.java#L26`）、嵌入授权 `wgh:pa:embed:grant:`（`EmbedAccessService.java#L26`），同样没有租户前缀。
- 部署上 Redis 默认开启：`RagRedisConfig.java#L22-L54`（`matchIfMissing=true`），compose 显式 `WGH_REDIS_ENABLED: "true"`（`docker-compose.yml#L65-L67`）。

**多实例是否共享**

- Redis 正常时：共享，计数全局一致。
- Redis 不可用/未启用时：**各实例独立计数**，实际限流上限 = 配置值 × 实例数（或按流量分布失真）。这属于“保护性限流可接受、租户配额不可接受”的降级。
- 因为 key 没有租户前缀，同一个 share token/API Key 跨实例共享计数是正确的；但无法按租户聚合，也无法做“租户级限流”。

### 1.3 语义缓存：PostgreSQL + pgvector，按窗口隔离，无租户维度

- 存储：`wgh_semantic_cache`（`sql/schema/01-schema.sql#L1021-L1039`），字段只有 `window_id`、`query_text`、`answer_text`、`embedding_id`、`hit_count`。
- 向量：固定表 `wgh_semantic_cache_vec`（`SemanticCacheService.java#L41-L42`），metadata 只写 `windowId`（`#L136-L138`）。
- 查询过滤：`new IsEqualTo("windowId", String.valueOf(windowId))`（`#L76-L80`），再按 `window_id + embedding_id` 查行（`#L90-L96`）。
- 失效：按窗口删除（`#L161-L171`）、按知识库找到窗口后删除（`#L177-L198`）；底层 `selectWindowKbMappings` 是全表扫描 `wgh_chat_window`（`SemanticCacheMapper.java#L20-L22`）。
- 清理：每个实例在写入节奏触发时执行（`#L223-L232`），没有单飞；重复清理是幂等的，但会产生多余 DB/向量操作。

**多实例是否串**

- 实例之间不会串：DB 与 pgvector 都是共享的，`windowId` 过滤一致。
- **跨用户/跨租户会串**：隔离强度完全等价于“窗口访问控制”。当前窗口与运行态接口存在归属校验缺口（多租户文档 G-01、G-04、G-08），且 `window_id` 是自增 ID，可枚举。多租户阶段 2 必须给缓存加 `tenant_id` 并同时过滤 `tenant_id + window_id`，向量 metadata 也要加 `tenantId`。
- 多实例下的另一个风险：知识库变更触发 `invalidateByKbId` 时，每个实例可能同时执行（若由请求触发），产生重复删除；不致命。

### 1.4 现状核实小结

| 问题 | 结论 | 多实例共享 | 主要缺口 |
|---|---|---|---|
| 配额计数 | PostgreSQL `wgh_quota_usage` | 是 | 检查与自增非原子；无 `tenant_id` |
| 公共限流 | Redis Lua + 内存兜底 | Redis 正常时是；降级时否 | 无租户前缀；降级语义需明确 |
| 语义缓存 | PostgreSQL + pgvector | 是 | 只有 `window_id`，无 `tenant_id`；依赖窗口鉴权 |
| 分享密码/嵌入授权 | Redis + 内存兜底 | 同上 | 无租户命名空间 |

---

## 2. 五类多实例问题的解法

### 2.0 先给结论：当前部署形态下哪些不必解决

当前 `docker-compose.yml` 只有 1 个 backend 容器（`#L46-L82`），没有 `deploy.replicas`。在**保持单副本**的前提下：

- 不必解决：熔断器跨实例共享、黑板跨实例共享、SSE 事件重放、跨实例取消、定时任务单飞（单副本天然单飞）。
- 仍必须解决：进程崩溃后的状态恢复（上一份文档）、本地磁盘持久化（当前 backend 没有挂卷，容器重建即丢文件，`#L46-L82` 无 `volumes`）、优雅停机。
- 一旦扩到 2 副本，以下立刻变成必须：执行者标识与租约、定时任务单飞、`TenantContext` 跨线程、后台并发全局口径、本地磁盘换共享存储。

### 2.1 进程内状态外置

| 状态 | 现状与代码依据 | 目标形态 | 改动模块 | 可渐进性 | 成本 |
|---|---|---|---|---|---|
| 工具熔断器 | `ToolCircuitBreaker.states` 为 `ConcurrentHashMap`，类注释明确“仅在单实例内存中生效，不跨节点共享”（`ToolCircuitBreaker.java#L16-L23`） | 方案 A：保持实例内（安全阀，允许各实例独立开合）；方案 B：Redis Hash/ZSet 共享失败计数与开合状态 | `rag-tool` | **可保持现状**；只有“同一工具在某实例已熔断、另一实例仍打满”不可接受时才需要外置 | S |
| 多 Agent 黑板 | `MultiAgentContext.blackboard = new InMemoryBlackboard()`（`MultiAgentContext.java#L39`），实现为进程内 Map（`InMemoryBlackboard.java#L20`） | 持久化到 `wgh_m_agent_run.context_snapshot_json` 或 Redis Hash（按 runId） | `rag-agent-multi` | 与“运行恢复”绑定；不恢复可暂不做。跨实例读取 `run state` 目前只读 DB，不读黑板，所以不会串 | M |
| 聊天取消标记 | `ChatStreamCancellation` 是**请求内对象**（`RagChatService.java#L167`），不是全局 Map；取消来源是 SSE 断连/onError/onTimeout（`#L168-L180`） | 不需要外置；若要支持“显式停止”，改为 DB 状态 + 执行线程轮询（协作式取消） | `rag-chat` | 当前没有聊天停止 API，跨实例取消问题不存在；Agent 取消无效问题见第一份文档 | S |
| HITL 等待上下文 | 已经是数据库状态：`HITL_PENDING` + `PAUSED`（`HitlGate.java#L41-L54`），恢复从 DB 读取 | 保持 DB；补执行者租约与 CAS，避免两个实例同时确认 | `rag-agent` | **已经外置**，只差互斥 | S |
| 工作流游标 | `frontier`/`pendingIn`/`submitted` 是 `executeLoop` 局部变量（`WorkflowEngine.java#L446-L458`） | 把游标写入 `context_json`（`frontier/pendingIn`），恢复时重建 | `rag-workflow` | 与恢复能力绑定；单副本也必须做（重启恢复） | M |
| SSE emitter 注册表 | `WorkflowService.emitters` 是进程内 `ConcurrentHashMap`（`WorkflowService.java#L61-L62`、`#L693-L707`） | 事件写入 `wgh_execution_event`，SSE 通过 `Last-Event-ID` 重放；或前端只轮询状态 | `rag-workflow`、`rag-chat` | 可延后；先保证“状态可查” | M-L |

**判断**：这类问题的共同点是“只影响跨实例的一致性，不影响单实例正确性”。除了 HITL 互斥与工作流游标（与恢复强相关），其余可以等到真正多副本时再做。

### 2.2 定时任务单飞

| 任务 | 现状 | 多副本后果 | 目标形态 | 成本 |
|---|---|---|---|---|
| CRON 工作流触发 | 每个实例 `init` 加载全部启用触发器并在本地 `schedule`（`WorkflowTriggerService.java#L51-L63`、`#L157-L171`）；`fire` 无原子抢占（`#L142-L151`） | N 副本 N 倍触发，重复扣模型/写数据 | 方案 A：`pg_try_advisory_lock(hashtext('wgh:cron:'||triggerId))`；方案 B：把“到期触发器”做成后台作业，由作业 claim 保证单飞 | S |
| URL 连接器同步 | 每实例 `@PostConstruct` 启动 1 线程 fixed-delay（`ConnectorScheduler.java#L34-L49`），`tick` 内同步执行（`#L59-L81`） | 重复抓取、重复入库 | advisory lock 按 connectorId 单飞，或迁移到 MAINTENANCE lane | S |
| 上传会话清理 | 每实例 1 线程（`UploadSessionCleaner.java#L40-L55`） | 重复删除（幂等但浪费） | advisory lock 单飞 | S |
| 语义缓存过期清理 | 每实例写入节奏触发（`SemanticCacheService.java#L223-L232`） | 重复清理（幂等） | 可保持；或纳入 MAINTENANCE lane | S |
| 审计异步 | `@Async`（`AuditLogService.java#L25-L32`） | 无重复问题 | 不需要单飞 | — |

**判断**：CRON 与连接器必须在扩副本前做单飞；上传清理与语义缓存清理可以容忍重复，但建议统一进 MAINTENANCE lane 以获得可观测性。

### 2.3 执行者标识与租约

- 现状：三套运行表都没有 `executor_id`/`lease_expire_time`/`heartbeat_time`/`version`，详见第一份文档 §1.1。
- 多副本后果：同一 plan/run 可能被两个实例同时执行（HITL 确认、重试、未来的回收器），且无法判断“谁在跑”。
- 目标形态：复用第一份文档 §2.4-§2.8 的字段、心跳（10s）、TTL（60s）、`FOR UPDATE SKIP LOCKED`、`version` CAS。
- 改动模块：`rag-agent`、`rag-workflow`、`rag-agent-multi`、新增 `rag-scheduler`。
- 成本：M（加列 + 心跳 + claim + 回收 + 接口改造）。
- 是否可渐进：**不可**。这是扩副本的前置条件。

### 2.4 本地磁盘依赖转共享存储

- 现状：
  - 默认 `wgh.rag.storage.type=local`（`StorageProperties.java#L19-L20`），根目录默认 `./uploads`（`#L27-L33`），实现为 `Paths.get(baseDir)` + `Files`（`LocalFileStorageService.java#L38-L47`）。
  - key 规则为 `kb/{kbId}/{fileUuid}/{fileName}`（`StorageKeys.java#L18-L24`），**没有租户前缀**。
  - `docker-compose.yml` 的 backend 没有声明 `volumes`（`#L46-L82`），本地文件在容器重建/迁移后丢失；多副本时 A 写入的文件 B 读不到。
- 目标形态：
  - 多副本必须用 `type=oss`（已支持，`AliyunOssFileStorageService`），或至少给所有副本挂同一共享卷（NFS/CephFS）。
  - key 改为 `t/{tenantId}/kb/{kbId}/...`（与多租户文档 §5.2 一致）。
  - 迁移旧对象：按 `kb_id -> tenant_id` 映射批量 copy，保留旧 key 读兼容期。
- 改动模块：`rag-storage`、`rag-kb`、部署配置。
- 成本：配置切换 S；存量迁移与双读兼容 M。
- 是否可渐进：单副本可暂不切换，但**必须挂持久卷**，否则重启即丢。

### 2.5 SSE 事件不能重放

- 现状：
  - 聊天事件直接写 `SseEmitter`（`RagChatService.java#L421-L429`），失败只记日志。
  - 工作流 emitter 存在进程内 Map（`WorkflowService.java#L61-L62`），刷新后只能查状态（`#L693-L707`）。
  - 多 Agent 直接写 emitter（`MultiAgentController.java#L91-L99`）。
  - 没有任何事件表或 `Last-Event-ID` 支持（第一份文档 §1.5）。
- 多副本后果：用户在实例 A 建立 SSE，后续请求（或重连）落到实例 B，无法附着到原连接；事件无法补发。
- 目标形态（按优先级）：
  1. **状态可查**（已有）：`GET /state` 类接口返回完整进度，前端轮询/重连后重建 UI。
  2. **事件表 + 重放**：`wgh_execution_event(run_type, run_id, seq, event_name, payload_json, create_time)`，SSE 支持 `Last-Event-ID`。
  3. **SSE 网关/粘性会话**：仅作为过渡，不作为唯一手段。
- 改动模块：`rag-common`（事件抽象）、`rag-agent`/`rag-workflow`/`rag-agent-multi`、`rag-controller`。
- 成本：状态可查 S；事件表与重放 M-L。
- 是否可渐进：可以。多副本初期用“轮询状态 + 断开重连重新订阅”即可接受；事件重放放到 S3。

### 2.6 五类问题汇总

| 问题 | 单副本是否必须 | 扩到 2 副本是否必须 | 推荐优先级 |
|---|---|---|---|
| 执行者标识与租约 | 是（恢复也需要） | 是 | P0 |
| 定时任务单飞 | 否 | 是 | P0（扩副本前） |
| `TenantContext` 跨线程 | 阶段 1 起必须 | 是 | P0（与租户阶段 1 同步） |
| 工作流游标外置 | 是（重启恢复） | 是 | P1 |
| 本地磁盘→共享存储 | 挂卷即可 | 是 | P1（扩副本前） |
| 黑板/熔断器外置 | 否 | 视一致性要求 | P2 |
| SSE 重放 | 否 | 否（可轮询兜底） | P3 |

---

## 3. 租户隔离在多实例下的正确姿势

### 3.1 `TenantContext` 的传递

**现状**：不存在 `TenantContext` 类（全仓库检索无命中）；`wgh_user.tenant_id` 是死字段（多租户文档 G-14）；`MdcContext` 只复制 `requestId`（`MdcContext.java#L19-L50`）；异步线程里 `create_by` 回落 `system`、配额回落 `anonymous`（G-06、`DefaultQuotaUserResolver.java#L17-L24`）。

**目标模型**（严格复用多租户文档 §5.2）：

```java
record RequestContext(Long tenantId, Long userId, String username, String requestId) {}
```

- Web 入口：过滤器解析令牌后设置 `TenantContext`（ThreadLocal）+ 写入 MDC。
- 异步边界：**提交时捕获** `RequestContext`，执行前设置、finally 清理。禁止在执行线程里重新从 ThreadLocal 读。
- 作业：`wgh_execution_job` 显式存 `tenant_id`/`owner_user_id`，worker 从 payload 恢复上下文。
- 消息队列（未来）：消息头带 `tenantId`/`userId`/`traceId`，消费者设置后清理。

**各链路的具体做法与取舍**

| 链路 | 现状 | 推荐方式 | 取舍 |
|---|---|---|---|
| 线程池 | `MdcContext.wrap` 只带 MDC（`MdcContext.java#L26-L37`） | 自定义 `TaskDecorator`：捕获 `RequestContext`，执行前 `TenantContext.set`，finally `clear` | 侵入小；但必须保证所有池都注册，漏一个就丢租户 |
| `@Async` | Boot 默认执行器（无自定义） | 自定义 `AsyncConfigurer`/`TaskExecutor` 并复用同一 `TaskDecorator` | 统一入口，避免每个 `@Async` 手写包装 |
| ReAct 流 | 与请求同线程执行（`RagChatService.java#L197-L221`） | 同线程天然有上下文；工具回调不得新开线程 | 最简单；未来若工具并行需显式传递 |
| 工作流线程 | 顶层提交用 `workflowExecutor`，节点 worker 是每 run 新建（`WorkflowEngine.java#L460-L464`） | 在 `RunContext` 增加 `tenantId/userId`，创建 worker 时用 `TaskDecorator` 或包装 Runnable | 显式携带最可靠；改动集中在引擎 |
| AGENT 节点线程 | 每节点新建单线程池（`AgentNodeExecutor.java#L78-L95`） | 同上，必须包装 | 容易漏，需统一工厂 |
| Crew 线程 | 每 run 新建 4-8 线程（`CrewCoordinator.java#L273-L277`） | 同上；建议统一 `SchedulerThreadFactory` 携带上下文 | 线程多，漏的概率高 |
| 定时任务 | 无请求上下文 | 任务本身携带 `tenantId`；维护任务用 `tenant_id=0` | 不能依赖 ThreadLocal |
| 后台作业 | 无 | payload + 表字段 | 最可靠，推荐作为长期方案 |
| MQ（未来） | 无 | 消息头 | 解耦最好，但需要消费端严格清理 |

**结论**：短期用“ThreadLocal + 显式包装”，长期以“任务对象/payload 携带”为准；ThreadLocal 只作为执行期缓存，不作为跨边界的唯一载体。

### 3.2 缓存 key、存储 key、向量检索、RLS

| 层 | 现状 | 多租户 + 多实例目标 |
|---|---|---|
| 语义缓存 | `wgh_semantic_cache` 只有 `window_id`（`01-schema.sql#L1021-L1039`）；向量 metadata 只有 `windowId`（`SemanticCacheService.java#L136-L138`） | 加 `tenant_id`；查询同时过滤 `tenant_id + window_id`；向量 metadata 写 `tenantId`；清理按租户 |
| 向量检索 | 每个 KB 独立物理表（由 KB 路由），`VectorStoreService` 缓存 `(tableName, dimension)`（`VectorStoreService.java#L60-L61`） | 只要 KB 归属校验正确，物理表天然隔离；若引入共享向量表，必须加 `tenantId` 过滤 |
| 本地/OSS 存储 | key `kb/{kbId}/...`（`StorageKeys.java#L18-L24`） | `t/{tenantId}/kb/{kbId}/...`；旧对象迁移 + 双读 |
| Redis 业务 key | 限流 `wgh:pa:rl:...`（`PublicAccessRateLimiter.java#L71-L73`）、密码 `wgh:pa:pwd:`、嵌入授权 `wgh:pa:embed:grant:` | 统一前缀 `wgh:t:{tenantId}:...`；限流 key 增加租户段 |
| 本地进程缓存 | `ModelCacheManager` 按模型维度全局缓存（平台级）；`VectorStoreService.storeCache` 按表名 | 平台级模型可保持全局；租户 BYOK 时 key 加租户；向量缓存按表名已隔离 |
| RLS | 未启用；应用用 `postgres` 超级用户（多租户文档 G-15） | 阶段 3：非 owner 角色 + `SET LOCAL app.tenant_id`；每个事务入口设置，事务结束自动失效，连接池复用安全 |

**多实例一致性**：以上都是“共享存储 + 显式租户条件”，不依赖实例本地状态。唯一需要注意的是**实例本地缓存**（策略、模型）必须有失效机制（§4.3）。

### 3.3 跨实例的租户级限流与配额

| 维度 | 推荐实现 | 原因 |
|---|---|---|
| 每日对话/ token 配额 | PostgreSQL 条件更新（见下） | 已有表；原子、可审计、跨实例一致 |
| 租户级请求速率 | Redis Lua（可用时）+ DB 固定窗口兜底 | Redis 原子且低延迟；降级时宁可放宽也不误杀 |
| 租户级并发 | `wgh_tenant_runtime_slot` 原子占槽 | 需要“运行中”语义，DB 最可靠 |
| 租户级排队 | `wgh_execution_job` 的 PENDING 计数 + 策略 | 作业本身就是持久化的 |
| 公网分享/API Key 限流 | 现有 Redis 滑动窗口 + 租户前缀 | 保护性限流，允许最终一致 |

**配额原子化 SQL（修复 TOCTOU）**：

```sql
-- 前提：行已存在（首次插入可单独处理）
UPDATE wgh_quota_usage
SET chat_count = chat_count + 1,
    update_time = now()
WHERE username = :username
  AND date_key = :periodKey
  AND deleted = 0
  AND (:chatLimit <= 0 OR chat_count < :chatLimit)
RETURNING chat_count;
```

- 返回 0 行 = 已超限，直接 429；不再先 `SELECT`。
- `token_used` 同理，在 `addTokens` 中做无条件累加即可（事后计量）。
- 租户级配额：`wgh_quota_usage` 增加 `tenant_id`，唯一键改为 `(tenant_id, subject_type, subject_id, date_key)`；聚合查询按租户。
- 这套 SQL 在单实例/多实例语义一致，不依赖 Redis。

**限流降级策略要显式**：
- 公网保护性限流：Redis 不可用时允许降级为“每实例内存”，但要在管理端显示 `store=memory` 并告警（现有 `PublicAccessRateLimiter#L59` 已返回该字段）。
- 租户准入配额：**不允许**降级为内存，否则多实例下配额翻倍。宁可 DB 查询慢一点，也要保证全局口径。

### 3.4 租户上下文与数据权限的边界

- `TenantContext` 只负责“我是哪个租户”，不负责“能看哪些行”。
- 行级过滤仍由 MyBatis 拦截器/RLS 负责；调度器只使用 `tenant_id` 做准入与聚合，不替代数据权限。
- 管理端跨租户查询必须走平台管理员权限并审计（多租户文档阶段 1 第 4 条）。

---

## 4. 多租户调度中心的形态

### 4.1 两种形态对比

| 维度 | 内嵌策略组件（推荐当前） | 独立调度服务 |
|---|---|---|
| 形态 | 新增 `rag-scheduler` 模块，随应用启动；DB 做协调 | 独立进程/服务，应用通过 RPC/HTTP 提交作业 |
| 部署复杂度 | 低：无新组件 | 高：新服务、新部署、新监控、新故障域 |
| 提交延迟 | 本地方法调用，毫秒级 | 网络往返 + 序列化 |
| 作业领取 | 应用内 worker 直接 claim DB | 调度服务 claim 后分发给 worker（或 worker 自行 claim） |
| 扩缩容 | 与应用同生命周期 | 可独立扩缩容 |
| 故障隔离 | 调度器故障 = 应用故障 | 调度器故障可独立降级，但多一个依赖 |
| 多语言/异构 worker | 不支持 | 支持 |
| 租户策略分发 | 本地缓存 + DB 轮询 | 服务端集中决策，天然一致 |
| 适用规模 | 单业务、作业量 < 10 万/天、副本 < 5 | 多业务、跨语言、作业量大、需要独立容量池 |
| 成本 | S-M | L |

**推荐**：当前阶段做成**内嵌策略组件**。它不是一个“调度服务”，而是应用内的“准入 + 作业 + 租约 + 策略”库；多实例之间通过 PostgreSQL 协调。管理端只是读 DB 与实例心跳，不需要额外服务。

### 4.2 多实例下的租户策略语义

| 策略项 | 单副本 | 多副本目标 | 实现 |
|---|---|---|---|
| 后台并发 | 全局 | **全局** | `wgh_tenant_runtime_slot` 原子占槽 |
| 后台排队 | 全局 | **全局** | `wgh_execution_job` PENDING 计数 |
| 交互式并发 | 全局 | **全局**（扩容前必须切换） | 阶段 A/B：实例内信号量；阶段 C：DB/Redis 全局槽 |
| 交互式排队 | 实例 | 全局或“就近排队” | 需要跨实例队列；短期可用 429 代替 |
| 权重/公平 | 全局 | 全局 | 后台 dispatcher 统一在 DB 上做加权选择 |
| 时间窗 | 全局 | 全局 | 策略表 + DB `now()` |
| 日作业数 | 全局 | 全局 | `wgh_execution_job` 按租户计数或独立计数表 |

**必须明确的失真**：如果策略写“每租户并发 2”，而实现是每实例 2，那么 3 副本实际是 6。文档、管理端和告警都必须避免这种表述。阶段 B 之前，交互式并发必须标注为“单实例上限”。

### 4.3 作业领取、租约与策略分发

**作业领取**（复用第一份文档的租约模型）：

```sql
WITH candidate AS (
  SELECT j.id
  FROM wgh_execution_job j
  WHERE j.status = 'PENDING'
    AND j.scheduled_at <= now()
    AND (j.not_before IS NULL OR j.not_before <= now())
    AND j.deleted = 0
    AND EXISTS (
      SELECT 1 FROM wgh_tenant_runtime_slot s
      WHERE s.tenant_id = j.tenant_id AND s.lane = j.lane
        AND s.running < :maxConcurrent
    )
  ORDER BY j.priority DESC, j.scheduled_at
  LIMIT 1
  FOR UPDATE SKIP LOCKED
)
UPDATE wgh_execution_job j
SET status = 'LEASED',
    lease_owner = :instanceId,
    lease_expire_time = now() + interval '60 seconds',
    heartbeat_time = now(),
    attempt = attempt + 1,
    update_time = now(),
    version = version + 1
FROM candidate c
WHERE j.id = c.id
RETURNING j.*;
```

- 占槽与领取必须在同一事务内完成；占槽用 `INSERT ... ON CONFLICT ... WHERE running < :max`（第一份调度文档 §3.7）或 `UPDATE ... WHERE running < :max`。
- 心跳 10s、TTL 60s、回收 30s，与第一份文档一致。
- 领取失败/租约丢失 → worker 停止写库，作业回到 PENDING 或 FAILED(`LEASE_LOST`)。

**策略分发**：

1. 策略表带 `version` 与全局 `epoch`（可用一行 `wgh_scheduler_meta`）。
2. 各实例每 30-60s 轮询 `epoch`，变化则全量重载到本地缓存。
3. 写策略时递增 `epoch`；可选 Redis pub/sub 立即通知（不可用时靠轮询）。
4. 已提交作业保存 `policy_snapshot_json`，运行中不因策略变更被中断。

**管理端“集群视角”**：

- 新增 `wgh_scheduler_instance(instance_id, started_at, last_heartbeat, lanes_json, version)`，每个实例每 10s 心跳。
- 管理端读 `last_heartbeat > now() - 30s` 的实例 + 作业表聚合，展示：
  - 集群总运行/排队/拒绝；
  - 每实例运行数（用于发现倾斜）；
  - 租户维度总运行/排队；
  - 失联实例（心跳过期）标灰，并提示“其上作业将在租约过期后被回收”。
- 不直接 RPC 查询各实例，避免管理端依赖实例可达性。

### 4.4 何时升级为独立调度服务

满足以下任意两条再考虑：

1. backend 副本数持续 > 5，或需要按 worker 类型独立扩缩容；
2. 作业量 > 10 万/天，或需要跨业务线共享调度；
3. 需要非 Java worker / 多语言任务；
4. 需要独立的容量池与故障隔离（调度故障不影响在线请求）；
5. 需要复杂的 DAG/定时/依赖/重试策略，维护成本已超过引入服务；
6. 有独立团队负责调度平台。

在此之前，独立服务会带来：多一个部署单元、多一套监控、网络分区下的提交失败、版本兼容成本。**不推荐**为了“看起来架构先进”提前拆分。

---

## 5. 实施路径（合并多租户阶段与调度阶段）

### 5.1 合并映射

| 合并阶段 | 多租户文档 | 调度文档 | 恢复文档 | 目标 |
|---|---|---|---|---|
| **A 止血与可恢复** | 阶段 0：先修越权 | S0：受管池、有界队列、单飞、指标、优雅停机 | L0：租约/心跳/回收/启动对账 | 单副本可靠；扩 2 副本的最低门槛 |
| **B 租户模型与后台作业** | 阶段 1：`wgh_tenant`、`TenantContext`、`tenant_id` | S1：作业表、后台 lane、策略表、只读管理端 | L1：自动续跑 | 有租户上下文；后台任务可持久化 |
| **C 数据面隔离与策略生效** | 阶段 2：全量表/存储/缓存/配额/审计隔离 | S2：策略全面生效、集群占槽、管理端写操作 | L2：工具副作用与人工介入 | 多租户可用；后台全局口径正确 |
| **D 企业级加固** | 阶段 3：RLS、计量、生命周期 | S3：集群交互口径、事件重放、死信 | L3：事件/outbox | 可证明隔离与可运营 |

> 多租户文档的**阶段 4（混合分层：大客户独立 schema / 独立数据库）是按需项**，不纳入 A-D 主线。触发条件出现时（强合规、物理隔离、超大客户）在 D 之后单独立项；调度中心通过 `tenant_id -> datasource` 路由支持，策略模型不变。

### 5.2 阶段 A（多租户阶段 0 + S0 + L0）

**目标**：不引入租户也能安全扩到 2 副本；崩溃后任务不悬挂。

**改动范围**
1. 修复多租户文档阶段 0 的越权点（G-01、G-02、G-03、G-04、G-08）。
2. 聊天/多 Agent 裸线程改受管池；工作流拆分交互/后台池。
3. 执行者标识、租约、心跳、回收、启动对账（第一份文档）。
4. CRON 与连接器 advisory lock 单飞。
5. 修复“先插 RUNNING 再提交”的失败边界（第一份调度文档 §1.4）。
6. `server.shutdown=graceful` + drain。
7. backend 挂持久卷（当前 compose 无 volume）。

**验收**
- 2 副本同时运行：同一 CRON 只触发一次、同一连接器只同步一次、同一作业/计划只执行一次。
- `kill -9` 后 ≤90s 状态离开 RUNNING。
- 池满不会留下 RUNNING。
- 重启后本地文件仍在（挂卷）。

**风险**：advisory lock 需要处理连接断开后的自动释放；受管池引入拒绝行为，需要前端 429/503。

### 5.3 阶段 B（阶段 1 + S1 + L1）

**目标**：建立租户上下文；后台作业可持久化、可重试。

**改动范围**
1. `wgh_tenant`、`wgh_user.tenant_id`、`TenantContext`、过滤器与令牌 claim。
2. `TaskDecorator` 统一传递 `RequestContext`；`@Async` 自定义执行器。
3. `wgh_execution_job`、`wgh_tenant_schedule_policy`（默认桶）、后台 lane、作业处理器 SPI。
4. 评测、静默工作流、连接器、文件处理迁移到作业。
5. 配额原子条件更新；限流 key 加租户前缀（未启用租户时用 0）。
6. 工作流游标写入 `context_json`；单 Agent 自动续跑。

**验收**
- 异步线程的 `create_by`、审计、配额都能看到 `tenantId`。
- 后台作业崩溃可重试；评测不再占用请求线程。
- 租户 A 的后台任务不影响租户 B 的聊天 P95。
- 同一作业在多副本下只执行一次。

**风险**：租户上下文漏传会导致“无主”数据；需要启动校验与负向测试（多租户文档阶段 1 验收）。

### 5.4 阶段 C（阶段 2 + S2 + L2）

**目标**：数据面全量隔离；租户策略真正生效。

**改动范围**
1. `TenantLineInnerInterceptor`/RLS 前置；`DataScopeInterceptor` 改造。
2. 语义缓存加 `tenant_id` + 向量 metadata；存储 key 加租户；Redis key 加命名空间。
3. 后台 `wgh_tenant_runtime_slot` 全局占槽；加权公平调度。
4. 交互式准入控制器读策略；集群口径（DB slot 或 Redis）。
5. 管理端策略编辑、试算、单租户视图、作业重试/取消/回收；权限与审计。
6. 工具副作用分类与 `wgh_agent_tool_call`。

**验收**
- 跨租户负向测试全通过（缓存、存储、向量、配额、审计、运行态接口）。
- 一个租户占满自己的并发后，其他租户不受影响。
- 策略修改 60s 内全实例生效；管理端看到集群视角。
- 写工具崩溃不自动重试。

**风险**：全量拦截器改造面大；pgvector 表/集合命名与租户前缀需要迁移；性能需压测。

### 5.5 阶段 D（阶段 3 + S3 + L3）

**目标**：企业级可证明隔离与可运营。

**改动范围**
1. RLS + 非 owner 应用角色 + `SET LOCAL app.tenant_id`。
2. 集群交互式配额精确口径；计量/计费视图。
3. `wgh_execution_event` + SSE `Last-Event-ID` 重放；死信管理。
4. 租户生命周期（停用、导出、删除）、备份恢复演练。

**验收**：数据库层可证明隔离；断线重连可补齐事件；死信可处置；计量可按租户对账。

### 5.6 明确不推荐

| 做法 | 为什么不推荐 |
|---|---|
| 一开始就上 K8s 级调度/自研 Operator | 当前单副本单体，收益为零，复杂度极高 |
| 引入 Temporal/Camunda 类持久化执行引擎 | 需要重写编排层 + 新集群运维；现有表已能做检查点 |
| 把粘性会话当作多实例唯一手段 | 发布/扩缩容/故障时粘性失效；不能替代持久化与租约 |
| 只用 Redis 做租户并发/配额 | Redis 可降级为内存（`DefaultPublicAccessStore#L207-L225`），配额会翻倍；DB 才是事实源 |
| 用 `update_time` 当心跳 | 已被第一份文档 §1.3 证伪（`strictUpdateFill` 不刷新） |
| 每个功能各自建线程池继续堆叠 | 线程数不可控、无公平性、无集群口径 |
| 先做可视化再补后端口径 | 管理端会展示“假全局”数据，误导运营 |
| 在业务代码里直接 `new Thread` | 无法排水、无法传租户、无法观测 |

### 5.7 不做的代价

| 不做 | 代价 |
|---|---|
| 不做租约/单飞 | 2 副本即重复执行；CRON 成本翻倍；状态无法收敛 |
| 不做 `TenantContext` 传递 | 异步任务归属丢失、配额串到 anonymous、审计无法按租户 |
| 不做全局并发口径 | “每租户 2 并发”在 3 副本下变成 6；SLA 无法承诺 |
| 不做缓存/存储租户维度 | 跨租户读到缓存答案/文件；合规风险 |
| 不做集群视角 | 无法判断容量倾斜、无法定位失联实例 |
| 不做本地存储外置 | 容器重建丢文件；多副本读不到彼此文件 |

---

## 6. 验收标准与测试场景

### 6.1 验收标准

| 编号 | 标准 |
|---|---|
| A1 | 2 副本下 CRON/连接器/作业/计划均只执行一次 |
| A2 | 任一实例 `kill -9` 后 ≤90s 状态离开 RUNNING，且可自动或人工恢复 |
| A3 | 异步线程、定时任务、工作流节点、Crew 线程都能读到正确 `tenantId` |
| A4 | 语义缓存、文件、向量、Redis key 均带租户维度，跨租户负向测试通过 |
| A5 | 配额检查与自增原子化，并发下不超发 |
| A6 | 后台租户并发为集群口径；交互式口径在管理端明确标注 |
| A7 | 策略修改 60s 内全实例生效，运行中作业不受影响 |
| A8 | 管理端能看到集群/实例/租户三层运行与排队数据 |
| A9 | 所有策略与作业操作有审计；权限不足 403 |
| A10 | 正常聊天 P95 不劣化（阈值需压测确认） |

### 6.2 测试场景

| 编号 | 场景 | 期望 |
|---|---|---|
| T1 | 2 副本同时到 CRON 时间 | 只产生 1 个 run |
| T2 | 2 副本同时轮询连接器 | 同一连接器只同步 1 次 |
| T3 | 2 副本同时 HITL 确认 | 只有 1 个 claim 成功，另 1 个 409 |
| T4 | 作业执行中 kill -9 | 租约过期后被另一实例重试；attempt+1 |
| T5 | 租户 A 提交 100 个后台作业，租户 B 聊天 | B 的 P95 与拒绝率不受影响 |
| T6 | 3 副本，策略“每租户后台并发 2” | 集群总并发 ≤2 |
| T7 | 交互式策略在 2 副本下 | 管理端明确显示“实例内上限”，不谎报全局 |
| T8 | 异步线程创建 KB/文件 | `create_by`/`tenant_id` 正确，不是 `system` |
| T9 | 语义缓存跨租户查询 | 即使 windowId 命中也不返回他租户答案 |
| T10 | Redis 宕机 | 公网限流降级并告警；租户配额仍走 DB，不翻倍 |
| T11 | 配额并发 100 请求，limit=10 | 成功数 ≤10（原子条件更新） |
| T12 | 本地存储切 OSS | 多副本上传/下载一致；旧对象可读 |
| T13 | SSE 断线后重连 | S3 前：状态轮询恢复；S3 后：事件补齐 |
| T14 | 失联实例 | 管理端 30s 内标灰；其作业被回收 |

---

## 7. 未验证与推断声明

1. **未做多副本实测**：仓库只有单副本 compose，本文的“重复触发/重复执行”来自代码路径，不是集群实测。
2. **未做 Redis 故障演练**：降级语义来自 `DefaultPublicAccessStore` 的代码注释与实现，未实测。
3. **Boot 默认 `@Async` 线程池参数**：仓库无自定义 `TaskExecutor`，按 Spring Boot 默认推断，未运行时确认。
4. **配额超发窗口大小**：确认存在“先查后加”竞态，但未压测具体超发数量。
5. **语义缓存跨租户泄露**：依赖窗口 ID 是否可被他人访问；多租户文档 G-01/G-04 为已有结论，本文未重新复现。
6. **生产副本数**：仓库无法确认；若生产中已是多副本，阶段 A 的优先级应立即提升。
7. **本地存储**：确认 compose 未挂卷；生产是否用 OSS 由环境变量决定，仓库默认 `local`。
8. **成本量级**：S/M/L 为相对估算，不是工时承诺。

---

## 附录 A：证据索引

| 结论 | 证据 |
|---|---|
| 配额在 PostgreSQL | `rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/service/QuotaService.java#L94-L132` |
| 配额表与唯一键 | `rag-backend/sql/schema/01-schema.sql#L964-L998` |
| 配额累加实现 | `rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/QuotaUsageMapper.java#L20-L55` |
| 限流 Redis Lua + 内存兜底 | `rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/DefaultPublicAccessStore.java#L20-L21`、`#L28-L123`、`#L207-L225` |
| 限流 key 构造 | `rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/PublicAccessRateLimiter.java#L42-L73` |
| 分享密码/嵌入授权 key | `rag-public-access/.../SharePasswordGuard.java#L26`、`EmbedAccessService.java#L26` |
| 语义缓存只按窗口隔离 | `rag-kb/.../service/SemanticCacheService.java#L64-L115`、`#L136-L138` |
| 语义缓存表结构 | `rag-backend/sql/schema/01-schema.sql#L1021-L1039` |
| 语义缓存清理无单飞 | `SemanticCacheService.java#L223-L232` |
| 工具熔断器进程内 | `rag-tool/.../fallback/ToolCircuitBreaker.java#L16-L23` |
| 黑板进程内 | `rag-agent-multi/.../context/MultiAgentContext.java#L39`、`blackboard/InMemoryBlackboard.java#L20` |
| 聊天取消是请求内对象 | `rag-chat/.../service/ChatStreamCancellation.java#L15-L19`、`RagChatService.java#L167-L180` |
| HITL 已数据库化 | `rag-agent/.../service/HitlGate.java#L41-L54` |
| 工作流游标在内存 | `rag-workflow/.../engine/WorkflowEngine.java#L446-L458` |
| SSE emitter 进程内 Map | `rag-workflow/.../service/WorkflowService.java#L61-L62`、`#L693-L707` |
| CRON 每实例注册 | `rag-workflow/.../service/WorkflowTriggerService.java#L51-L63`、`#L142-L151` |
| 连接器每实例调度 | `rag-kb/.../connector/ConnectorScheduler.java#L34-L49` |
| 上传清理每实例 | `rag-kb/.../service/UploadSessionCleaner.java#L40-L55` |
| 本地存储默认 | `rag-storage/.../properties/StorageProperties.java#L19-L33` |
| 本地存储实现 | `rag-storage/.../service/LocalFileStorageService.java#L38-L47` |
| 存储 key 无租户 | `rag-storage/.../service/StorageKeys.java#L18-L24` |
| backend 无数据卷 | `docker-compose.yml#L46-L82` |
| Redis 默认启用 | `rag-config/.../redis/RagRedisConfig.java#L22-L54`、`docker-compose.yml#L65-L67` |
| MDC 只传 requestId | `rag-common/.../log/MdcContext.java#L19-L50` |
| 异步配额回落 anonymous | `rag-quota/.../service/DefaultQuotaUserResolver.java#L17-L24` |
| 多租户阶段划分 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L31-L39` |
| 租户模型与决策 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L493-L508` |
| 配额现状与改造点 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L294-L311` |
| 隔离薄弱点 | `docs/review/multi-tenancy-analysis-2026-11-18.md#L390-L410` |
| 恢复文档租约/心跳 | `docs/design/features/agent/design-react-task-recovery.md` §2.4-§2.9 |
| 调度文档 lane/策略/作业表 | `docs/design/features/platform/design-unified-scheduler-and-tenant-policy.md` §2-§5 |
