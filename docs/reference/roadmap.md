# 路线图与后续计划

> 本文是 [README](../../README.md) 路线图章节的完整版。状态口径：**已完成** / **部分完成** / **在途**（进行中、未交付）/ **未实现**（未开始）/ **规划中**（已成稿、未开始）。第 1、2 节分别对齐两份来源文档的实际进度，不自行推测；第 3 节收录不属于这两份清单的其他方向；第 4 节汇总 2026-11-19 的平台演进四块规划。
>
> - 来源一：[长任务上下文管理与工具调用兜底 §5.1 优先级清单](../design/features/context-management/design-long-task-context-and-tool-fallback.md)
> - 来源二：[缺口开发计划 2026-11-09 任务总表](../review/gap-development-plan-2026-11-09.md)

## 1. 上下文与工具治理落地清单（P0 / P1 / P2）

**P0 — 当前版本（6 项，全部已完成）**

| 项 | 状态 | 说明 |
|----|------|------|
| 历史 Token 上限生效 | 已完成 | `HistoryContextBudgeter` 按 Tokens 而非条数截断历史；默认 4000 Tokens（commit `0ed790c`） |
| `sources` / 指标口径统一 | 已完成 | 指标、来源卡片、模型上下文使用同一份裁剪后列表；`retrieveInfo` 追加 3 个上下文字段；Agent 分支改用步骤内实际喂给模型的 chunk（commit `27fce99`、`7a6070f`） |
| 工具结果入上下文预算 | 已完成 | 单条 1200 Tokens、单任务累计 3000 Tokens，超出从最旧结果折叠；前端仍展示完整原文（commit `a458197`） |
| 工具重试 + 熔断 + 结构化错误 | 已完成 | `ToolInvoker`：失败分类、仅幂等调用重试（最多 2 次含首次，300ms 退避）、按 toolId 熔断（5 次 / 30s / 半开单探针）、任务预算（50 次 / 120s）；SSRF 与 401/403/400/404 不重试（commit `3a52ab6`） |
| 工作流 LLM 节点输入预算 | 已完成 | `WorkflowContextProperties` + `LlmNodeExecutor` 渲染后按模型窗口裁剪并告警（commit `f27704c`） |
| 缓存友好布局纪律 | 已完成 | 约定 `system` 前缀稳定、动态内容只进尾部；无代码改动，作为实现纪律遵守 |

**P1 — 下一迭代（5 项，全部已完成）**

| 项 | 状态 | 说明 |
|----|------|------|
| 统一 `ContextAssembler` | 已完成 | 新增 `rag-context` 模块，承载「按类型 + 优先级分配预算」（commit `03ccb1c`） |
| Agent 结构化状态块 | 已完成 | 结构化状态块替换 `StringBuilder` 平铺摘要，整体受 `agentStepContextMaxTokens` 约束（commit `7c8e98b`） |
| 多 Agent 结果卡片 + 故障转移接入 | 已完成 | 结果卡片、复用 `BlockingModelFailover` 与 Agent 记忆注入（commit `2afd8ae`） |
| 工具结果结构化信封 / 分页 | 已完成 | `rag-tool` 结构化结果信封 + 分页（commit `c0679f8`；测试构造器适配见 `e7318fa`） |
| 工作流节点输出上限与摘要落库 | 已完成 | 节点输出与 `context_json` 落库上限、摘要截断（commit `9be4a10`） |

**P2 — 后续版本（4 项，全部已完成）**

| 项 | 状态 | 说明 |
|----|------|------|
| 检索式记忆 | 已完成 | 会话级检索式记忆（commit `239c490`）；新增 `TASK` 作用域并镜像到 `SESSION`、可选 LLM 摘要、TTL 30 天与单作用域 200 条清理（提交 `ae3bbda`）；摘要默认关闭，向量表按首次写入的 embedding 维度建立、换维度需重建 |
| map-reduce 分段摘要 | 已完成 | 单条 chunk 饱和时触发分段摘要（commit `5bf6ce2`）；只在普通聊天链路生效，暂无集成测试 |
| 多 Agent 共享黑板 | 已完成 | 共享黑板读写接口（commit `a70753d`）；GroupChat 汇总尚未使用黑板 |
| 模型能力画像 | 已完成 | `LlmModel` 能力画像 + 切链降级策略（commit `d6cdee3`）；管理端模型表单未暴露能力档位与 function calling 字段 |

## 2. 缺口开发计划任务状态（T01–T13）

