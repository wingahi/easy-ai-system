# 能力矩阵

> 本文是 [README](../../README.md) 能力矩阵章节的完整版，保留到具体模块、类名、配置键与提交号。状态口径：**已完成** / **已交付未验证**（代码或清单已合入，缺真实环境或端到端验证）/ **部分完成** / **未实现** / **规划中**（已有成稿规划文档、尚未实施）。状态按代码与文档核对结果判断；各项限制与未验证项见 [已知限制](known-limitations.md)，平台演进四块的状态与阶段见 [路线图](roadmap.md)。

## 知识库与检索

| 能力 | 状态 | 说明 |
|------|------|------|
| 文档解析（12 种格式） | 已完成 | 位于 `rag-document`：PDF / DOC / DOCX / XLS / XLSX / CSV / TXT / MD / PPTX / HTML / JSON / EML；统一由 `DocumentParserDispatcher` 分发并抽取元数据 |
| PDF 版面分析与表格还原 | 已完成 | `rag-document` 的 `PdfLayoutAnalyzer` 识别双栏阅读顺序，`TableExtractor` 将表格还原为 Markdown；栏检测优先于表格，纯两列表格可能按双栏处理 |
| 扫描版 PDF OCR | 已完成 | `rag-document` 的 `TessOcrService` 按页兜底，单页文字少于阈值（默认 20 字）触发；依赖本机 `tesseract` 与 `chi_sim+eng` 语言包，缺失或超时降级为文本抽取（`WGH_OCR_*`） |
| 新增文档格式（PPTX / HTML / JSON / EML） | 已完成 | `rag-document` 的 `PptxParser` / `HtmlParser` / `JsonParser` / `EmlParser` + 统一元数据与大小上限 |
| 文本切分（9 种策略） | 已完成 | `rag-document` 的 AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM / STRUCTURED（标题感知）/ SEMANTIC（语义断句） |
| 查询变换 / 混合检索 / RRF / Rerank | 已完成 | REWRITE / MULTI_QUERY / HYDE；向量余弦 + pg_trgm 关键词召回；RRF(k=60) 融合；可选 Rerank；聊天窗口可单独配置 `hybridEnabled` / `queryTransformMode` / `rewriteModelId`，混合检索按“窗口开启或全局开启”生效，窗口关闭不能覆盖全局开启（2026-11-18，commit `8b52b5f`） |
| 检索指标与离线评测 | 已完成 | `rag-eval`：覆盖率 / 命中率；ground truth 标注后计算 Recall@K / Precision@K / MRR / NDCG；生成侧多 judge 评估 Faithfulness / Answer Relevancy / Context Precision / Context Recall（缺参考答案时显式标记不可计算）；`eval/eval_recall.py` 复用同一标注 |
| 评测数据集版本化与 CI 检索门禁 | 已完成 | `rag-eval`：数据集 / 用例 / 结果三表；用例每次变更按“写时复制”生成新版本，旧版本只读；按版本评测并归档指标与逐查询明细，支持跨版本指标差值与逐查询 Recall 差值；CI 用固定 fixture 在无 Key、无网络、无真实模型条件下校验五项阈值（commit `adbdafe`） |
| 检索来源结构化与 [Sn] 引用溯源 | 已完成 | `sources` 扩展 `kbId` / `kbName` / `fileId` / `fileName` / `chunkIndex` / `pageNo` / `charStart` / `charEnd` / `score` / `snippet`（压缩空白后前 120 字），按文件名去重保留最高分；上下文按文档分配 `[Sn]` 编号（同文档多段共用），system prompt 要求按编号引用；前端 `SourcePanel.vue` 展开原文并高亮对应卡片（commit `96dfc42`） |
| 知识库标签 | 已完成 | `TagService` / `TagController`；知识库与文件多标签绑定及管理界面 |
| 文档版本 | 已完成 | `FileVersionService` + 版本列表 / 回滚；回滚生成新版本，不覆盖旧版本 |
| URL 数据源连接器（单页抓取 + 增量同步） | 已完成 | `ConnectorController` / `ConnectorService` / `UrlConnector`；单个 http/https 页面抓取、Jsoup 正文抽取、SSRF 逐跳校验；按 `content_hash` 判重，内容未变返回 `UNCHANGED` 不重复入库，内容变化生成新文件并逻辑删除旧文件，404/410 清理旧文件；每次同步留运行记录；定时同步默认关闭，需显式 `WGH_CONNECTOR_SCHEDULER_ENABLED=true`（commit `855f61f`） |
| 可插拔文件存储（local / OSS） | 已完成 | 独立 `rag-storage` 模块，`FileStorageService` + 本地 / 阿里云 OSS 实现 |
| 多副本共享存储（`mode=shared`） | 已完成（未在容器内验证） | `rag-storage` 新增 `wgh.rag.storage.mode`：`single`（默认，行为不变）/ `shared`。`shared` 启动自检（`StorageDeploymentGuard`，失败即启动失败）：`type=oss` 校验必填项完整；`type=local` 校验 `base-dir` 可写且为独立挂载卷，并执行写-读-删探测。`read-fallback-enabled`（仅 `type=oss`）在主存储未命中时回落 `local.base-dir` 读取旧对象，写 / 删仍以主存储为准，用于 local → oss 滚动切换。独立挂载卷判定只在本机临时进程验证过，未在容器内验证 |
| 大文件分片上传与断点续传 | 已完成 | 三段式接口：`POST /api/kb/{kbId}/files/chunk/init` → `POST /api/kb/files/chunk/{sessionId}`（按序号上传分片）→ `POST .../complete`；合并后按整文件 `content_hash` 走原有判重与入库链路；页面内断线续传（进度来自会话表）；会话按创建者归属校验，他人会话按不存在处理（`3001`）；24 小时未完成会话自动清理，阈值可配（`WGH_UPLOAD_CLEANUP_*`，commit `140375a`） |
| `local` ↔ `oss` 历史对象自动迁移 | 已完成 | 提供历史对象迁移服务与管理端异步作业（提交 `e4155c6`）：任务状态与取消标记存 Redis（TTL 24h），跨实例单飞锁；需在管理端显式发起，不做自动触发 |
| OSS 删除失败后的孤儿对象清理 | 已完成 | 定期扫描对象存储与库内记录比对并输出孤儿报告（提交 `e4155c6`）；默认只报告不删除（`delete=false`），最后修改时间 60 分钟内受保护 |
| 独立图片 OCR | **未实现** | 扫描版 PDF 已按页 OCR；独立图片文件（PNG / JPG）仍不解析入库 |

