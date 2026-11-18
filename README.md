# Easy AI System

> 面向私有化部署的企业知识库问答与 RAG 中台：把文档治理、检索增强、流式问答、Agent / 工作流编排、分享与开放接入收敛到一套可审计、可配额管理的系统。

---

## 1. 定位与价值

**一句话**：把分散在企业内部的文档变成可检索、可引用、可评估、可治理的问答能力，并以聊天窗口、分享链接、Widget 和 API 对外提供。

它解决的是"知识在哪里、答案从哪来、质量怎么衡量、谁能用、用了多少"这一整条链路，而不只是把问题拼进一段 prompt：

- **文档到答案**：上传 → 解析 → 切分 → 向量化 → 混合检索 → 带引用的回答，支持增量维护与重新切分。
- **质量可度量**：SSE 实时输出召回覆盖率与命中率；有标注后可计算 Recall@K / Precision@K / MRR / NDCG，并提供离线评测脚本。
- **多入口复用同一套能力**：管理端调试、免登录分享、第三方站点 Widget、API Key 开放 API 都复用同一套模型、知识库、工具、配额与审计。
- **可治理**：OIDC / 本地账号双认证、RBAC、配额与限流、审计日志、成本统计、可观测性。

### 1.1 与"直接调用大模型"的区别

| 维度 | 直接调用大模型 | 本系统 |
|------|----------------|--------|
| 知识来源 | 依赖模型参数或临时拼 prompt | 文档入库、切分、向量与关键词索引，内容可更新、可删除 |
| 回答可追溯 | 较难说明依据 | 回答附带引用来源，前端按文档去重展示 |
| 质量评估 | 主要靠人工感觉 | 检索侧 Recall@K / Precision@K / MRR / NDCG，生成侧 LLM-as-Judge |
| 权限与审计 | 需自行实现 | OIDC 资源服务器 + RBAC、审计日志、配额与限流 |
| 对外渠道 | 需自行开发 | 免登录分享、iframe Widget、API Key 开放 API |
| 复杂任务 | 单轮问答为主 | 单 Agent、多 Agent、工作流编排与 HITL 人工确认 |

### 1.2 能力边界

- **做**：文档解析与检索增强、流式问答、多轮会话与上下文压缩、Agent 规划执行、多 Agent 协作、工作流编排、分享 / 嵌入 / 开放 API、配额限流、审计与成本统计。
- **不做**：多租户隔离、独立向量数据库 / 搜索引擎（单库 PostgreSQL + pgvector）、图片 OCR、移动端与国际化、Kubernetes 级别的水平扩展编排。

---

## 2. 典型应用场景

| 场景 | 典型用法 | 系统提供的支撑 | 边界 |
|------|----------|----------------|------|
| **企业内部知识库问答与员工自助** | 制度、手册、SOP 入库，员工在聊天窗口提问，回答附带原文出处 | 12 种文档格式解析、9 种切分策略、混合检索、SSE 流式回答、引用来源与检索指标 | 不替代 OA / 工单流程；文档需先上传或经 API 写入 |
| **客服 / 工单坐席辅助** | 坐席把客户问题或工单描述作为查询，检索知识库并生成建议答复；对外可用免登录分享或 Widget 自助问答 | RAG 问答、引用来源、分享链接、Widget 嵌入、开放 API、配额与限流 | 工单流转、客户画像、坐席工作台集成不在本系统范围 |
| **研发文档与规范检索助手** | 接口文档、设计文档、排障记录、编码规范入库，研发在聊天窗口或经开放 API 在内部工具中检索 | 混合检索（向量 + 关键词）、查询变换、Rerank、API Key 开放 API、单页 URL 连接器 | 不直接同步 Git / Jira 等数据源；文档需上传、经 API 写入或由连接器抓取单个页面 |
| **培训 / 考试题库知识问答** | 培训资料、FAQ、题库解析后入库，学员按自然语言提问 | FAQ / 章节 / 结构化 / 语义等多种切分，问答对与知识点检索，引用来源 | 不提供组卷、考试、判分等培训业务功能 |
| **第三方系统集成** | 既有站点嵌入问答窗口，或由外部系统通过 API 调用问答与知识库检索 | 免登录分享链接、iframe Widget、API Key（`chat:write` / `kb:read` / `usage:read`）、Redis 限流与调用日志 | Widget 为 iframe 注入，不含 `postMessage` 双向通信；跨域需宿主配置 CSP |
| **多用户按角色与配额分权使用** | 管理员维护用户/角色/权限，按用户配置日/月配额，审计关键操作 | OIDC 资源服务器 + 本地 admin/体验账号、RBAC、角色与权限管理页面、配额模块、审计日志 | **不做租户隔离**；知识库/文档级权限未全量覆盖 |

> 说明：以上场景均基于当前代码已实现的能力描述；未实现的能力不会作为卖点写入场景。

---

## 3. 能力矩阵

状态口径：**已完成** / **部分完成** / **未实现**。状态判断以代码与提交为准，详细限制见 §8。

### 3.1 知识库与检索

