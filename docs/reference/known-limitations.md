# 已知限制

> 本文是 [README](../../README.md) 已知限制章节的完整版。2026-11-24 交付的计费口径、多副本与 Kubernetes 编排能力，凡未在真实多副本 / 真实集群验证的项，均在「限制」列显式标注「未验证」；未执行的迁移脚本单列。
>
> 下文先给 6 类摘要，再按 48 条逐项展开。

## 摘要

| 类别 | 关键限制 | 影响 / 建议 |
|------|----------|-------------|
| 权限体系 | 租户隔离已落地（dev 以 `wgh_app` + RLS 生效，prod/test 未切换）；文档级权限未全量覆盖；五类核心资源已支持 owner + 可见性 | 切 prod/test 前需完成连接用户与 RLS 核对；默认租户不做向量过滤，历史缓存/记忆向量无 `tenantId` |
| 多副本与集群 | 调度中心运行时端到端已在 dev 通过；真实双副本下的失联回收、跨副本 SSE 重连，以及滚动更新与 HPA / PDB 均未在真实多副本或集群验证 | 生产启用前需在目标环境做双副本与集群演练 |
| 配额与计费 | 「无产出不计费」与死信人工退款 / 核销已交付；计数异常默认拒绝（业务码 `5004`）；`20261124` 两个迁移脚本状态按库分层 | 上线前准备回滚与人工对账 SQL；迁移脚本按库核对后再启用对应能力 |
| 存储与 OSS | `mode=shared` 启动自检与 local → oss 回落读已交付；真实 OSS 读写、容器内独立挂载卷判定未验证 | 生产切换到 OSS 前需做读写与回落读演练 |
| 检索与评测 | 单库 PostgreSQL + pgvector，无独立向量库 / 搜索引擎；无反馈 A/B 实验；生成侧 CP / CR 依赖参考答案 | 超大规模语料需评估性能上限；A/B 与 CP / CR 需后续补能力与字段 |
| 接入与交互 | Widget 仅 iframe 注入、无 `postMessage`；OpenAI 兼容仅基础对话参数；移动端 / 国际化未支持 | 复杂参数与宿主双向通信需走原生接口或后续扩展 |

## 逐项清单