## 对话与开放接入

| 能力 | 状态 | 说明 |
|------|------|------|
| 多会话与流式问答 | 已完成 | 多会话管理、首条消息自动生成标题、历史滑动加载；SSE 输出 Markdown、引用来源与检索指标；回答中的 `[Sn]` 可点击定位来源卡片（2026-11-12） |
| SSE 事件重放与自动重连（2026-11-24） | 已完成（未验证：跨副本重连） | 后端 `StreamReplayService` 为每个事件分配 `<streamId>:<seq>` 并批量写 Redis，重连按 `Last-Event-ID` 回放 `seq > 游标` 的事件；缺口下发 `stream_replay_gap`、未完整收口下发 `stream_replay_incomplete`。客户端断开只停止推送、继续生成，不再等同执行失败；前端 `sseStream.js` 按指数退避自动重连（5 次，1s–15s，带抖动），重连体只带 `sessionId + lastEventId`，不重发消息、不重复确认 HITL。Pod A 生成、Pod B 接管的事件完整性与时延未验证 |
| 三层上下文压缩 | 已完成 | 超过 `max-history-messages`（默认 20）触发：近期原文 / 中期摘要 / 远期关键事实；模型缺失或超时降级为截断；压缩后再按 `max-history-tokens`（默认 4000）做 Token 级硬裁剪：System 全部保留、当前提问必留、其余从最近往前保留，放不下即丢弃（commit `0ed790c`） |
| 检索指标 / 来源 / 上下文同源 | 已完成 | `coverageRate` / `hitRate` 改为基于预算裁剪后的同一份列表；`retrieveInfo` 新增 `contextKeptCount` / `contextBudgetTokens` / `contextTrimmed`，`retrievedCount` 保留“检索返回条数”原义；Agent 分支来源改为步骤内实际喂给模型的 chunk，计划不检索时为空（commit `27fce99`、`7a6070f`） |
| 多模态图片问答 | 已完成 | 视觉模型（`supports_vision`）支持图片 + 文本提问，附件地址落 `wgh_chat_message.attachments` |
| 反馈闭环 | 已完成 | 消息点赞 / 点踩 + 备注，窗口满意度统计与差评运营列表 |
| 反馈 A/B 实验 | **未实现** | 评测数据集版本化已交付（写时复制、按版本归档与跨版本对比）；仍无实验分流、对照组与显著性分析 |
| 免登录分享 / Widget 嵌入 / 开放 API | 已完成 | 分享链接（有效期、访问密码、只读范围、吊销）；`widget.js` iframe 注入；API Key 开放 API 与调用日志 |
| OpenAI 兼容入口 + Python SDK | 已完成 | `/v1/models`、`/v1/chat/completions`（含 SSE 流式），复用 API Key + scope；附带 `sdk/python/wgh_rag_client.py`；仅支持基础对话参数，`n>1` / `logprobs` / tools 未支持 |
| Prompt 模板中心与输入框前缀联想 | 已完成 | 模板 CRUD、变量提取、渲染预览；输入框前缀触发候选、模糊过滤、变量逐个填写；`GET /api/prompt-templates/suggest` |
| 语义缓存 | 已完成 | 窗口级开关，相似度阈值命中后直接返回缓存答案，跳过检索与 LLM |

