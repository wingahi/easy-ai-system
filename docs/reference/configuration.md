# 配置说明

> 本文是 [README](../../README.md) 配置说明章节的完整版。功能开关与运行参数集中在 `application-features.yml`，由 `application.yml` 以 `optional:classpath` 引入；文件内所有值等于代码默认值，缺失不影响启动，敏感项仍由环境变量注入。
>
> `config/migrations/` 存放按日期分目录的**增量配置片段**（如 `20261120/01_async-executor.yml`、`02_scheduler.yml`、`03_quota-refund.yml`，`20261122/01_auth-jwks-shared-cache.yml`、`02_tracing.yml`，`20261124/01_graceful-shutdown-probes.yml` 至 `07_auth-user-cache.yml`），不参与自动加载；2026-11-24 的 7 个片段内容已合入主配置，片段只作变更记录。合并方式与各片段键位见 [config/migrations/README.md](../../rag-backend/config/migrations/README.md)。

## 认证与初始化管理员

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_INIT_ADMIN_PASSWORD` | 空 | 初始管理员口令；未注入且库中无 admin 时不创建管理员，启动日志输出 ERROR；**禁止使用默认口令** |
| `wgh.auth.local.jwt-secret` / `WGH_AUTH_LOCAL_JWT_SECRET` | 空 | 本地会话签名密钥；`prod` / `production` 下未配置会启动失败（fail-closed）；开发环境未配置会生成随机密钥，重启后旧会话失效 |
| `WGH_JWT_SECRET` | 无 | 兼容旧 prod 配置项，建议与 `WGH_AUTH_LOCAL_JWT_SECRET` 同值 |
| `wgh.auth.init-admin.password` | 空 | `WGH_INIT_ADMIN_PASSWORD` 的 YAML 等价配置项 |

本地账号仅允许 `wgh_user.source=local` 的 admin 或体验账号（`trial=1`）登录；外部 OIDC 用户即使 `password_hash` 为空也不能走本地密码登录。

## 外部 OIDC（资源服务器）

| 配置 | 说明 |
|------|------|
| `WGH_OIDC_ISSUER` / `wgh.auth.oidc.issuer` | IdP issuer，token `iss` 必须一致 |
| `WGH_OIDC_AUDIENCE` / `wgh.auth.oidc.audience` | 期望 audience，token `aud` 必须包含 |
| `WGH_OIDC_JWKS_URI` / `wgh.auth.oidc.jwks-uri` | JWKS 端点；留空时按 issuer 自动发现 |
| `WGH_OIDC_ROLES_CLAIM` / `wgh.auth.oidc.roles-claim` | 承载角色的 claim，默认 `roles`，常用 `groups` |
| `WGH_OIDC_USERNAME_CLAIM` / `wgh.auth.oidc.username-claim` | 承载用户名的 claim，默认 `sub` |
| `WGH_OIDC_JWKS_SHARED_CACHE` / `wgh.auth.oidc.jwks-shared-cache-enabled` | 多实例 JWKS Redis L2 共享缓存；代码与增量片段默认 `true`；关闭时与改造前一致 |
| `wgh.auth.oidc.role-mapping` | claim 值 → `wgh_role.role_code` 映射表 |
| `wgh.auth.oidc.default-role` | token 无角色或映射不到数据库角色时的默认角色，默认 `viewer` |

验签仅接受 RS / PS / ES 非对称算法，拒绝 HS256 与 `none`；JWKS 保留进程内 L1 缓存（TTL 300s），开启共享缓存后增加 Redis L2，key 为 `{wgh.rag.redis.key-prefix}:auth:jwks:<sha256(jwksUri)>`，存原始 JWKS JSON；Redis 异常自动降级本地（见 [已知限制](known-limitations.md)）。

## 文件存储（local / OSS）

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_STORAGE_TYPE` / `wgh.rag.storage.type` | `local` | `local` 本地磁盘 / `oss` 阿里云 OSS |
| `WGH_STORAGE_MODE` / `wgh.rag.storage.mode` | `single` | 部署模式：`single` 单实例（行为不变）/ `shared` 多实例共享；`shared` 启动自检失败即启动失败 |
| `WGH_STORAGE_READ_FALLBACK` / `wgh.rag.storage.read-fallback-enabled` | `false` | 迁移期回落读（仅 `type=oss` 生效）：主存储未命中时读 `local.base-dir` 旧对象；写 / 删仍走主存储 |
| `WGH_UPLOAD_DIR` / `wgh.rag.storage.local.base-dir` | `./uploads` | 本地存储根目录 |
| `WGH_STORAGE_URL_PREFIX` / `wgh.rag.storage.local.url-prefix` | `/uploads` | 本地 `resolveUrl` 逻辑前缀（需静态服务配合） |
| `WGH_STORAGE_MAX_FILE_MB` | 50 | 存储层单文件大小上限 |
| `WGH_OSS_ENDPOINT` / `wgh.rag.storage.oss.endpoint` | - | OSS 公网 endpoint |
| `WGH_OSS_INTERNAL_ENDPOINT` | - | OSS 内网 endpoint（可选） |
| `WGH_OSS_BUCKET` / `wgh.rag.storage.oss.bucket` | - | OSS bucket |
| `WGH_OSS_ACCESS_KEY_ID` / `WGH_OSS_ACCESS_KEY_SECRET` | - | OSS 凭证，生产必须环境变量注入 |
| `WGH_OSS_KEY_PREFIX` | 空 | 对象 key 前缀 |
| `WGH_OSS_PUBLIC_READ` | `false` | `true` 返回公网直链；`false` 返回签名 URL |
| `WGH_OSS_SIGNED_URL_EXPIRE_MINUTES` | 60 | 签名 URL 有效期 |
| `WGH_OSS_MAX_RETRIES` | 3 | 上传失败重试次数（含首次） |