| 能力 | 状态 | 说明 |
|------|------|------|
| 文档解析（12 种格式） | 已完成 | PDF / DOC / DOCX / XLS / XLSX / CSV / TXT / MD / PPTX / HTML / JSON / EML；统一由 `DocumentParserDispatcher` 分发并抽取元数据 |
| PDF 版面分析与表格还原 | 已完成 | `PdfLayoutAnalyzer` 识别双栏阅读顺序，`TableExtractor` 将表格还原为 Markdown；栏检测优先于表格，纯两列表格可能按双栏处理 |
| 扫描版 PDF OCR | 已完成 | `TessOcrService` 按页兜底，单页文字少于阈值（默认 20 字）触发；依赖本机 `tesseract` 与 `chi_sim+eng` 语言包，缺失或超时降级为文本抽取（`WGH_OCR_*`） |
| 新增文档格式（PPTX / HTML / JSON / EML） | 已完成 | `PptxParser` / `HtmlParser` / `JsonParser` / `EmlParser` + 统一元数据与大小上限 |
| 文本切分（9 种策略） | 已完成 | AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM / STRUCTURED（标题感知）/ SEMANTIC（语义断句） |
| 查询变换 / 混合检索 / RRF / Rerank | 已完成 | REWRITE / MULTI_QUERY / HYDE；向量余弦 + pg_trgm 关键词召回；RRF(k=60) 融合；可选 Rerank；聊天窗口可单独配置 `hybridEnabled` / `queryTransformMode` / `rewriteModelId`，混合检索按“窗口开启或全局开启”生效，窗口关闭不能覆盖全局开启（2026-11-18，commit `8b52b5f`） |
| 检索指标与离线评测 | 已完成 | 覆盖率 / 命中率；ground truth 标注后计算 Recall@K / Precision@K / MRR / NDCG；生成侧多 judge 评估 Faithfulness / Answer Relevancy / Context Precision / Context Recall（缺参考答案时显式标记不可计算）；`eval/eval_recall.py` 复用同一标注 |
| 评测数据集版本化与 CI 检索门禁 | 已完成 | 数据集 / 用例 / 结果三表；用例每次变更按“写时复制”生成新版本，旧版本只读；按版本评测并归档指标与逐查询明细，支持跨版本指标差值与逐查询 Recall 差值；CI 用固定 fixture 在无 Key、无网络、无真实模型条件下校验五项阈值（commit `adbdafe`） |
| 检索来源结构化与 [Sn] 引用溯源 | 已完成 | `sources` 扩展 `kbId` / `kbName` / `fileId` / `fileName` / `chunkIndex` / `pageNo` / `charStart` / `charEnd` / `score` / `snippet`（压缩空白后前 120 字），按文件名去重保留最高分；上下文按文档分配 `[Sn]` 编号（同文档多段共用），system prompt 要求按编号引用；前端 `SourcePanel.vue` 展开原文并高亮对应卡片（commit `96dfc42`） |
| 知识库标签 | 已完成 | `TagService` / `TagController`；知识库与文件多标签绑定及管理界面 |
| 文档版本 | 已完成 | `FileVersionService` + 版本列表 / 回滚；回滚生成新版本，不覆盖旧版本 |
| URL 数据源连接器（单页抓取 + 增量同步） | 已完成 | `ConnectorController` / `ConnectorService` / `UrlConnector`；单个 http/https 页面抓取、Jsoup 正文抽取、SSRF 逐跳校验；按 `content_hash` 判重，内容未变返回 `UNCHANGED` 不重复入库，内容变化生成新文件并逻辑删除旧文件，404/410 清理旧文件；每次同步留运行记录；定时同步默认关闭，需显式 `WGH_CONNECTOR_SCHEDULER_ENABLED=true`（commit `855f61f`） |
| 可插拔文件存储（local / OSS） | 已完成 | 独立 `rag-storage` 模块，`FileStorageService` + 本地 / 阿里云 OSS 实现 |
| 大文件分片上传与断点续传 | 已完成 | 三段式接口：`POST /api/kb/{kbId}/files/chunk/init` → `POST /api/kb/files/chunk/{sessionId}`（按序号上传分片）→ `POST .../complete`；合并后按整文件 `content_hash` 走原有判重与入库链路；页面内断线续传（进度来自会话表）；会话按创建者归属校验，他人会话按不存在处理（`3001`）；24 小时未完成会话自动清理，阈值可配（`WGH_UPLOAD_CLEANUP_*`，commit `140375a`） |
| `local` ↔ `oss` 历史对象自动迁移 | **未实现** | 切换本身已支持，但旧对象仍留在原存储后端，需手工迁移或重新上传并重建向量 |
| OSS 删除失败后的孤儿对象清理 | **未实现** | 删除失败仅记录 WARN，数据库仍完成逻辑删除，对象可能残留 |
| 独立图片 OCR | **未实现** | 扫描版 PDF 已按页 OCR；独立图片文件（PNG / JPG）仍不解析入库 |

### 3.2 对话与开放接入

| 能力 | 状态 | 说明 |
|------|------|------|
| 多会话与流式问答 | 已完成 | 多会话管理、首条消息自动生成标题、历史滑动加载；SSE 输出 Markdown、引用来源与检索指标；回答中的 `[Sn]` 可点击定位来源卡片（2026-11-12） |
| 三层上下文压缩 | 已完成 | 超过 `max-history-messages`（默认 20）触发：近期原文 / 中期摘要 / 远期关键事实；模型缺失或超时降级为截断；压缩后再按 `max-history-tokens`（默认 4000）做 Token 级硬裁剪：System 全部保留、当前提问必留、其余从最近往前保留，放不下即丢弃（commit `0ed790c`） |
| 检索指标 / 来源 / 上下文同源 | 已完成 | `coverageRate` / `hitRate` 改为基于预算裁剪后的同一份列表；`retrieveInfo` 新增 `contextKeptCount` / `contextBudgetTokens` / `contextTrimmed`，`retrievedCount` 保留“检索返回条数”原义；Agent 分支来源改为步骤内实际喂给模型的 chunk，计划不检索时为空（commit `27fce99`、`7a6070f`） |
| 多模态图片问答 | 已完成 | 视觉模型（`supports_vision`）支持图片 + 文本提问，附件地址落 `wgh_chat_message.attachments` |
| 反馈闭环 | 已完成 | 消息点赞 / 点踩 + 备注，窗口满意度统计与差评运营列表 |
| 反馈 A/B 实验 | **未实现** | 评测数据集版本化已交付（写时复制、按版本归档与跨版本对比）；仍无实验分流、对照组与显著性分析 |
| 免登录分享 / Widget 嵌入 / 开放 API | 已完成 | 分享链接（有效期、访问密码、只读范围、吊销）；`widget.js` iframe 注入；API Key 开放 API 与调用日志 |
| OpenAI 兼容入口 + Python SDK | 已完成 | `/v1/models`、`/v1/chat/completions`（含 SSE 流式），复用 API Key + scope；附带 `sdk/python/wgh_rag_client.py`；仅支持基础对话参数，`n>1` / `logprobs` / tools 未支持 |
| Prompt 模板中心与输入框前缀联想 | 已完成 | 模板 CRUD、变量提取、渲染预览；输入框前缀触发候选、模糊过滤、变量逐个填写；`GET /api/prompt-templates/suggest` |
| 语义缓存 | 已完成 | 窗口级开关，相似度阈值命中后直接返回缓存答案，跳过检索与 LLM |