## Agent 与工作流

| 能力 | 状态 | 说明 |
|------|------|------|
| 单 Agent | 已完成 | Planner 拆解 → StepExecutor 执行 → Reflection 评分重试 → 汇总答复；计划 / 步骤落库；支持 HITL |
| 多 Agent 协作 | 已完成 | 6 种模式：CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL / GRAPH_ROUTED / GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER |
| 工作流编排 | 已完成 | 12 类节点：START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE / WORKFLOW / AGENT / EXPRESSION；并行 fan-out/fan-in、版本、触发器、模板市场 |
| 工作流 AGENT 节点 | 已完成 | `AgentNodeExecutor` 内置轻量 Plan-and-Execute；编辑器支持 `goal` / `maxSteps` / `timeoutMs` / `kbIds` |
| 工作流表达式节点 | 已完成 | `EXPRESSION` 节点基于 `SimpleEvaluationContext` 的受限 SpEL 沙箱：仅允许字面量、`#input` / `#last` / `#loop` / `#vars` / `#nodes`、算术/比较/逻辑/三元与白名单 `#fn.*`；拒绝 `T()`、`new`、`@bean`、赋值与实例方法；表达式长度上限 2000 字符；保存时做必填与语法校验（commit `e27723f`） |
| 工作流单步调试 | 已完成 | `POST /api/workflows/{id}/debug/step` 按节点推进并返回输入快照（渲染后配置 + 直接上游输出）与输出；游标（frontier / pendingIn / 已执行数）持久化在运行记录，可带 `runId` 续跑；HITL 节点停在 `PAUSED`；开关 `wgh.workflow.debug.enabled` 默认关闭（commit `e27723f`） |
| 模型故障转移（Agent / 工作流） | 已完成 | Planner / StepExecutor / ReflectionEngine 与工作流 LLM 节点复用 `rag-llm` 的 `BlockingModelFailover`：首响应前失败切下一个模型，已产出内容后不切链；SSE 下发 `model_switch` |
| 任务恢复（检查点与继续执行） | 已完成 | 多 Agent 步骤检查点按 `(runId, stepKey)` 跳过已完成步骤，管理端「运行中任务」合并单 Agent / 多 Agent / 工作流，并在调度开启时登记普通对话运行态（`wgh.scheduler.chat-run`）；页面按类型分栏（Agent / 多 Agent / 工作流 / 普通对话），切换时按新类型重查并丢弃过期响应（提交 `9c6b6d9`、`710ff1a`；分栏为 2026-11-24）。限制：恢复为 at-least-once，副作用已发生但检查点未落库时可能重复执行；旧数据缺 `stepKey` 时按未完成重跑 |
| Agent 长期记忆（任务级） | 已完成 | 新增 `TASK:taskId` 作用域并镜像到 `SESSION`，召回按统一预算组装为 `FAR_FACT`；可选 LLM 摘要默认关闭；按 TTL 30 天与单作用域 200 条自动清理，清理器用咨询锁单飞（提交 `ae3bbda`） |
| 代码沙箱 | 已完成（默认关闭） | 新增 `rag-sandbox` 模块：Python / JavaScript 执行、超时与输出上限、CPU / 内存限制、审计、默认拒绝网络（提交 `a4071db`）；需显式 `wgh.sandbox.enabled=true` 开启；隔离优先 `sandbox-exec` / `bwrap`，均不可用时 fail-closed；macOS `sandbox-exec` 已被 Apple 标记 deprecated，风险等级为中 |