OSS 配置不完整时不阻断启动，仅在真正调用存储操作时返回明确错误。存储切换、迁移与孤儿对象限制见 [已知限制](known-limitations.md)；完整设计见 [design-file-storage.md](../design/features/storage/design-file-storage.md)。

## 数据库、Redis 与其他关键配置

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_PROFILE` | `dev` | Spring Profile：`dev` / `test` / `prod` |
| `WGH_DB_HOST` / `PORT` / `NAME` / `USERNAME` / `PASSWORD` | localhost / 5432 / wgh_rag_dev / postgres / 123456 | 本地 dev 数据源 |
| `POSTGRES_*` | - | Compose 内置数据库与 backend 数据源的唯一真相，密码必填 |
| `PROD_DB_*` | - | 非 Compose（裸机 `prod`）或外部库；优先级低于 `SPRING_DATASOURCE_*` |
| `WGH_REDIS_ENABLED` / `HOST` / `PORT` / `PASSWORD` | true / localhost / 6379 / 空 | Redis 连接；不可用时降级为单机内存 |
| `WGH_MANAGEMENT_PORT` | 9090 | 管理端口；业务端口不暴露 actuator |
| `WGH_MANAGEMENT_ADDRESS` | 0.0.0.0 | 管理端口监听地址，生产建议内网网卡 |
| `wgh.public-access.share-default-expire-days` | 7 | 分享链接默认有效期（上限 30 天） |
| `wgh.public-access.share-password.max-failures` | 5 | 密码失败阈值 |
| `wgh.public-access.share-password.lock-minutes` | 15 | 锁定时长（分钟） |
| `wgh.rag.cache.similarity-threshold` | 0.95 | 语义缓存命中阈值 |
| `wgh.file.max-single-file-mb` | 50 | 单文件上传 / 解析大小上限 |

> 限流覆盖、可信代理、配额、Agent 与上下文压缩等完整配置与设计说明见 `docs/design/features/**/*.md`；接口侧的限流与错误码行为见 [api.md](../api/api.md)。
> `wgh.public-access.trusted-proxies` 默认空，只信任 TCP 对端；部署在 Nginx / 网关后需显式配置可信 CIDR，**不要配置为 `0.0.0.0/0`**。

## 检索与评测参数

| 配置 | 默认 | 说明 |
|------|------|------|
| `wgh.rag.retrieve.min-score` | 0.55 | 向量召回最低余弦相似度；`0.3` 会引入大量弱相关噪声，dev / test / prod 三套 yml 已统一为 `0.55` |
| `wgh.rag.retrieve.enable-hybrid` | `false` | 全局混合检索开关；最终是否启用为“窗口 `hybridEnabled=true` **或** 本项为 `true`”，窗口显式关闭不能覆盖全局开启（2026-11-18） |
| `wgh.rag.retrieve.keyword-top-n` | 10 | 混合检索关键词路召回条数 |
| `wgh.rag.evaluation.judge-timeout-seconds` | 30 | 单个 LLM judge 调用超时，超时按该 judge 失败处理，不影响主链路 |
| `wgh.rag.evaluation.judge-concurrency` | 4 | 同时评估的 judge 数量上限 |
| `wgh.rag.evaluation.repeat-count` | 1 | 每个 judge 的重复评估次数，最终取有效结果均值 |
| `wgh.rag.evaluation.max-queries-per-evaluation` | 200 | 单次离线检索评测最多处理的标注查询数 |

> OCR 相关配置通过环境变量注入：`WGH_OCR_ENABLED`、`WGH_OCR_LANG`（默认 `chi_sim+eng`）、`WGH_OCR_MIN_TEXT_CHARS`（默认 20）、`WGH_OCR_MAX_PAGES`（默认 50）、`WGH_OCR_TIMEOUT_SECONDS`（默认 60）；引擎缺失或超时时降级为纯文本抽取，不阻断入库。

## URL 数据源连接器与工作流调试

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_CONNECTOR_SCHEDULER_ENABLED` / `wgh.connector.scheduler.enabled` | `false` | 定时同步总开关；默认关闭时启动后不抓取外网，只能手动同步 |
| `WGH_CONNECTOR_SCHEDULER_INTERVAL_SECONDS` | 60 | 调度轮询间隔（秒，最小 5） |
| `WGH_CONNECTOR_SCHEDULER_INITIAL_DELAY_SECONDS` | 30 | 启动后首次轮询延迟（秒） |
| `WGH_CONNECTOR_SCHEDULER_BATCH_SIZE` | 20 | 单轮最多检查的连接器数 |
| `WGH_CONNECTOR_FETCH_TIMEOUT_MS` | 10000 | 单次抓取连接 / 请求超时（毫秒） |
| `WGH_CONNECTOR_FETCH_USER_AGENT` | `WGH-RAG-Connector/1.0` | 抓取 User-Agent |
| `WGH_CONNECTOR_FETCH_MAX_CONTENT_CHARS` | 200000 | 正文最大字符数，超出截断 |
| `WGH_CONNECTOR_DEFAULT_SYNC_INTERVAL_MINUTES` | 60 | 新建连接器未指定间隔时的默认值 |
| `WGH_WORKFLOW_DEBUG_ENABLED` / `wgh.workflow.debug.enabled` | `false` | 工作流单步调试开关；关闭时调试接口直接拒绝，普通运行链路不受影响 |