### 3.3 Agent 与工作流

| 能力 | 状态 | 说明 |
|------|------|------|
| 单 Agent | 已完成 | Planner 拆解 → StepExecutor 执行 → Reflection 评分重试 → 汇总答复；计划 / 步骤落库；支持 HITL |
| 多 Agent 协作 | 已完成 | 6 种模式：CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL / GRAPH_ROUTED / GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER |
| 工作流编排 | 已完成 | 12 类节点：START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE / WORKFLOW / AGENT / EXPRESSION；并行 fan-out/fan-in、版本、触发器、模板市场 |
| 工作流 AGENT 节点 | 已完成 | `AgentNodeExecutor` 内置轻量 Plan-and-Execute；编辑器支持 `goal` / `maxSteps` / `timeoutMs` / `kbIds` |
| 工作流表达式节点 | 已完成 | `EXPRESSION` 节点基于 `SimpleEvaluationContext` 的受限 SpEL 沙箱：仅允许字面量、`#input` / `#last` / `#loop` / `#vars` / `#nodes`、算术/比较/逻辑/三元与白名单 `#fn.*`；拒绝 `T()`、`new`、`@bean`、赋值与实例方法；表达式长度上限 2000 字符；保存时做必填与语法校验（commit `e27723f`） |
| 工作流单步调试 | 已完成 | `POST /api/workflows/{id}/debug/step` 按节点推进并返回输入快照（渲染后配置 + 直接上游输出）与输出；游标（frontier / pendingIn / 已执行数）持久化在运行记录，可带 `runId` 续跑；HITL 节点停在 `PAUSED`；开关 `wgh.workflow.debug.enabled` 默认关闭（commit `e27723f`） |
| 模型故障转移（Agent / 工作流） | 已完成 | Planner / StepExecutor / ReflectionEngine 与工作流 LLM 节点复用 `rag-llm` 的 `BlockingModelFailover`：首响应前失败切下一个模型，已产出内容后不切链；SSE 下发 `model_switch` |

### 3.4 平台治理

| 能力 | 状态 | 说明 |
|------|------|------|
| OIDC 资源服务器形态验签 | 已完成 | `OidcTokenVerifier` / `JwksProvider`；仅接受非对称签名，拒绝 HS256 与 `none`，校验 iss / aud / exp / nbf |
| 本地 admin / 体验账号登录 + `password_version` | 已完成 | `LocalLoginService` / `LocalTokenService`；仅 `source=local` 的 admin / 体验账号可登录；`password_version` 校验会话失效 |
| 多用户与权限体系 | 已完成 | OIDC + 本地账号双认证，RBAC（`@RequiresPermission` / `PermissionAspect`）；租户隔离与知识库/文档级全量权限不在范围内 |
| 资源归属与可见性 | 已完成 | 知识库 / 聊天窗口 / 工作流 / Prompt 模板 / 工具五类资源新增 `visibility`（`PRIVATE` 默认 / `TEAM` / `PUBLIC`），owner 复用 `create_by`；读他人 `PRIVATE` 按不存在处理（业务码 `3001`），写他人 `TEAM`/`PUBLIC` 返回权限不足（业务码 `1004`），均为 HTTP 200 + 业务码；列表/分页由 `DataScopeInterceptor` 显式过滤，内置 Prompt 模板固定 `PUBLIC`（commit `59c5eee`） |
| 角色与权限管理页面 | 已完成 | `AdminRoleController`（`/api/admin/roles`、`/api/admin/permissions`）+ `RoleManagement.vue`、`UserManagement.vue` |
| 配额模块与前后端页面 | 已完成 | 独立 `rag-quota` 模块；全局默认 + 按用户日/月配置，超限 429；`QuotaManagement.vue` / `MyQuota.vue` |
| Redis 8.10.1 接入 | 已完成 | 分布式限流、分享密码失败锁定（成功清零）、嵌入授权、API Key IP 白名单；不可用时降级为单机内存 |
| 审计与成本统计 | 已完成 | `@Audit` 异步记录写操作；角色 / Agent / 多 Agent / 配额等管理写操作已覆盖（资源标识落 `detail`）；模型价格表 + 消息成本 / 模型名回填；管理端聚合统计 |
| 安全整改 | 已完成 | 首登强制改密（`password_must_change`）、审计日志敏感信息脱敏、409 映射、`WGH_INIT_ADMIN_PASSWORD` 注入 |
| 可观测性（Prometheus + 独立 9090） | 已完成 | `RagMetrics` 业务指标 + `/actuator/prometheus`；管理端口默认 `9090`，业务端口不暴露 `/actuator` |
| Compose 内置 PostgreSQL(pgvector) | 已完成 | `docker-compose.yml` 内置 `pgvector/pgvector:pg15`，空卷首启执行 `schema/01-schema.sql` / `seed/02-data.sql` |

### 3.5 模型与工程