## 平台治理

| 能力 | 状态 | 说明 |
|------|------|------|
| OIDC 资源服务器形态验签 | 已完成 | `OidcTokenVerifier` / `JwksProvider`；仅接受非对称签名，拒绝 HS256 与 `none`，校验 iss / aud / exp / nbf |
| 本地 admin / 体验账号登录 + `password_version` | 已完成 | `LocalLoginService` / `LocalTokenService`；仅 `source=local` 的 admin / 体验账号可登录；`password_version` 校验会话失效 |
| 多用户与权限体系 | 已完成 | OIDC + 本地账号双认证，RBAC（`@RequiresPermission` / `PermissionAspect`）；租户隔离与知识库/文档级全量权限不在范围内 |
| 资源归属与可见性 | 已完成 | 知识库 / 聊天窗口 / 工作流 / Prompt 模板 / 工具五类资源新增 `visibility`（`PRIVATE` 默认 / `TEAM` / `PUBLIC`），owner 复用 `create_by`；读他人 `PRIVATE` 按不存在处理（业务码 `3001`），写他人 `TEAM`/`PUBLIC` 返回权限不足（业务码 `1004`），均为 HTTP 200 + 业务码；列表/分页由 `DataScopeInterceptor` 显式过滤，内置 Prompt 模板固定 `PUBLIC`（commit `59c5eee`） |
| 角色与权限管理页面 | 已完成 | `AdminRoleController`（`/api/admin/roles`、`/api/admin/permissions`）+ `RoleManagement.vue`、`UserManagement.vue` |
| 配额模块与前后端页面 | 已完成 | 独立 `rag-quota` 模块；全局默认 + 按用户日/月配置，超限 429；对话计数为带 limit 条件的原子 UPDATE，多实例并发不超发（提交 `e02981f`）；扣费流水 `wgh_quota_charge` 与退款补偿作业 `QUOTA_REFUND`（2026-11-20）；`QuotaManagement.vue` / `MyQuota.vue` |
| 计费口径：无产出不计费（2026-11-24） | 已完成 | 新增 `StreamBillingObserver` 与 `ChatTurnContext.markOutput()`：流式首包、语义缓存命中、无检索兜底、工作流 / Agent 最终输出、多 Agent HITL 续跑等产出点显式声明「有效产出」，首个非空内容成功下发才结算；全程无产出立即 `CHARGED → REFUNDED`，已有部分内容后失败 / 停止 / 超时仍计费。对话与 HITL 续跑以 `afterCommit` 提交闸门（10s 超时）保证异步生成不早于事务提交；`usage_epoch` 让退款只回退扣费时的纪元，避免重置用量后误退新计数；HITL 续跑按「本轮已结算不扣 / 未决 `CHARGED` 复用 / 否则新扣」决策，再次停在 HITL 保持 `CHARGED` |
| 死信流水人工退款与核销（2026-11-24） | 已完成 | 自动补偿耗尽后流水进入 `DEAD`，管理端提供 `GET /api/admin/quota/refunds/{chargeId}`、`POST .../refund` 与 `POST .../write-off`；人工退款允许 `CHARGED / REFUND_FAILED / DEAD`，`SETTLED` 拒绝；核销置 `SETTLED` 且不改计数。两者写审计日志并分别打 `wgh.quota.refund{result=manual}`、`wgh.quota.write_off` 指标 |
| 计数异常默认拒绝（2026-11-24） | 已完成 | `wgh.rag.quota.fail-open-on-count-error` 默认 `false`：计数写入异常直接拒绝本次对话，返回业务码 `5004`，并打 `[ALERT]` 日志与 `wgh.quota.count.failure{result=reject}`；置 `true` 时仅告警放行且不扣费。事务内计数改为「SAVEPOINT 建行 + 有条件 UPDATE」 |
| 用户信息 Redis 缓存（2026-11-24） | 已完成 | `rag-auth` 新增 `UserInfoCacheService`：按 `userId` 缓存身份 / 账号状态字段（不含 `password_hash`、OIDC 外部标识、额度与用量），TTL 30 分钟；未命中用 Redis 分布式锁串行化回填，写路径在事务提交后删除缓存；Redis 不可用时直读数据库并降级告警。指标 `wgh.user.cache` |
| Redis 8.10.1 接入 | 已完成 | 分布式限流、分享密码失败锁定（成功清零）、嵌入授权、API Key IP 白名单；统一到 Redis 命名空间（根 `WGH_REDIS_KEY_PREFIX`），不可用时降级单机内存并计降级次数（提交 `e02981f`） |
| 多实例共享状态与跨实例取消 | 已完成 | 聊天取消标记、限流与授权统一落 Redis；新增显式停止接口，停止生成在任意实例生效，`CHAT` 会话可在其它副本被取消（提交 `e02981f`）；新增通用 Redis 分布式锁，供存储迁移与任务恢复复用 |
| 审计与成本统计 | 已完成 | `@Audit` 异步记录写操作；角色 / Agent / 多 Agent / 配额等管理写操作已覆盖（资源标识落 `detail`）；模型价格表 + 消息成本 / 模型名回填；管理端聚合统计 |
| 安全整改 | 已完成 | 首登强制改密（`password_must_change`）、审计日志敏感信息脱敏、409 映射、`WGH_INIT_ADMIN_PASSWORD` 注入 |
| 可观测性（Prometheus + 独立 9090） | 已完成 | `RagMetrics` 业务指标 + `/actuator/prometheus`；管理端口默认 `9090`，业务端口不暴露 `/actuator`；指标新增实例维度标签 `wgh.instance`，Prometheus 改为按副本发现（提交 `e02981f`）；2026-11-24 新增 `deploy/prometheus/rules/wgh-rag-alerts.yml`（5 组 12 条告警）与 `deploy/grafana/dashboards/wgh-rag-runtime.json`，抓取与规则验证见 `deploy/observability/README.md` |
| 优雅停机与探针分组（2026-11-24） | 已完成（未验证：滚动更新） | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=45s`；`management.endpoint.health.probes.enabled=true`，`liveness` 仅含 `livenessState`，`readiness` 含 `readinessState,db`；`wgh.resume.stale-minutes` 由 5 调整为 30。真实集群滚动更新与 `maxUnavailable: 0` 零 5xx 未验证 |
| 多副本调度：实例心跳与失联回收（2026-11-24） | 已完成（未验证：真实双副本） | 新表 `wgh_scheduler_instance` 记录实例启动、心跳、下线标记与车道快照（心跳 10s、存活阈值 45s、扫描 10s）；心跳超时即判定失联，其 `LEASED / RUNNING` 作业提前回收（未超上限回 `PENDING`，超限落 `DEAD`）；启动先接管同 `instance-id` 残留。管理端 `GET /api/admin/scheduler/instances` 查看存活、心跳与车道快照；新增调度指标。真实 PostgreSQL 双副本下的回收与并发领取未验证 |
| 作业对账（2026-11-24） | 已完成（未验证：真实双副本） | 周期作业 `SCHEDULER_RECONCILE`（维护车道）只对账 `WF_RUN` / `RUN_RESUME`，回看 24h、间隔 10m、单类型单轮 200 条；仅自动取消「`PENDING` 超 5m 且业务已终态或不存在」的排队作业，其余只告警 + 指标；`GET /api/admin/scheduler/reconcile` 返回本实例最近一轮报告（内存态，重启清空） |
| Kubernetes 清单与观测栈（2026-11-24） | 已交付未验证 | `deploy/k8s/` 提供 `base` + `overlays/k1-single`、`k2-multi`（2 副本、HPA 2–4、PDB、RWX PVC / OSS）、`k3-prod`（NetworkPolicy、ResourceQuota/LimitRange、TLS 等）与迁移 Job；`deploy/observability/` 提供 Prometheus + Grafana + postgres-exporter。清单渲染与 kubeconform 校验通过，未在真实集群部署；HPA 扩缩、PDB 驱逐、双副本探针未验证 |
| 分布式链路追踪（2026-11-22） | 已完成 | `rag-app` 引入 `micrometer-tracing-bridge-brave`；未配置采集端时只本地生成 `traceId` / `spanId`、不发起网络请求；日志追加 `[trace=%X{traceId:--}]`；`MdcAsyncConfig` 组合 `ContextPropagatingTaskDecorator`，线程池同时透传 MDC 与 Observation；调度作业提交写入 `wgh_execution_job.trace_id`，执行前恢复 |
| Compose 内置 PostgreSQL(pgvector) | 已完成 | `docker-compose.yml` 内置 `pgvector/pgvector:pg15`，空卷首启执行 `schema/01-schema.sql` / `seed/02-data.sql` |

## 模型与工程

| 能力 | 状态 | 说明 |
|------|------|------|
| 模型管理 | 已完成 | DB 动态模型 + OpenAI / DASHSCOPE / DeepSeek / Azure OpenAI / Ollama / 智谱 GLM / 自建 OpenAI 兼容端点，能力由 `ProviderCatalog` 统一描述；缓存热更新 |
| 模型路由与故障转移 | 已完成 | `ModelRouter#resolveChain` + `rag-llm/failover` 通用组件：`StreamingModelFailover` 驱动普通 RAG 流式切链（首 token 前 `onError` 切下一候选，独立输出缓冲 + `active`/CAS 防重复切链），`BlockingModelFailover` 驱动 Agent 与工作流 LLM 节点（首响应前失败切链）；全部候选失败返回明确业务错误，已产出内容后失败不切链、不拼接两段输出；三类链路均下发 `model_switch` |
| 多实例 JWKS 缓存共享（2026-11-22） | 已完成（开关默认开启） | 保留进程内 L1 缓存（TTL 300s、未知 `kid` 限频回源、IdP 失败沿用旧缓存）；新增 Redis L2 `RedisJwksSharedCache`，key 为 `{wgh.rag.redis.key-prefix}:auth:jwks:<sha256(jwksUri)>`，存原始 JWKS JSON；冷启动/过期先读 L2，未知 `kid` 先重读 L2 再回源并回写；Redis 异常全部吞掉降级本地。开关 `wgh.auth.oidc.jwks-shared-cache-enabled`，关闭时行为与改造前一致；无表结构变更 |
| `rag-public-access` 独立模块 + Enforcer 禁反向依赖 | 已完成 | 分享 / Widget / API Key 实现层独立；Controller 留在 `rag-controller`，依赖方向受 Enforcer 约束；与 chat/kb 通过 `rag-common` 的 `ChatShareTargetPort` / `KnowledgeBaseShareTargetPort` 解耦 |
| `rag-storage` 独立模块（local / OSS 可插拔） | 已完成 | `FileStorageService` + `LocalFileStorageService` / `AliyunOssFileStorageService` |
| `rag-agent-multi` Controller 迁入 `rag-controller` | 已完成 | `rag-agent-multi` 已无 Controller，`MultiAgentController` 位于 `rag-controller` |
| `rag-execution` 契约 + `rag-scheduler` 调度层（P0–P3） | 已完成 | `rag-execution` 零依赖契约（`JobHandler` / `RunAdapter` / 车道 / 事件 / `ExecutionStreamSink`）；`rag-scheduler` 提供三车道、`wgh_execution_job`、租约、Redis 单飞与周期作业提交；`WORKFLOW_RUN`、`QUOTA_REFUND` 两个 handler 已接入 |
| `rag-document` / `rag-eval` 拆分（P4） | 已完成 | 解析 / 切分从 `rag-kb` 拆到 `rag-document`，评测数据集与召回评测拆到 `rag-eval`；`rag-kb` 收窄为知识库 / 文件生命周期、入库编排、向量化与检索增强；`rag-controller` 仅保留 REST / SSE 适配 |
| `rag-chat` 传输无关重构 | 已完成 | service 层移除 `SseEmitter`，统一使用 `ExecutionStreamSink`；`ChatEventPublisher` / `ChatStreamHandle` 负责事件与取消；SSE 适配器 `SseExecutionStreamSink` 仅在 `rag-controller` |
| 常量统一抽取 | 已完成 | 各模块 `constant` 包；权限码、角色码、状态值、SSE 事件名、存储 key 前缀等改引用常量 |
| JDK 21 统一 | 已完成 | `maven.compiler.release=21` + Enforcer 强制 JDK 21，CI 与本地一致 |
| 测试用例与设计文档补全 | 已完成 | JUnit 5 + Mockito；`scripts/` 提供 e2e / 冒烟 / 压测；`docs/testing/test-cases.md`、`docs/design/**/*.md` 已补齐 |
| 配置分层（`application-features.yml`） | 已完成 | 功能开关与运行参数抽离到独立文件，由 `application.yml` 以 `optional:classpath` 引入；所有值等于代码默认值，文件缺失不影响启动；同时落地 Redis 命名空间根与实例指标标签（提交 `ca92e04`） |