> URL 抓取复用 `SsrfGuard`：仅允许 http/https，拒绝回环 / 内网 / 链路本地 / 云元数据地址，重定向逐跳校验（默认最多 3 跳），响应体默认上限 2MB；`wgh.tool.ssrf.allow-private-network` 默认 `false`。

## 上下文预算、工具兜底与上传清理

| 配置 | 默认 | 说明 |
|------|------|------|
| `wgh.rag.context.max-history-tokens` | 4000 | 历史消息 Token 上限；System 与当前提问必留，其余从最近往前保留 |
| `wgh.rag.context.context-max-tokens` / `context-window-ratio` | 6000 / 0.6 | 检索上下文硬上限与输入侧窗口占比 |
| `wgh.rag.context.tool-result-max-tokens` | 1200 | 单条工具结果进入 prompt 的 Token 上限，超出按句界裁剪并标注 |
| `wgh.rag.context.tool-round-history-max-tokens` | 3000 | 多轮工具结果累计上限，超出从最旧开始折叠 |
| `wgh.tool.fallback.enabled` | `true` | 工具兜底链总开关；关闭后退化为单次直调 |
| `wgh.tool.fallback.max-attempts` / `backoff-ms` / `jitter-ratio` | 2 / 300 / 0.3 | 仅幂等调用重试；退避 ±30% 抖动 |
| `wgh.tool.fallback.idempotent-only` | `true` | 仅 HTTP GET/HEAD 或显式 `idempotent=true` 的工具允许重试 |
| `wgh.tool.fallback.failure-threshold` / `open-seconds` | 5 / 30 | 按 toolId 连续失败 5 次打开熔断 30s，半开只放行 1 个探针 |
| `wgh.tool.fallback.task-max-requests` / `task-max-total-ms` | 50 / 120000 | 单任务工具调用次数与总时长预算 |
| `WGH_UPLOAD_CLEANUP_ENABLED` | `true` | 分片上传会话清理开关 |
| `WGH_UPLOAD_CLEANUP_STALE_HOURS` | 24 | 只清理超过该时长未更新的 `UPLOADING` 会话 |
| `WGH_UPLOAD_CLEANUP_INTERVAL_MINUTES` / `BATCH_SIZE` | 60 / 200 | 清理轮询间隔（最小 5 分钟）与单轮上限 |