| ID | 任务 | 状态 | 说明 |
|----|------|------|------|
| T01 | 文档解析增强：OCR + 表格结构 + 清洗 | 已完成 | 扫描版 PDF 按页 OCR、版面分析、表格还原与清洗（commit `6ed9d10`） |
| T02 | 检索参数治理与自动补偿 | 部分完成 | 参数与 HYDE 已完成（commit `874f43d`）；低覆盖自动补偿（`coverageRate < 0.4` 扩 topK / 放宽阈值）未实现 |
| T02F | 窗口级检索参数 UI | 已完成 | 聊天窗口可配置 `hybridEnabled` / `queryTransformMode` / `rewriteModelId`（commit `8b52b5f`） |
| T03 | chunk 级引用与原文定位 | 已完成 | `sources` 扩 10 字段、按文档编号 `[Sn]`、`SourcePanel.vue` 三页接入（commit `96dfc42`）；`pageNo` 恒空、Agent 分支无 `[Sn]` |
| T04a | 资源归属与可见性（后端） | 已完成 | 五类资源 `visibility` + `DataScopeInterceptor` + 5 个 `PATCH .../visibility`（commit `59c5eee`） |
| T04b | 资源归属与可见性（前端） | 已完成 | 五个列表页可见性列 + `VisibilityControl.vue`（2026-11-12） |
| T05 | OpenAI 兼容入口 + Python SDK | 已完成 | `/v1/models`、`/v1/chat/completions`（含 SSE）+ `sdk/python/**`（commit `a2d9046`） |
| T06a | 生成侧评测指标补齐 | 已完成 | Faithfulness / Answer Relevancy / Context Precision / Context Recall 多 judge（commit `e814bb5`） |
| T06b | 评测数据集版本化 + CI 门禁 | 已完成 | 写时复制版本、跨版本对比、CI `retrieval-gate` 五项阈值（commit `adbdafe`） |
| T07 | 审计覆盖补齐 | 部分完成 | AdminUser 走 `AccountAuditService` 显式审计，角色 / 配额 / Agent / 多 Agent 已覆盖（commit `1307d27`）；资源 ID 仍只落 `detail` |
| T08 | URL/HTTP 数据源增量同步 | 已完成 | 单页抓取、`content_hash` 判重、变更换版本、404/410 清理、SSRF 逐跳校验、可选定时同步（commit `855f61f`）；robots.txt 与整站爬取未做 |
| T09 | 工作流表达式节点 + 单步调试 | 已完成 | `EXPRESSION` 受限 SpEL 节点 + `POST /api/workflows/{id}/debug/step`（commit `e27723f`）；不支持任意脚本执行 |
| T10 | 大文件分片上传 / 断点续传 | 已完成 | 三段式接口、顺序分片、`content_hash` 判重、会话归属校验、24h 清理（commit `140375a`） |
| T11 | 模型提供商扩展（Azure / Bedrock / Ollama） | 已完成 | Azure OpenAI / Ollama 等扩展已交付（commit `028f1bc`）；Bedrock 未做 |
| T12 | 多实例与可观测 | 已完成（2026-11-19；2026-11-22、2026-11-24 补齐） | Redis 统一命名空间共享状态、跨实例取消、配额原子自增、实例维度指标与部署配置（提交 `e02981f`）；JWKS 缓存共享与分布式链路追踪已于 2026-11-22 落地（见 §3）；2026-11-24 补齐调度实例心跳 / 失联回收、作业对账、SSE 事件重放、用户信息缓存与 K8s K1/K2/K3 清单与观测栈（多副本与集群行为未验证，见 [已知限制](known-limitations.md)） |
| T13 | Agent 长期记忆 / 代码沙箱 | 已完成（2026-11-19） | 任务级长期记忆（`TASK` + `SESSION`、摘要与 TTL/容量清理，提交 `ae3bbda`）；新增 `rag-sandbox` 代码沙箱模块（提交 `a4071db`），默认关闭、需显式开启；macOS 隔离已 deprecated |

## 3. 其他后续方向

以下方向不属于上述两份清单，**未排期、不代表交付承诺**。