| 能力 | 状态 | 说明 |
|------|------|------|
| 模型管理 | 已完成 | DB 动态模型 + OpenAI / DASHSCOPE / DeepSeek / Azure OpenAI / Ollama / 智谱 GLM / 自建 OpenAI 兼容端点，能力由 `ProviderCatalog` 统一描述；缓存热更新 |
| 模型路由与故障转移 | 已完成 | `ModelRouter#resolveChain` + `rag-llm/failover` 通用组件：`StreamingModelFailover` 驱动普通 RAG 流式切链（首 token 前 `onError` 切下一候选，独立输出缓冲 + `active`/CAS 防重复切链），`BlockingModelFailover` 驱动 Agent 与工作流 LLM 节点（首响应前失败切链）；全部候选失败返回明确业务错误，已产出内容后失败不切链、不拼接两段输出；三类链路均下发 `model_switch` |
| 多实例 JWKS 缓存共享 | **未实现** | `JwksProvider` 为进程内缓存，密钥轮换后各实例刷新时间不一致 |
| `rag-public-access` 独立模块 + Enforcer 禁反向依赖 | 已完成 | 分享 / Widget / API Key 实现层独立；Controller 留在 `rag-controller`，依赖方向受 Enforcer 约束 |
| `rag-storage` 独立模块（local / OSS 可插拔） | 已完成 | `FileStorageService` + `LocalFileStorageService` / `AliyunOssFileStorageService` |
| `rag-agent-multi` Controller 迁入 `rag-controller` | 已完成 | `rag-agent-multi` 已无 Controller，`MultiAgentController` 位于 `rag-controller` |
| 常量统一抽取 | 已完成 | 各模块 `constant` 包；权限码、角色码、状态值、SSE 事件名、存储 key 前缀等改引用常量 |
| JDK 21 统一 | 已完成 | `maven.compiler.release=21` + Enforcer 强制 JDK 21，CI 与本地一致 |
| 测试用例与设计文档补全 | 已完成 | JUnit 5 + Mockito；`scripts/` 提供 e2e / 冒烟 / 压测；`docs/testing/test-cases.md`、`docs/design/**/*.md` 已补齐 |

### 3.6 上下文与工具治理（2026-11-18）

| 能力 | 状态 | 说明 |
|------|------|------|
| 历史消息 Token 级裁剪 | 已完成 | `HistoryContextBudgeter` 在分层压缩结果上再做一次硬裁剪：System 全保留、当前提问必留、其余从最近往前保留，放不下即停止；默认上限 4000 Tokens（commit `0ed790c`） |
| 检索指标 / 来源 / 上下文同源 | 已完成 | 指标、来源卡片、模型上下文使用同一份裁剪后列表；`retrieveInfo` 追加 `contextKeptCount` / `contextBudgetTokens` / `contextTrimmed`（commit `27fce99`、`7a6070f`） |
| 工具结果纳入上下文预算 | 已完成 | 单条工具结果上限 1200 Tokens、单任务累计 3000 Tokens，超出从最旧结果开始折叠；预算只作用于送入模型的副本，前端仍展示完整原文（commit `a458197`） |
| 工具调用兜底链 | 已完成 | `ToolInvoker` 统一包一层：失败分类（网络 / 限流 / 5xx 可重试，SSRF / 401/403 / 400/422 / 404 不重试）、仅幂等调用重试（最多尝试 2 次含首次，300ms 退避 ±30% 抖动）、按 toolId 熔断（连续 5 次失败开 30s，半开只放行 1 个探针）、任务级预算（50 次 / 120s）（commit `3a52ab6`） |
| 工作流 LLM 节点输入预算 | 已完成 | `WorkflowContextProperties` + `LlmNodeExecutor` 渲染后按模型窗口裁剪并告警（commit `f27704c`） |

---

## 4. 架构与技术栈

### 4.1 模块结构

```text
wgh-rag-system/
├── rag-backend/                  # Spring Boot 多模块（Java 21）
│   ├── rag-common/               # 纯契约层：Result / 异常 / 指标 / 安全契约
│   ├── rag-data/                 # BaseEntity / MyBatis-Plus 配置 / 类型处理器
│   ├── rag-log/                  # @Audit 切面与审计日志
│   ├── rag-auth/                 # OIDC 资源服务器 + 本地登录 + 用户/角色/权限
│   ├── rag-quota/                # 配额模块（全局默认 + 按用户日/月）
│   ├── rag-config/               # 配置属性映射
│   ├── rag-storage/              # 可插拔文件存储：local / 阿里云 OSS
│   ├── rag-llm/                  # 动态模型、厂商工厂、缓存热更新、模型路由
│   ├── rag-kb/                   # 上传 → 解析 → 切分 → 向量化 → 检索增强 → 评测
│   ├── rag-chat/                 # 窗口/会话、上下文压缩、流式 RAG、分享、反馈
│   ├── rag-tool/                 # HTTP / MCP / Skill / 函数调用工具
│   ├── rag-agent/                # 单 Agent：Planner / Executor / Reflection / HITL
│   ├── rag-agent-multi/          # 多 Agent：Crew / Graph / GroupChat / runtime
│   ├── rag-workflow/             # 工作流引擎：节点执行器、触发器、模板
│   ├── rag-public-access/        # 分享 / Widget / 开放 API 实现层（无 Controller）
│   ├── rag-controller/           # REST 层（含 /api/public、/api/open/v1）
│   ├── rag-app/                  # 启动模块 + application*.yml + 集成测试
│   ├── sql/                      # schema/01-schema.sql（37 张表）/ seed/02-data.sql
│   ├── eval/                     # eval_recall.py（Recall@K）、eval_rag.py（含 LLM Judge）
│   └── scripts/                  # e2e / 冒烟 / 压测脚本
├── rag-frontend/                 # Vue 3 前端（含 public/widget.js）
└── docs/                         # 设计与测试文档（索引见 docs/README.md）
```

关键依赖约束：