## 调度中心与配额退款补偿

| 配置 | 默认 | 说明 |
|------|------|------|
| `wgh.scheduler.enabled` | `true` | 调度中心总开关；关闭后不创建车道线程池与作业循环，后台作业与补偿作业不再运行 |
| `wgh.scheduler.lease-ttl` | 60s | 作业租约 TTL；持有者需在到期前续租，否则可被其它实例回收 |
| `wgh.scheduler.single-flight.lock-ttl` | 120s | 维护车道单飞锁 TTL；Redis 只做锁，不存待退款数据 |
| `wgh.scheduler.instance-heartbeat-interval` | 10s | 多副本实例心跳写入间隔 |
| `wgh.scheduler.instance-liveness-ttl` | 45s | 实例失联阈值（建议不低于 3 倍心跳）；超过即回收其 `LEASED / RUNNING` 作业 |
| `wgh.scheduler.instance-sweep-interval` / `instance-retention` | 10s / 7d | 失联扫描间隔 / 实例注册行保留时长 |
| `wgh.scheduler.reconcile.enabled` / `interval` / `lookback` / `pending-grace` / `batch-size` | true / 10m / 24h / 5m / 200 | 作业对账周期、回看窗口、`PENDING` 自动取消宽限期与单轮上限 |
| `wgh.scheduler.chat-run.enabled` / `lease-ttl` / `terminal-retention` | true / 35m / 24h | 普通对话运行态登记；关闭后「进行中任务」CHAT 页签为空 |
| `wgh.rag.quota.fail-open-on-count-error` | `false` | 计数写入异常策略：`false` 拒绝本次对话并返回 `5004`；`true` 仅告警放行且不扣费 |
| `wgh.rag.quota.charge.refund-enabled` | `true` | 是否启用退款补偿作业 |
| `wgh.rag.quota.charge.refund-protection` | 5m | 已扣未决保护期，超过后才补偿 |
| `wgh.rag.quota.charge.refund-scan-interval` | 60s | 补偿作业提交间隔（维护车道） |
| `wgh.rag.quota.charge.refund-max-attempts` | 5 | 单条流水最大退款尝试次数（含首次），耗尽后置 `DEAD` |
| `wgh.rag.quota.charge.refund-backoff` / `refund-backoff-max` | 60s / 30m | 指数退避基数与上限 |
| `wgh.rag.quota.charge.refund-batch-size` | 200 | 单次扫描最多处理的流水条数 |