| 优先级 | 方向 | 当前状态 | 说明 |
|--------|------|----------|------|
| P0 | 模型故障转移补全 | 已完成（首 token 前切链） | 普通 RAG 首个 token 前的连接失败 / 超时 / 4xx / 5xx 已进入切链；部分输出后失败与全部候选失败均有明确错误收口（commit `e496f71`）；切链逻辑已抽成 `rag-llm` 的 `FailoverAttempt` / `StreamingModelFailover` / `BlockingModelFailover` / `ModelFailoverService`（commit `8e357bb`） |
| P1 | Agent / 工作流接入故障转移 | 已完成 | Planner / StepExecutor / ReflectionEngine 与工作流 LLM 节点复用 `BlockingModelFailover`；Agent 恢复 / 重试入口回查窗口路由组（commit `8e357bb`） |
| P1 | 存储迁移与孤儿对象清理 | 已完成（2026-11-19） | `local` ↔ `oss` 迁移服务 + Redis 任务状态 + 管理端接口（提交 `e4155c6`）；孤儿扫描默认只报告，需显式开启删除 |
| P2 | 多实例 JWKS 缓存共享 | 已完成（2026-11-22） | 保留进程内 L1，新增 Redis L2 `RedisJwksSharedCache`（原始 JWKS JSON、未知 `kid` 先重读 L2 再回源并回写）；开关 `wgh.auth.oidc.jwks-shared-cache-enabled` 默认开启，关闭时与改造前一致，Redis 异常降级本地；无表结构变更 |
| P2 | 分布式链路追踪 | 已完成（2026-11-22） | `micrometer-tracing-bridge-brave` 本地生成 `traceId` / `spanId`，日志输出 `[trace=...]`；`MdcAsyncConfig` 透传 MDC 与 Observation；调度作业写入 / 恢复 `wgh_execution_job.trace_id`，采集端待接入时才导出 |
| P1 | 多副本调度与 SSE 事件重放 | 已完成（dev 运行时已验证；真实双副本与跨副本重连未验证）（2026-11-24） | 实例注册 / 心跳 / 失联回收、作业对账、管理端实例与对账接口、租户调度策略与运行槽；SSE Redis 缓冲 + `Last-Event-ID` 重放与前端自动重连。运行时端到端已在 dev 通过（含 attach 兜底轮询、`fromSeq=0` 全量重放、作业线程恢复属主角色、payload 过滤 `null`、续跑事务边界 5 条修复）；真实双副本回收 / 对账与跨副本实时 tail 未验证 |
| P2 | Kubernetes 清单与观测栈 | 已交付未验证（2026-11-24） | `deploy/k8s/` 的 K1/K2/K3 清单与迁移 Job、Prometheus 告警规则与 Grafana 面板；仅离线渲染与 schema 校验，未在真实集群部署 |
| P2 | 整站爬取与多源接入 | 未实现 | robots.txt 校验、站点爬取 / Sitemap、PDF / RSS / 第三方文档源同步；单页 URL 连接器已交付（T08 未做部分） |
| P3 | 反馈 A/B 对比 | 未实现 | 版本化评测集已交付；仍缺实验分流、对照组与显著性分析 |
| P3 | 图片 OCR | 部分完成 | 扫描版 PDF 已按页 OCR（`TessOcrService`，commit `6ed9d10`）；独立图片文件（PNG / JPG）未接入 |

## 4. 平台演进规划（2026-11-19）

### 4.1 四块状态

状态口径：**已完成** / **已交付未验证** / **部分完成** / **规划中**（已有成稿规划文档、尚未开始实施）。四块互有依赖，阶段编号与验收以规划文档为准。