- `rag-common` 不依赖任何内部模块，也不含实体 / Mapper / MyBatis 配置；
- `rag-public-access` 依赖方向固定为 `rag-controller → rag-public-access`，并由 Enforcer 禁止反向依赖；
- `rag-storage` 仅依赖 `rag-common`，上层业务通过 `FileStorageService` 抽象使用；
- `rag-auth` 持有用户 / 角色 / 权限实体与 Mapper，供 Controller 的 RBAC 管理接口使用。

更完整的模块依赖与边界说明见 [docs/design/design-overview.md](docs/design/design-overview.md) 与 [docs/design/design-detailed.md](docs/design/design-detailed.md)。

### 4.2 关键设计

- **单库承载业务与向量**：PostgreSQL + pgvector 省去独立向量数据库；pgvector 不可用时不阻断应用启动，但向量检索相关能力不可用。
- **Redis 可选**：限流、分享密码锁定、嵌入授权可集中到 Redis；不可用时自动降级为单机内存，不阻断启动。
- **SSE 统一协议**：聊天、Agent、工作流、多 Agent 共用事件风格；`content` 事件使用 JSON 包装文本，避免换行丢失，前端状态复位以事件驱动。
- **先落库后推送**：Agent / 工作流执行结果先持久化再发送 SSE，刷新页面后仍可恢复。
- **多环境配置**：dev / test / prod 分离；生产敏感配置通过环境变量注入。
- **逻辑删除**：所有删除走 `deleted` 字段，便于审计与恢复。

### 4.3 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue 3 · Vite · Pinia · Vue Router · Element Plus · SCSS · marked |
| 后端 | Spring Boot 3.4 · Java 21 · Maven 多模块 · Spring Security |
| 数据 | PostgreSQL 15+ · pgvector · MyBatis-Plus（逻辑删除 + 分页） |
| LLM | LangChain4j · OpenAI / DASHSCOPE / DeepSeek / 自建 OpenAI 兼容端点 |
| 工具 | LangChain4j Tool Calling · MCP（stdio / Streamable HTTP）· HTTP · 本地反射 · Skill |
| Agent | Planner / StepExecutor / Reflection / HITL；多 Agent 含 Crew / Graph / GroupChat |
| 工作流 | 自研 DAG 引擎：11 类节点、并行 fan-out/fan-in、版本快照、CRON / Webhook |
| 鉴权 | 外部 OIDC token（JWKS 非对称公钥验签）+ 本地会话凭证 + RBAC + API Key（SHA-256） |
| 评估 | 检索侧 Recall@K / Precision@K / MRR / NDCG；生成侧 LLM-as-Judge |
| 测试 | JUnit 5 + Mockito；shell e2e / 冒烟 / 压测；离线召回率评测脚本 |

---

## 5. 快速开始

### 5.1 环境要求

- **JDK 21（必需）**：编译目标与测试均固定在 JDK 21；JDK 25 下会在 `validate` 阶段直接失败。
- Maven 3.9+、Node.js 18+（前端）、Docker 24+（可选，用于 PostgreSQL / Redis / Compose）。

```bash
java -version 2>&1 | head -1     # 期望 openjdk version "21.x"
mvn -version | grep "Java version"   # 期望 Java version: 21
# macOS 切换：export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# Linux 切换：export JAVA_HOME=/path/to/jdk-21
```

### 5.2 本地启动

1）启动 PostgreSQL + pgvector 并初始化：

```bash
docker run -d --name postgres-with-vector \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=wgh_rag \
  -p 5432:5432 \
  -v "$(pwd)/rag-backend/sql:/sql:ro" \
  pgvector/pgvector:pg15

# 37 张表 + 索引 + 种子数据：按顺序用 -f 执行（结构脚本幂等，种子含示例数据，不建议对存量库重跑）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag \
  -f /sql/schema/01-schema.sql -f /sql/seed/02-data.sql
```

> **已有库升级**：不要重跑 schema / seed。按版本顺序执行 `sql/migrations/` 下的增量脚本，例如资源可见性：
>
> ```bash
> docker exec -i postgres-with-vector psql -U postgres -d wgh_rag \
>   -f /sql/migrations/20261113/01_resource_visibility.sql
> ```
>
> 迁移脚本均为幂等（`IF NOT EXISTS`），只补列 / 索引与必要回填，不重建表。

2）启动后端：

```bash
cd rag-backend
export WGH_DB_PASSWORD=postgres          # 与上面容器口令一致
export WGH_INIT_ADMIN_PASSWORD='<至少 8 位且含字母与数字>'
mvn install -DskipTests
mvn -f rag-app/pom.xml spring-boot:run
# 业务端口 http://localhost:8080；管理端口 http://localhost:9090
```

> 使用 `-pl` 时必须加 `-am`，否则可能读到 `~/.m2` 中的旧 jar。

3）可选：启动 Redis（公开访问分布式限流 / 密码锁定 / 嵌入授权）：

```bash
docker run -d --name wgh-rag-redis -p 6379:6379 redis:8.10.1-alpine
docker exec wgh-rag-redis redis-cli ping   # PONG
```

4）启动前端：

```bash
cd rag-frontend
npm install
npm run dev          # http://localhost:5173
```

### 5.3 Docker Compose 一键启动

```bash
cp .env.example .env
# 至少填写：
#   POSTGRES_PASSWORD
#   WGH_AUTH_LOCAL_JWT_SECRET   （至少 32 字节）
#   WGH_INIT_ADMIN_PASSWORD
docker compose up -d --build
docker compose ps
```