> 调度中心与配额扣费流水的迁移前置、各脚本状态与执行顺序见 [数据库迁移](database-migrations.md)。增量配置片段见 `config/migrations/20261120/02_scheduler.yml`、`03_quota-refund.yml` 与 `config/migrations/20261124/02_scheduler-cluster.yml`、`05_chat-run-registry.yml`、`06_quota-count-policy.yml`。

## 分布式链路追踪（2026-11-22）

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_TRACING_ENABLED` / `management.tracing.enabled` | `true` | 总开关；关闭后不生成 `traceId`，日志 trace 位输出 `--` |
| `WGH_TRACING_SAMPLING_PROBABILITY` / `management.tracing.sampling.probability` | `1.0` | 采样率；默认全量采样，保证每个请求都有 `traceId` |
| `logging.pattern.console` / `logging.pattern.file` | 含 `[trace=%X{traceId:--}]` | 在既有 `rid` 之后追加 trace；`logback-spring.xml` 的 `LOG_PATTERN` 同步 |

> 未配置 Zipkin / OTLP 等采集端时只本地生成 `traceId` / `spanId`，不发起网络请求；调度作业提交时把 `traceId` 写入 `wgh_execution_job.trace_id`，执行实例恢复。默认全量采样（`1.0`），高吞吐环境应下调采样率。周期作业（`RecurringJob`）没有提交请求，`trace_id` 为空，可用 `execution_id` 关联。增量片段见 `config/migrations/20261122/02_tracing.yml`。

## 多副本运行参数：SSE 重放、用户缓存与优雅停机（2026-11-24）

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_STREAM_REPLAY_ENABLED` / `wgh.rag.stream.replay.enabled` | `true` | SSE 重放总开关；关闭后回到「实时流 + 断连即取消」旧语义 |
| `wgh.rag.stream.replay.ttl` / `completed-ttl` | 35m / 5m | 活跃流 / 终态流缓冲 TTL |
| `wgh.rag.stream.replay.max-events-per-stream` | 5000 | 单流最多保留事件条数 |
| `wgh.rag.stream.replay.flush-interval` / `poll-interval` / `poll-batch-size` | 200ms / 300ms / 200 | 批量刷盘间隔、重连跟随轮询间隔与单次读取条数 |
| `wgh.rag.stream.replay.idle-timeout` / `follow-timeout` | 120s / 29m | 无新事件空闲判定 / 单次重连最长跟随时间 |
| `WGH_AUTH_USER_CACHE_ENABLED` / `wgh.auth.user-cache.enabled` | `true` | 用户信息 Redis 缓存开关 |
| `WGH_AUTH_USER_CACHE_TTL` / `wgh.auth.user-cache.ttl` | 30m | 缓存 TTL；不允许永久键 |
| `WGH_AUTH_USER_CACHE_LOCK_WAIT` / `wgh.auth.user-cache.lock-wait-timeout` | 200ms | 未命中回填时等待分布式锁的上限，超时直接回源且不写缓存 |
| `server.shutdown` / `spring.lifecycle.timeout-per-shutdown-phase` | `graceful` / 45s | 优雅停机；45s 需小于 Pod `terminationGracePeriodSeconds` 减 `preStop` |
| `management.endpoint.health.probes.enabled` + `group.liveness` / `group.readiness` | true；`livenessState` / `readinessState,db` | K8s 探针分组；Redis 可降级、磁盘余量不纳入 readiness |
| `wgh.resume.stale-minutes` | 30 | 任务中断判定窗口（由 5 调整为 30） |

> SSE 重放依赖共享 Redis；Redis 不可用时无法跨副本重放，重连请求显式失败并上报 `wgh.stream.replay.fallbacks`。配置片段见 `config/migrations/20261124/01_graceful-shutdown-probes.yml`、`03_storage-shared-multi-replica.yml`、`04_stream-replay.yml`、`07_auth-user-cache.yml`。

---

[返回 README](../../README.md)