## 上下文与工具治理（2026-11-18）

| 能力 | 状态 | 说明 |
|------|------|------|
| 历史消息 Token 级裁剪 | 已完成 | `HistoryContextBudgeter` 在分层压缩结果上再做一次硬裁剪：System 全保留、当前提问必留、其余从最近往前保留，放不下即停止；默认上限 4000 Tokens（commit `0ed790c`） |
| 检索指标 / 来源 / 上下文同源 | 已完成 | 指标、来源卡片、模型上下文使用同一份裁剪后列表；`retrieveInfo` 追加 `contextKeptCount` / `contextBudgetTokens` / `contextTrimmed`（commit `27fce99`、`7a6070f`） |
| 工具结果纳入上下文预算 | 已完成 | 单条工具结果上限 1200 Tokens、单任务累计 3000 Tokens，超出从最旧结果开始折叠；预算只作用于送入模型的副本，前端仍展示完整原文（commit `a458197`） |
| 工具调用兜底链 | 已完成 | `ToolInvoker` 统一包一层：失败分类（网络 / 限流 / 5xx 可重试，SSRF / 401/403 / 400/422 / 404 不重试）、仅幂等调用重试（最多尝试 2 次含首次，300ms 退避 ±30% 抖动）、按 toolId 熔断（连续 5 次失败开 30s，半开只放行 1 个探针）、任务级预算（50 次 / 120s）（commit `3a52ab6`） |
| 工作流 LLM 节点输入预算 | 已完成 | `WorkflowContextProperties` + `LlmNodeExecutor` 渲染后按模型窗口裁剪并告警（commit `f27704c`） |

---

[返回 README](../../README.md)