| 服务 | 默认端口 | 说明 |
|------|----------|------|
| frontend | `8088` → 容器 80 | Nginx 托管 SPA，`/api` 反代 backend，SSE 关闭缓冲 |
| backend | `8080` → 容器 8080 | 业务 API / SSE；**不暴露 actuator** |
| backend 管理端口 | 容器内 `9090`，默认不映射宿主机 | Prometheus 抓取；生产禁止对外发布 |
| prometheus | `9091` → 容器 9090 | 抓取 `backend:9090/actuator/prometheus` |
| grafana | `3000` | 预置数据源与业务总览仪表盘 |
| redis | `6379` | 限流 / 密码锁定 / 嵌入授权 |
| postgres | **默认不映射宿主机** | `pgvector/pgvector:pg15`，仅容器网络内可达 |

> `docker compose down -v` 会删除 `pgdata` 与 Redis / Prometheus / Grafana 数据卷；只停服务请用 `docker compose down`。
> 本地开发若需宿主直连数据库，可启用 `docker-compose.override.yml` 的 `127.0.0.1:5432` 绑定；生产不要启用，更不要把 5432 发布到 `0.0.0.0`。

### 5.4 首次登录

- 初始管理员用户名为 `admin`，口令来自 `WGH_INIT_ADMIN_PASSWORD`；仅当库中不存在 admin 时创建，已存在则不会覆盖。
- 首次登录后必须先调用 `POST /api/auth/password` 改密；改密前写请求会被拒绝（业务码 1006）。
- 不使用本地账号时可只配置 OIDC；外部 IdP 用户由 Bearer token 验签进入。

---

## 6. 配置说明

### 6.1 认证与初始化管理员

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_INIT_ADMIN_PASSWORD` | 空 | 初始管理员口令；未注入且库中无 admin 时不创建管理员，启动日志输出 ERROR；**禁止使用默认口令** |
| `wgh.auth.local.jwt-secret` / `WGH_AUTH_LOCAL_JWT_SECRET` | 空 | 本地会话签名密钥；`prod` / `production` 下未配置会启动失败（fail-closed）；开发环境未配置会生成随机密钥，重启后旧会话失效 |
| `WGH_JWT_SECRET` | 无 | 兼容旧 prod 配置项，建议与 `WGH_AUTH_LOCAL_JWT_SECRET` 同值 |
| `wgh.auth.init-admin.password` | 空 | `WGH_INIT_ADMIN_PASSWORD` 的 YAML 等价配置项 |

本地账号仅允许 `wgh_user.source=local` 的 admin 或体验账号（`trial=1`）登录；外部 OIDC 用户即使 `password_hash` 为空也不能走本地密码登录。

### 6.2 外部 OIDC（资源服务器）

| 配置 | 说明 |
|------|------|
| `WGH_OIDC_ISSUER` / `wgh.auth.oidc.issuer` | IdP issuer，token `iss` 必须一致 |
| `WGH_OIDC_AUDIENCE` / `wgh.auth.oidc.audience` | 期望 audience，token `aud` 必须包含 |
| `WGH_OIDC_JWKS_URI` / `wgh.auth.oidc.jwks-uri` | JWKS 端点；留空时按 issuer 自动发现 |
| `WGH_OIDC_ROLES_CLAIM` / `wgh.auth.oidc.roles-claim` | 承载角色的 claim，默认 `roles`，常用 `groups` |
| `WGH_OIDC_USERNAME_CLAIM` / `wgh.auth.oidc.username-claim` | 承载用户名的 claim，默认 `sub` |
| `wgh.auth.oidc.role-mapping` | claim 值 → `wgh_role.role_code` 映射表 |
| `wgh.auth.oidc.default-role` | token 无角色或映射不到数据库角色时的默认角色，默认 `viewer` |

验签仅接受 RS / PS / ES 非对称算法，拒绝 HS256 与 `none`；JWKS 为进程内缓存，多实例不共享（见 §8）。

### 6.3 文件存储（local / OSS）

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_STORAGE_TYPE` / `wgh.rag.storage.type` | `local` | `local` 本地磁盘 / `oss` 阿里云 OSS |
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

OSS 配置不完整时不阻断启动，仅在真正调用存储操作时返回明确错误。存储切换、迁移与孤儿对象限制见 §8；完整设计见 [docs/design/features/storage/design-file-storage.md](docs/design/features/storage/design-file-storage.md)。