| 方向 | 状态 | 阶段划分 | 说明 |
|------|------|----------|------|
| 任务调度中心 | **部分完成（S0–S3 已落地，dev 运行时端到端已通过）** | S0 止血 → S1 后台作业化 → S2 租户策略生效 → S3 集群语义 | `rag-execution` 契约、`rag-scheduler` 三车道、`wgh_execution_job` 作业表、租约与 Redis 单飞、`RecurringJob` 周期提交已落地；`WORKFLOW_RUN`、`QUOTA_REFUND`、`EVAL_RUN`、`AGENT_PLAN`、多 Agent 与聊天工作流已作业化。2026-11-24 补齐：实例注册与心跳、失联回收、作业对账、管理端实例 / 对账接口、租户调度策略与运行槽、`wgh_execution_event` 与 `Last-Event-ID` 重放；运行时端到端已在 dev 通过（含 5 条修复）。真实 PostgreSQL 双副本下的回收 / 对账未验证。详见 [design-execution-scheduler-center.md](../design/features/platform/design-execution-scheduler-center.md) |
| 多租户隔离 | **部分完成（阶段 0–3 在 dev 生效；prod/test 未切换）** | 阶段 0 修越权 → 1 租户模型与上下文 → 2 数据面全量隔离 → 3 企业级加固 → 4 混合分层（按需） | 阶段 0–2 已落地（越权收口、`wgh_tenant` 与上下文传播、MyBatis 插件 + 34 表 `tenant_id`、存储/向量租户前缀）；阶段 3 在 `wgh_rag_dev` 启用：41 表 / 52 策略，应用已以 `wgh_app` + 事务级 GUC 连接并通过阶段 B/C，`prod` / `wgh_rag_test` 未切换。租户管理端 9 端点、配额与 `/admin/tenants` 页面已上线；平台级跨租户扫描已落地。详见 [design-multi-tenancy-isolation-roadmap.md](../design/features/platform/design-multi-tenancy-isolation-roadmap.md) |
| 独立向量数据库 / 搜索引擎 | **部分完成（V0 已落地，V1 起步）** | V0 可替换性基线 → V1 搜索引擎 → V2 独立向量库（按阈值）→ V3 多后端治理 | V0 已完成：`VectorStorePort` / `KeywordSearchPort` 与路由、`wgh_kb_chunk` 事实层（默认关闭，双写 / 回填 / 对账）；V1 起步：双读对比（只记指标）、按 KB 白名单灰度、RRF 权重可配、双路删除与分后端指标，默认行为不变；独立向量库仍未接入。详见 [design-vector-store-and-search-engine.md](../design/features/platform/design-vector-store-and-search-engine.md) |
| Kubernetes 级编排 | **部分完成（K0–K3 清单已交付，未集群验证）** | K0 容器基线 → K1 单副本可编排 → K2 多副本 → K3 生产级 | 只做应用与依赖的部署编排，不把业务调度迁到 K8s；K1/K2/K3 清单与观测栈已落地：优雅停机与探针分组（K0）、单副本基线（K1）、2 副本 + HPA/PDB + OSS 共享存储（K2）、3 副本 + NetworkPolicy/ResourceQuota/TLS（K3）。清单仅做离线渲染与 schema 校验，未在真实集群部署。详见 [design-kubernetes-orchestration.md](../design/features/platform/design-kubernetes-orchestration.md) 与 [deploy/k8s/README.md](../../deploy/k8s/README.md) |

### 4.2 阶段与前置关系

以下四块均已有规划文档：**任务调度中心 S0–S3 已落地，dev 运行时端到端已通过（真实双副本未验证）；多租户阶段 0–3 在 dev 生效（prod/test 未切换）；向量/搜索 V0 已落地、V1 起步；Kubernetes 编排已交付 K1/K2/K3 清单（未集群验证）**。阶段编号与验收以各自文档为准。此前「暂不规划」的表述已废止。

| 块 | 规划文档 | 阶段 | 前置关系 |
|----|----------|------|----------|
| 任务调度中心 | [design-execution-scheduler-center.md](../design/features/platform/design-execution-scheduler-center.md) | S0 → S1 → S2（已落地，dev 已验证）→ S3（已交付，真实双副本未验证） | 与多租户阶段 0/1 同批；S0 是 K8s 多副本前置 |
| 多租户隔离 | [design-multi-tenancy-isolation-roadmap.md](../design/features/platform/design-multi-tenancy-isolation-roadmap.md) | 阶段 0 → 1 → 2（已落地）→ 3（dev 生效，prod/test 未切换）→ 4（按需） | 阶段 0 必须先于任何多副本扩容；阶段 3 切换需先完成连接用户与 RLS 核对 |
| 独立向量数据库 / 搜索引擎 | [design-vector-store-and-search-engine.md](../design/features/platform/design-vector-store-and-search-engine.md) | V0（已落地）→ V1（起步）→ V2（按阈值）→ V3 | V0 的事实层（chunk 原文独立化）是替换前置；V1 灰度能力默认关闭 |
| Kubernetes 级编排 | [design-kubernetes-orchestration.md](../design/features/platform/design-kubernetes-orchestration.md) | K0 → K1 → K2 → K3（清单均已交付，未集群验证） | K2 依赖 S0/A 阶段完成 |

对应的现状口径见本文 §4.1 与 [已知限制](known-limitations.md)；四块互不替代：调度中心解决「谁来执行」，多租户解决「数据归属」，向量/搜索解决「检索容量与质量」，K8s 解决「部署与弹性」。

---

[返回 README](../../README.md)