| 类别 | 限制 | 影响 / 建议 |
|------|------|-------------|
| 权限体系 | 已提供角色 / 权限管理页面与接口；五类核心资源（知识库 / 聊天窗口 / 工作流 / Prompt 模板 / 工具）已支持 owner + 可见性；租户隔离已落地：应用层 + MyBatis 插件 + RLS，dev 以 `wgh_app` + 事务级 GUC 生效，**`prod` / `wgh_rag_test` 未切换**；文档级权限仍不在范围内 | 切换 prod/test 前先完成 `wgh_app` 连接与 RLS 核对；文档级权限规划见 [多租户隔离落地规划](../design/features/platform/design-multi-tenancy-isolation-roadmap.md) |
| 可观测性 | 已提供 Prometheus 指标与管理端口（9090）、`X-Request-Id` 日志关联；已接入 Micrometer Tracing（Brave），本地生成 `traceId` / `spanId` 并写入日志与 `wgh_execution_job.trace_id`，未配置采集端时不导出 | 跨进程可视化仍需接入 Zipkin / OTLP 或日志平台；未配置采集端时只能按本地日志排查；周期作业（`RecurringJob`）没有提交请求，`trace_id` 为空，需用 `execution_id` 关联；默认全量采样，高吞吐环境应下调采样率 |
| 部署编排 | 已提供前后端 Dockerfile、全栈 docker-compose 与 `deploy/k8s/` 的 K1/K2/K3 清单（含迁移 Job）；清单仅做离线渲染与 kubeconform 校验，未在真实集群部署；K3 的 GitOps / 准入策略 / 镜像扫描 / SBOM 不在清单范围 | 上线前需在目标集群验证 StorageClass（RWX）、Ingress 注解、镜像 digest、HPA / PDB 与滚动更新；见 [deploy/k8s/README.md](../../deploy/k8s/README.md) |
| 调度中心（2026-11-20；2026-11-24 补多副本） | `wgh_execution_job`、三车道、租约、Redis 单飞与 `RecurringJob` 已落地；2026-11-24 新增实例注册 / 心跳 / 失联回收、作业对账、管理端实例 / 对账接口、租户调度策略与运行槽，运行时端到端已在 dev 通过（含 attach 兜底轮询、`fromSeq=0` 全量重放、作业线程恢复属主角色、payload 过滤 `null`、续跑事务边界 5 条修复）。**未验证**：失联实例提前回收、启动接管、对账自动修复在真实 PostgreSQL 双副本上的行为；H2 单测覆盖不到 `FOR UPDATE SKIP LOCKED` 与跨副本单飞；`CHAT` 仍走统一异步执行器（仅登记运行态），未作业化；存储迁移类 handler 尚未接入调度；`daily_job_limit` / `rate_per_minute` / `time_window_json` 未强制执行 | 生产启用前需在真实双副本环境验证领取 / 回收 / 对账；未接入的链路继续沿用原有执行方式 |
| 配额与计费口径（2026-11-20；2026-11-24 改口径） | 计费已改为「无产出不计费」，首个有效产出才结算；`wgh_quota_charge` 与 `usage_epoch` 已登记基线，存量库需按库核对：`wgh_rag_dev` 的 `usage_epoch` 两列实测已存在，`wgh_rag_test` 未核实，缺列时再执行 `20261124/02_quota_usage_epoch.sql`。补偿作业依赖 `wgh.scheduler.enabled=true`（关闭时由本地兜底线程运行）；`DEAD` 已提供管理端人工退款 / 核销，但无批量操作 | 上线前准备回滚与人工对账 SQL；`DEAD` 需管理员逐笔处理 |
| 计费提交闸门（2026-11-24） | 异步生成等待外层事务提交（10s 超时），回滚或超时不生成、不结算；`usage_epoch` 让旧流水退款不误退重置后的新计数。**未验证**：高并发下提交闸门超时路径与分享次数回补的并发边界 | 极端并发下分享入口可能短暂少计一次使用次数（条件 `use_count > 0` 保证不为负） |
| 计数异常策略（2026-11-24） | 默认 `fail-open-on-count-error=false`：数据库计数异常直接拒绝对话并返回业务码 `5004` | 数据库抖动会造成对话失败，这是有意取舍；临时开启 fail-open 会放出一批无扣费请求，需按 `wgh.quota.count.failure{result=allow}` 事后排查 |
| SSE 事件重放（2026-11-24） | 后端 Redis 缓冲 + `Last-Event-ID` 重连、前端自动重连与信令提示已交付。**未验证**：跨副本重连（Pod A 生成、Pod B 接管）的事件完整性与时延；Redis 故障注入下的降级路径；刷新页面后对话保持运行 / 完成、多 Agent Run 不再被误标 `FAILED` 的端到端验证。前端收到 `stream_replay_gap` / `stream_replay_incomplete` 只展示提示，不自动补拉历史 | 依赖 Redis 可用；无法补齐时需用户刷新会话。Ingress 去掉 Cookie 亲和后的实际表现依赖该能力，未验证 |
| 用户信息缓存（2026-11-24） | 按 `userId` 缓存身份 / 账号状态字段，TTL 30 分钟，写后删除缓存；Redis 故障降级直读库。直接改库（绕过应用）不会主动失效缓存 | 绕过应用的账号状态变更最长 30 分钟后才可见 |
| 优雅停机与探针（2026-11-24） | 45s 优雅停机覆盖在途请求，但超长 SSE 仍会被切断，依赖客户端重连补齐；`readiness` 含 `db`、`liveness` 只看进程活性。**未验证**：真实集群双副本滚动更新、`maxUnavailable: 0` 零 5xx、PDB 驱逐与 HPA 扩缩 | 滚动更新前确认 `terminationGracePeriodSeconds`（建议 60s）大于 45s + `preStop` |
| `20261124` 迁移脚本（2026-11-24） | 分层状态：`wgh_rag_dev` 的 `wgh_quota_usage.usage_epoch`（bigint default 0）与 `wgh_quota_charge.usage_epoch`（bigint 可空）实测已存在；`wgh_scheduler_instance` 此前缺失，正在按 `20261124/01_scheduler_instance.sql` 补建；`wgh_rag_test` 两处状态未核实。两个脚本均不纳入迁移 Job | 部署前按库核对：`wgh_scheduler_instance` 未建时执行 01（须先于启用多副本的 Pod 启动）；`usage_epoch` 列缺失时执行 02（退款 SQL 直接比较该列）；`wgh_rag_test` 需先核实再补跑 |
| OSS 真实读写（2026-11-24） | `mode=shared` + `type=oss` 的启动自检、`read-fallback-enabled` 回落读已交付。**未验证**：OSS 真实上传 / 下载 / 删除，以及 local → oss 回落读链路；`shared + local` 的独立挂载卷判定只在本地临时进程验证过 | 生产切换到 OSS 前需在目标环境做读写与回落读演练 |
| 限流 | Redis Lua 分布式滑动窗口，故障时降级单机内存 | Redis 故障期间多实例计数不共享、重启清零；建议 Redis 高可用 |
| Widget | 仅 iframe 注入，无 `postMessage` 双向通信；`frame-ancestors` 需网关 / 静态服务配置 | 宿主页无法读取对话事件；手工 iframe 仍可能绕过 `widget.js`，需 CSP 兜底 |
| 分享密码 | 失败 5 次锁定 15 分钟（可配置），无验证码 | 换 IP 可继续尝试；强防护可叠加网关限流 / 验证码 |
| API Key | 无自动轮换；已有可选 IP 白名单 | 泄露后需人工吊销；建议始终配置 IP 白名单 |
| 真实 IP | 默认只信任 TCP 对端，XFF 仅在可信代理下生效 | 代理拓扑变化时需同步 `trusted-proxies`，配置过宽会引入伪造风险 |
| 反馈与评测 | 评测数据集已支持版本化（写时复制、按版本归档与跨版本对比，2026-11-18），但仍无 A/B 实验对比；生成侧 Context Precision / Recall 依赖参考答案字段，当前标注表只有相关 chunk，缺参考答案时返回不可计算 | A/B 对比需后续补实验分流与显著性分析；需先补参考答案字段才能端到端产出 CP / CR |
| 多模态 | 视觉模型支持图片输入；扫描版 PDF 按页 OCR；独立图片文件不入库 | OCR 依赖本机 Tesseract 与 `chi_sim+eng` 语言包，纯图片知识库仍需先转 PDF |
| OpenAI 兼容 | 仅 `/v1/models` 与 `/v1/chat/completions`，`n>1`、`logprobs`、function calling / tools 未支持；`model` 只能映射已启用聊天窗口 | 复杂参数与底层模型直连需走原生 `/api/chat` 接口 |
| OCR | 依赖本机 `tesseract` 可执行文件与语言包；单页 20 字触发、文档最多 50 页、单页 60s 超时；栏检测优先于表格 | 引擎缺失时静默降级为文本抽取；纯两列表格可能按双栏处理 |
| 检索 | 单库 PostgreSQL + pgvector（每知识库一张物理表），关键词路为 pg_trgm，无独立向量库 / 搜索引擎 | 超大规模语料需评估性能上限；替换路径见 [独立向量数据库 / 搜索引擎规划](../design/features/platform/design-vector-store-and-search-engine.md) |
| 模型故障转移 | 普通 RAG、Agent（Planner / Step / Reflection）与工作流 LLM 节点统一复用切链组件，覆盖首个 token / 首个响应前的失败切链；**已产生内容后失败不切链、不回滚、不重发、不拼接第二个模型输出**，直接 `error` 结束 | 已产出内容后的续接策略暂不提供；Agent 恢复 / 重试入口按计划绑定的窗口路由组解析链路 |
| 存储切换 | 已提供历史对象迁移作业（提交 `e4155c6`），但切换本身不自动触发迁移；任务状态与取消标记存 Redis（TTL 24h），跨实例单飞 | 需在管理端显式发起并等待完成；Redis 不可用时无法落任务状态 |
| OSS 删除 | 删除失败仍只记 WARN，数据库完成逻辑删除，可能残留孤儿对象；已提供孤儿扫描与清理（提交 `e4155c6`） | 默认仅报告（`delete=false`），需显式开启；最后修改时间 60 分钟内的对象受保护 |
| 大文件上传（2026-11-18） | 已支持分片上传与页面内断线续传；不做跨页面刷新恢复（刷新后需重新选择文件，会话本身仍可复用）；OSS 未使用原生 multipart，分片先落对象存储、合并后仍走单次 `putObject`；单文件存储上限代码默认 200MB，但 `application.yml` 的 `wgh.storage.*.max-file-size-mb` 显式配 50（可用 `WGH_STORAGE_MAX_FILE_MB` 覆盖），单次直传仍受 servlet multipart 50MB 限制，解析侧 `wgh.file.max-single-file-mb` 仍默认 50MB | 传 200MB 文件需走分片并同时调大存储与解析两处上限 |
| 多实例 JWKS | 已支持进程内 L1 + Redis L2 共享缓存（`wgh.auth.oidc.jwks-shared-cache-enabled`，默认开启）；未知 `kid` 会限频回源，IdP 失败沿用旧缓存，Redis 异常降级本地 | 关闭共享后按单实例缓存工作，多实例需接受轮换后的刷新时间差；开启时需保证 Redis 可用性 |
| 调度作业 trace_id 迁移（2026-11-22） | `sql/migrations/20261122/01_execution_job_trace.sql` 在 `wgh_rag_dev` 执行过（连跑两次验证幂等）；实测 `wgh_rag_test` 的 `wgh_execution_job.trace_id` 列也已存在 | 其他存量库部署前仍需按库核对 `trace_id` 列；缺失时该库作业提交写入 `trace_id` 会失败，追踪关联缺失 |
| 移动端 / 国际化 | 仅桌面端布局，无 i18n | — |
| 数据源连接器（2026-11-18） | 已支持单页 URL 抓取（Jsoup 正文抽取）、按 `content_hash` 判重不重复入库、内容变化生成新文件并逻辑删除旧文件、404/410 清理旧文件、按间隔增量同步与运行记录；仍未实现 robots.txt 校验、整站爬取、非 HTML 数据源（PDF / RSS / Sitemap / 第三方 API） | 仅适合“少量单页 + 定时重抓”；站点治理与批量抓取需另行接入爬虫或第三方数据源 |
| 数据源定时同步（2026-11-18） | 调度器默认关闭，需显式 `WGH_CONNECTOR_SCHEDULER_ENABLED=true`；单轮按 `batchSize` 限量，间隔为 0 的连接器只支持手动同步；单实例进程内锁，多实例之间不互斥 | 多实例部署需接受重复抓取（`content_hash` 仍可避免重复入库）或改为分布式锁 |
| 工作流表达式与单步调试（2026-11-18） | 表达式仅支持受限语法子集与固定 `#fn.*` 函数集，不支持自定义函数 / Bean / 类型引用；单步调试默认关闭，只按拓扑串行推进，调试运行使用创建会话时的图快照 | 复杂计算需拆成多节点或改用 TOOL 节点；调试期间修改图不会影响已开始的会话 |
| 评测门禁（2026-11-18） | CI 门禁使用固定 fixture（预置 `relevant` / `retrieved`），不验证真实检索链路，也不依赖 Key / 网络 / 模型 | fixture 只能防止指标口径与阈值回退；真实检索质量仍需在线评测或人工回归 |
| 存量库批次 3 迁移（2026-11-18） | 存量库需按序执行 `sql/migrations/20261118/01_eval_dataset_versioning.sql`（3 表 + 3 索引）与 `02_kb_connector.sql`（2 表 + 3 索引），脚本均幂等；新库走 `01-schema.sql`，不要重复执行 | 未执行时评测数据集 / 连接器相关接口会因表不存在报错 |
| 引用溯源（2026-11-12） | `pageNo` 目前恒为空（解析链路只做段落摊平，无页级映射）；纯 Agent 分支上下文未注入 `[Sn]`；同名文件跨知识库会合并为一张来源卡片 | Agent 回答中的编号可能无对应卡片；需要页码或 Agent 引用时需后续补页级映射与 Agent 上下文编号 |
| 上下文与工具兜底（2026-11-18） | 工具熔断为单实例内存态，重启即清空，按 toolId 生效且不跨节点、不跨任务；历史 Token 裁剪按估算值而非精确 tokenizer；工具结果预算只作用于送入模型的副本，前端仍显示完整原文；`RagChatService` 的工具循环暂无集成测试覆盖 | 多实例部署各自持有熔断状态；估算误差会用少量真实窗口余量兜底 |
| 多 Agent 故障转移（2026-11-18） | 单 Agent、工作流 LLM 节点与多 Agent（worker / manager，commit `2afd8ae`）均已接 `BlockingModelFailover`；已产出内容后失败仍不切链 | 已产出内容后的续接策略暂不提供 |
| 工作流上下文（2026-11-18） | 工作流 LLM 节点输入预算已交付（commit `f27704c`）；节点输出与 `context_json` 已加落库上限并按摘要截断（commit `9be4a10`） | 超长节点输出只保留摘要与受限原文，完整内容需回查节点输出或日志 |
| 检索式记忆（2026-11-18） | 已接入 `SESSION` 与 `TASK` 作用域，任务结果镜像到会话；可选 LLM 摘要默认关闭（`llm-summary-enabled=false`）；清理默认保留 30 天、单作用域 200 条、每小时执行一次（提交 `ae3bbda`） | 记忆向量表按首次写入的 embedding 维度建立，更换维度需重建；开启 LLM 摘要会为每条记忆增加一次模型调用 |
| 任务恢复（2026-11-22 移除） | 用户触发的【继续执行】入口与接口已下线（`pending-task` / `resume-task` / `admin/tasks/resume`），停在 RUNNING/PENDING 的任务改由调度中心按异步执行点处置；管理端「运行中任务」页仅保留只读观测 | 需要人工干预中断任务时，以运行记录与调度中心作业状态为准 |
| 代码沙箱（2026-11-19） | 默认关闭（`wgh.sandbox.enabled=false`）；隔离优先 `sandbox-exec` / `bwrap`，均不可用时 fail-closed，仅显式允许才降级；macOS `sandbox-exec` 已被 Apple 标记 deprecated，属用户态沙箱、风险等级中；CPU / 内存限制为尽力而为 | 生产建议仅在 Linux + bwrap 或容器内开启，并保持默认拒绝网络 |
| map-reduce 分段摘要（2026-11-18） | 仅在普通聊天链路生效，Agent / 多 Agent / 工作流链路未接；暂无集成测试覆盖 | 长任务链路仍需单独接入与回归 |
| 多 Agent 黑板（2026-11-18） | 已提供共享黑板读写接口，但 GroupChat 汇总未使用黑板，仍按对话消息汇总 | 需要跨 Agent 共享中间结论时需显式写入黑板 |
| 模型能力画像（2026-11-18） | 后端有 `LlmModel` 能力画像与切链降级策略；管理端模型表单未暴露能力档位与 function calling 字段 | 需通过接口或数据库配置，页面暂不支持 |
| 资源可见性（2026-11-12；2026-11-24 补租户） | 五类资源支持 owner + 可见性，`TEAM` 与 `PUBLIC` 判定等价（均为「非 owner 可读」）；`PUBLIC` 已收敛为租户内公开；租户隔离已落地（dev 生效，prod/test 未切换）；存量库执行 `sql/migrations/20261113/01_resource_visibility.sql` 补列与索引（幂等）；历史 `create_by='system'` 的资源升级后默认仅管理员可见 | 升级前需备份并执行该迁移脚本；跨租户共享需平台标记（未实现），文档级成员模型仍未引入 |
| 权限体系：多租户与 RLS（2026-11-24） | 租户隔离已在应用层 + MyBatis 插件 + RLS 落地，dev 以 `wgh_app` + 事务级 GUC 生效；**`prod` / `wgh_rag_test` 未切换**（仍 `postgres`，RLS 不生效）；默认租户不做向量过滤，改造前写入的语义缓存 / Agent 记忆向量无 `tenantId`；`admin` 即平台管理员，`username` 仍全局唯一；平台角色 `wgh_platform` 只能读审计 / 调用日志，业务表不可见 | 切 prod/test 前配置 `WGH_DB_USERNAME=wgh_app` 与口令 Secret，按 `09`→`10`→`11`→`13`→`12` 执行并用 `15` 核对；跨租户运维需按租户切换上下文 |
| 权限体系：租户管理端与配额（2026-11-24） | 9 个端点与 `/admin/tenants` 页面已交付（含逻辑下线、租户配额、调度策略）；`wgh_tenant_quota_config` 与 4 个租户权限码已并入 `01-schema.sql`，dev 已执行；**test / prod 存量库尚未执行**（新库建库即包含） | test / prod 存量库上线租户管理端前执行 `16_tenant_admin.sql` 并核对 4 个权限码 |
| 检索与评测：pgvector trusted 与平台计数（2026-11-24） | dev override 使用 `wgh/pgvector:pg15-0.8.7-trusted`；trusted 只放开非超级用户的扩展安装权限，不把 `extowner` 改写为超级用户；**生产未采用该镜像**，扩展仍由 DBA 预建。`17_vector_table_count_fn.sql` **dev 已执行并核对**（`prosecdef=t`、属主 `postgres`、ACL 仅属主与 `wgh_app`、PUBLIC 已撤销；postgres 直查与 `wgh_app` 登录调用均为 8，与 `wgh_knowledge_base` 直查一致）；**prod/test 未执行** | 生产沿用超级用户预建扩展；prod/test 执行 `17` 后再依赖 `wgh.retrieval.vector.table_count`，否则会被 RLS 过滤为 0 |
| 调度中心：平台级全租户扫描（2026-11-24） | `TenantDirectory` / `PlatformScanTemplate` / `wgh.platform-scan.*` 已落地，对账走三段式，连接器、续跑、配额退款、上传 / 记忆 / 语义缓存 / 存储孤儿共 7 个入口按租户遍历；默认串行、单轮最多 200 租户，超出截断到下一轮；单租户失败只告警并计入失败数，不中断整轮 | 租户增长后按指标调整并发与单轮上限；跨租户报表需结合 `tenantsTotal / tenantsFailed` 判断完整性 |