### 6.4 数据库、Redis 与其他关键配置

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_PROFILE` | `dev` | Spring Profile：`dev` / `test` / `prod` |
| `WGH_DB_HOST` / `PORT` / `NAME` / `USERNAME` / `PASSWORD` | localhost / 5432 / wgh_rag / postgres / 123456 | 本地 dev 数据源 |
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

> 限流覆盖、可信代理、配额、Agent 与上下文压缩等完整配置与设计说明见 `docs/design/features/**/*.md`；接口侧的限流与错误码行为见 [docs/api/api.md](docs/api/api.md)。
> `wgh.public-access.trusted-proxies` 默认空，只信任 TCP 对端；部署在 Nginx / 网关后需显式配置可信 CIDR，**不要配置为 `0.0.0.0/0`**。

### 6.5 检索与评测参数

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

### 6.6 URL 数据源连接器与工作流调试

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

### 6.7 上下文预算、工具兜底与上传清理

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

---

## 7. 接口概览

完整请求体与 SSE 事件协议见 [docs/api/api.md](docs/api/api.md)；这里只列分组。

| 模块 | 前缀 | 说明 |
|------|------|------|
| 鉴权 / OIDC | `/api/auth` | 本地登录、当前用户、改密、OIDC 状态 |
| 知识库 | `/api/kb` | 知识库 / 文件 CRUD、分片上传（`files/chunk/init` → `chunk/{sessionId}` → `complete`）、重切、检索测试、ground truth、召回评测 |
| 评测数据集 | `/api/kb/{kbId}/eval-datasets` | 数据集 / 用例 CRUD、版本列表、按版本评测归档、历史结果与跨版本对比 |
| 数据源连接器 | `/api/kb/{kbId}/connectors` | URL 连接器 CRUD、立即同步、运行记录 |
| 模型 | `/api/models` | Chat / Embedding / Rerank 模型 CRUD |
| 聊天 | `/api/chat/windows` | 窗口 CRUD、会话、消息、SSE 对话、反馈 |
| 分享（登录态旧入口） | `/api/chat/share` | 窗口分享开关与 token 管理 |
| 工具 | `/api/chat/tools` | 工具 CRUD、在线调试、Skill 目录管理 |
| Agent | `/api/agent` | 计划状态、HITL 确认、取消 / 重试 / 跳过 |
| 工作流 | `/api/workflows` | CRUD、校验、运行、HITL、版本、触发器、模板；`POST /{id}/debug/step` 单步调试（需开启调试开关） |
| 多 Agent | `/api/agent-multi` | Crew CRUD / 校验、运行、SSE、state、HITL |
| Prompt 模板 | `/api/prompt-templates` | CRUD、渲染预览、前缀联想候选（`/suggest`） |
| 标签 / 版本 | `/api/tags`、`/api/kb/files/{id}/versions` | 标签 CRUD 与绑定、版本列表与回滚 |
| 管理端 | `/api/admin` | 审计日志、成本 / 配额统计、差评、用户配额、角色权限、分享链接、API Key |
| 免登录分享 | `/api/public/share/{token}` | 信息 / 解锁 / 会话 / 消息 / SSE 问答 / 知识库检索 |
| 开放 API | `/api/open/v1` | `/me`、`/chat/stream`、`/kb/search`、`/usage`（`X-API-Key`） |
| OpenAI 兼容 | `/v1` | `/models`、`/chat/completions`（Bearer API Key，支持 SSE） |

---

## 8. 已知限制

| 类别 | 限制 | 影响 / 建议 |
|------|------|-------------|
| 权限体系 | 已提供角色 / 权限管理页面与接口；五类核心资源（知识库 / 聊天窗口 / 工作流 / Prompt 模板 / 工具）已支持 owner + 可见性；无租户隔离，文档级权限仍不在范围内 | 多团队共用与真正的团队边界需后续引入租户 / 成员模型 |
| 可观测性 | 已提供 Prometheus 指标与管理端口（9090）、`X-Request-Id` 日志关联；未接入分布式链路追踪 | 跨服务调用链仍需日志 / 审计辅助 |
| 部署编排 | 已提供前后端 Dockerfile 与全栈 docker-compose；无 Kubernetes 级编排 | 大规模水平扩展需自行补编排 |
| 限流 | Redis Lua 分布式滑动窗口，故障时降级单机内存 | Redis 故障期间多实例计数不共享、重启清零；建议 Redis 高可用 |
| Widget | 仅 iframe 注入，无 `postMessage` 双向通信；`frame-ancestors` 需网关 / 静态服务配置 | 宿主页无法读取对话事件；手工 iframe 仍可能绕过 `widget.js`，需 CSP 兜底 |
| 分享密码 | 失败 5 次锁定 15 分钟（可配置），无验证码 | 换 IP 可继续尝试；强防护可叠加网关限流 / 验证码 |
| API Key | 无自动轮换；已有可选 IP 白名单 | 泄露后需人工吊销；建议始终配置 IP 白名单 |
| 真实 IP | 默认只信任 TCP 对端，XFF 仅在可信代理下生效 | 代理拓扑变化时需同步 `trusted-proxies`，配置过宽会引入伪造风险 |
| 反馈与评测 | 评测数据集已支持版本化（写时复制、按版本归档与跨版本对比，2026-11-18），但仍无 A/B 实验对比；生成侧 Context Precision / Recall 依赖参考答案字段，当前标注表只有相关 chunk，缺参考答案时返回不可计算 | A/B 对比需后续补实验分流与显著性分析；需先补参考答案字段才能端到端产出 CP / CR |
| 多模态 | 视觉模型支持图片输入；扫描版 PDF 按页 OCR；独立图片文件不入库 | OCR 依赖本机 Tesseract 与 `chi_sim+eng` 语言包，纯图片知识库仍需先转 PDF |
| OpenAI 兼容 | 仅 `/v1/models` 与 `/v1/chat/completions`，`n>1`、`logprobs`、function calling / tools 未支持；`model` 只能映射已启用聊天窗口 | 复杂参数与底层模型直连需走原生 `/api/chat` 接口 |
| OCR | 依赖本机 `tesseract` 可执行文件与语言包；单页 20 字触发、文档最多 50 页、单页 60s 超时；栏检测优先于表格 | 引擎缺失时静默降级为文本抽取；纯两列表格可能按双栏处理 |
| 检索 | 单库 PostgreSQL + pgvector，无独立向量库 / 搜索引擎 | 超大规模语料需评估性能上限 |
| 模型故障转移 | 普通 RAG、Agent（Planner / Step / Reflection）与工作流 LLM 节点统一复用切链组件，覆盖首个 token / 首个响应前的失败切链；**已产生内容后失败不切链、不回滚、不重发、不拼接第二个模型输出**，直接 `error` 结束 | 已产出内容后的续接策略暂不提供；Agent 恢复 / 重试入口按计划绑定的窗口路由组解析链路 |
| 存储切换 | `local` ↔ `oss` 切换不自动迁移历史对象 | 需手工迁移或重新上传并重建向量 |
| OSS 删除 | 删除失败仅 WARN，数据库仍逻辑删除，可能残留孤儿对象 | 需离线清理脚本 |
| 大文件上传（2026-11-18） | 已支持分片上传与页面内断线续传；不做跨页面刷新恢复（刷新后需重新选择文件，会话本身仍可复用）；OSS 未使用原生 multipart，分片先落对象存储、合并后仍走单次 `putObject`；单文件存储上限代码默认 200MB，但 `application.yml` 的 `wgh.storage.*.max-file-size-mb` 显式配 50（可用 `WGH_STORAGE_MAX_FILE_MB` 覆盖），单次直传仍受 servlet multipart 50MB 限制，解析侧 `wgh.file.max-single-file-mb` 仍默认 50MB | 传 200MB 文件需走分片并同时调大存储与解析两处上限 |
| 多实例 JWKS | `JwksProvider` 缓存为进程内，多实例不共享 | 单实例部署假设；多实例需接受轮换后的刷新时间差 |
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
| 检索式记忆（2026-11-18） | 仅接入 `SESSION` 作用域，任务级 / 跨会话记忆未接；无 TTL 与清理策略，记忆会持续累积；记忆向量表按首次写入的 embedding 维度建立，更换维度需重建 | 长期使用需补 TTL 清理与维度变更重建流程 |
| map-reduce 分段摘要（2026-11-18） | 仅在普通聊天链路生效，Agent / 多 Agent / 工作流链路未接；暂无集成测试覆盖 | 长任务链路仍需单独接入与回归 |
| 多 Agent 黑板（2026-11-18） | 已提供共享黑板读写接口，但 GroupChat 汇总未使用黑板，仍按对话消息汇总 | 需要跨 Agent 共享中间结论时需显式写入黑板 |
| 模型能力画像（2026-11-18） | 后端有 `LlmModel` 能力画像与切链降级策略；管理端模型表单未暴露能力档位与 function calling 字段 | 需通过接口或数据库配置，页面暂不支持 |
| 资源可见性（2026-11-12） | 无团队 / 租户模型，`TEAM` 与 `PUBLIC` 在判定上等价（均为"非 owner 可读"）；存量库执行 `sql/migrations/20261113/01_resource_visibility.sql` 补列与索引（幂等）；历史 `create_by='system'` 的资源升级后默认仅管理员可见 | 升级前需备份并执行该迁移脚本；如需真正的团队边界，需后续引入 `tenant_id` / 团队成员模型 |

---

## 9. 路线图 / 后续计划

状态口径：**已完成** / **部分完成** / **在途**（进行中、未交付）/ **未实现**（未开始）。§9.1、§9.2 分别对齐两份来源文档的实际进度，不自行推测；§9.3 收录不属于这两份清单的其他方向。

- 来源一：[长任务上下文管理与工具调用兜底 §5.1 优先级清单](docs/design/features/context-management/design-long-task-context-and-tool-fallback.md)
- 来源二：[缺口开发计划 2026-11-09 任务总表](docs/review/gap-development-plan-2026-11-09.md)

### 9.1 上下文与工具治理落地清单（P0 / P1 / P2）

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
| 检索式记忆 | 已完成 | 会话级检索式记忆（commit `239c490`）；当前仅接 `SESSION` 作用域，无 TTL 清理，向量表按首次写入的 embedding 维度建立、换维度需重建 |
| map-reduce 分段摘要 | 已完成 | 单条 chunk 饱和时触发分段摘要（commit `5bf6ce2`）；只在普通聊天链路生效，暂无集成测试 |
| 多 Agent 共享黑板 | 已完成 | 共享黑板读写接口（commit `a70753d`）；GroupChat 汇总尚未使用黑板 |
| 模型能力画像 | 已完成 | `LlmModel` 能力画像 + 切链降级策略（commit `d6cdee3`）；管理端模型表单未暴露能力档位与 function calling 字段 |

### 9.2 缺口开发计划任务状态（T01–T13）

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
| T12 | 多实例与可观测 | 未实现（未开始） | Redis 共享、JWKS 缓存共享、分布式链路追踪、k8s / 备份脚本 |
| T13 | Agent 长期记忆 / 代码沙箱 | 未实现（未开始） | 依赖 T09 执行节点，建议延后 |

### 9.3 其他后续方向

以下方向不属于上述两份清单，**未排期、不代表交付承诺**。

| 优先级 | 方向 | 当前状态 | 说明 |
|--------|------|----------|------|
| P0 | 模型故障转移补全 | 已完成（首 token 前切链） | 普通 RAG 首个 token 前的连接失败 / 超时 / 4xx / 5xx 已进入切链；部分输出后失败与全部候选失败均有明确错误收口（commit `e496f71`）；切链逻辑已抽成 `rag-llm` 的 `FailoverAttempt` / `StreamingModelFailover` / `BlockingModelFailover` / `ModelFailoverService`（commit `8e357bb`） |
| P1 | Agent / 工作流接入故障转移 | 已完成 | Planner / StepExecutor / ReflectionEngine 与工作流 LLM 节点复用 `BlockingModelFailover`；Agent 恢复 / 重试入口回查窗口路由组（commit `8e357bb`） |
| P1 | 存储迁移与孤儿对象清理 | 未实现 | `local` ↔ `oss` 历史对象迁移工具；按 `file_path` 比对清理 OSS 残留对象 |
| P2 | 多实例 JWKS 缓存共享 | 未实现 | 将 JWKS 缓存放至 Redis 或共享缓存，统一密钥轮换后的刷新时机（T12 范围） |
| P2 | 分布式链路追踪 | 未实现 | 在现有 Prometheus + `X-Request-Id` 基础上接入 Tracing（T12 范围） |
| P2 | 整站爬取与多源接入 | 未实现 | robots.txt 校验、站点爬取 / Sitemap、PDF / RSS / 第三方文档源同步；单页 URL 连接器已交付（T08 未做部分） |
| P3 | 反馈 A/B 对比 | 未实现 | 版本化评测集已交付；仍缺实验分流、对照组与显著性分析 |
| P3 | 图片 OCR | 部分完成 | 扫描版 PDF 已按页 OCR（`TessOcrService`，commit `6ed9d10`）；独立图片文件（PNG / JPG）未接入 |

暂不规划：多租户隔离、独立向量数据库 / 搜索引擎、Kubernetes 级编排。当前设计以单库 PostgreSQL + pgvector 与单实例部署为优先，降低私有化部署与运维成本。
