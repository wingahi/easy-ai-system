# 功能对照分析：本系统 vs 主流 RAG / Agent 平台

> 分析日期：2026-11-09  
> 分析范围：`/Users/joe.wen/Desktop/ai-code/wgh-rag-system` 全仓（后端、前端、SQL、文档）  
> 分析口径：**以代码与数据结构为准**，不采信 README 的单方面表述；对 README 声称与代码实际不一致处单独列出。  
> 操作约束：全程只读，未修改业务代码、未重启 8080/9090 服务、未执行 Maven 测试、未连接 `wgh_rag` / `wgh_rag_fresh` 数据库、未 commit / push。

---

## 0. 结论摘要

**一句话定位**：本系统是一个“工程化 RAG 中台 + Agent/工作流运行时”，不是 Dify 那类低代码应用搭建平台。它在检索链路调优、会话级评估、HITL 恢复、配额与成本治理上做得比多数开源项目更细；但在文档解析质量、多租户隔离、开放生态、规模化运维上存在产品级缺口。

五条最重要的判断：

1. **检索与生成链路是真正的差异化能力，不是“有但很弱”。** 混合召回（pgvector + `word_similarity`）、RRF 融合、相邻 chunk 扩窗、阈值过滤、可选重排、查询改写、语义缓存、上下文预算、窗口级参数覆盖，全部可在代码中逐项定位。
2. **Agent 与工作流的完成度高于同类开源项目的“演示级”实现。** 单 Agent 具备 Planner/Executor/Reflection/HITL/重规划/重试/跳过；工作流有 11 类节点、版本回滚、Cron/Webhook 触发器、模板市场；多 Agent 有 6 种执行模式，且 HITL 可断点续跑。
3. **最大的产品级缺口是“多租户与资源归属”，不是模型或检索。** `wgh_knowledge_base`、`wgh_chat_window`、`wgh_workflow`、`wgh_tool` 均无 owner/tenant 字段；只有 `wgh_chat_session.user_identifier` 做了会话级隔离。多团队共用一套系统时，资源是全局可见的。
4. **第二大的缺口是“文档解析深度”。** 12 类格式依赖 Apache Tika / PDFBox 做文本抽取，没有 OCR、版面分析、表格结构还原、图片索引；企业常见的扫描件、双栏 PDF、复杂表格会直接掉效果。
5. **第三大缺口是“信任闭环”。** 引用只到“文档名 + 分数”，没有 chunk 级引用编号、页码/坐标、原文高亮；评测有 Recall@K / Precision@K，但生成侧只有 Faithfulness / Answer Relevancy，且没有基准集版本化与 CI 回归。

---

## 1. 分析口径与系统体量

| 口径项 | 实测值 | 依据 |
|---|---|---|
| Maven 模块 | **17 个**（README 称 18 个模块，差异见第 4 节） | `rag-backend/pom.xml` 的 `<modules>` |
| Controller | 21 个 | `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/*.java` |
| HTTP 路由 | 203 个 `@Get/Post/Put/DeleteMapping` | 同上 |
| 数据表 | 37 张（`wgh_*`） | `rag-backend/sql/schema/*.sql` |
| 权限码 | 18 个 `PERM_*` | `rag-auth/.../constant/AuthConstants.java` |
| 后端测试类 | 118 个 | `rag-backend/**/src/test/**/*.java` |
| 前端页面 | 22 个 `.vue` 页面 | `rag-frontend/src/views/**` |
| E2E 用例 | 93 条，修复后全部 PASS | `docs/testing/reports/browser-e2e-report-2026-11-06.md` |

**外部信息声明**：第 5 节对 Dify、RAGFlow、FastGPT、MaxKB、Coze Studio、Open WebUI 的描述，来自各产品官网/官方文档/公开对比资料（截至 2026-11），文中以 `†` 标注。这些产品迭代较快，具体功能以对应版本文档为准；本系统的描述则全部来自本仓库代码。

---

## 2. 本系统“究竟有什么”（含证据路径）

### 2.1 模块划分与职责

| 模块 | 职责 | 关键证据 |
|---|---|---|
| `rag-common` | 统一响应体、业务码、异常、JSONB/布尔类型处理器、通用工具 | `rag-common/src/main/java/com/wgh/rag/common/**` |
| `rag-data` | MyBatis-Plus 配置、分页方言（可配置 `wgh.db.dialect`）、Repository 基类 | `rag-data/.../config/**`、`application.yml` 中 `wgh.db.dialect` |
| `rag-log` | 审计注解、账号审计、请求日志、敏感信息脱敏 | `rag-log/.../aspect/AuditAspect.java`、`AccountAuditService.java` |
| `rag-auth` | 本地账号 + OIDC 双模式、JWT、RBAC、Spring Security | `rag-auth/.../config/SecurityConfig.java`、`JwtService.java`、`OidcTokenVerifier.java` |
| `rag-quota` | 用户配额、用量累计、模型价格 | `rag-quota/.../service/QuotaService.java`、`entity/ModelPrice.java` |
| `rag-config` | Redis 命令端口、降级与健康检查 | `rag-config/.../redis/RedisCommandPort.java`、`SpringRedisCommandPort.java` |
| `rag-storage` | 本地文件存储、下载签名、路径安全 | `rag-storage/.../LocalFileStorageService.java`、`DownloadSigner.java` |
| `rag-llm` | 模型缓存、模型路由/故障转移、密钥加密、上下文预算 | `rag-llm/.../service/ModelRouter.java`、`ModelCacheManager.java`、`util/RagContextBudgeter.java` |
| `rag-kb` | 知识库/文件/版本、解析、9 种切分、pgvector、关键词召回、RRF、重排、查询改写、语义缓存、检索评测 | `rag-kb/.../service/**`、`rag-kb/.../splitter/**` |
| `rag-chat` | 窗口/会话/消息、SSE 流、停止生成、上下文压缩、Prompt 模板、反馈、图片问答、LLM-as-judge、检索指标 | `rag-chat/.../service/RagChatService.java`、`ChatStreamCancellation.java`、`evaluation/**` |
| `rag-tool` | 工具 CRUD、HTTP/MCP/Function Calling/Skill 执行、SSRF 防护 | `rag-tool/.../executor/**`、`service/SsrfGuard.java` |
| `rag-agent` | 单 Agent 计划、执行、反思、HITL、重试/跳过/取消 | `rag-agent/.../service/AgentOrchestrator.java` |
| `rag-workflow` | 图执行、11 类节点、版本、触发器、模板、分享态安全运行 | `rag-workflow/.../engine/**`、`constant/WorkflowConstants.java` |
| `rag-agent-multi` | 6 种多 Agent 执行模式与断点续跑 | `rag-agent-multi/.../runtime/MultiAgentRuntime.java`、`crew/CrewCoordinator.java`、`groupchat/GroupChatManager.java` |
| `rag-public-access` | 分享链接、公开 API Key、Widget、限流、域名/IP 白名单、匿名会话 | `rag-public-access/.../service/**`、`entity/ShareLink.java`、`entity/ApiKey.java` |
| `rag-controller` | 统一 HTTP/SSE/WebSocket 入口 | `rag-controller/.../controller/*.java` |
| `rag-app` | 启动类、环境配置、系统级测试 | `rag-app/src/main/java/com/wgh/rag/RagApplication.java` |

> 说明：`rag-backend/eval/`、`rag-backend/scripts/` 是脚本目录，不是 Maven 模块；README 的“18 个模块”应是把脚本目录或根目录也计入了。

### 2.2 对外能力面

**接口分组（21 个 Controller，203 个路由）**

| 分组 | Controller | 能力 |
|---|---|---|
| 账号与权限 | `AccountController`、`AdminUserController`、`AdminRoleController` | 登录/刷新/改密、用户 CRUD、角色与权限分配 |
| 配额 | `QuotaAdminController` | 用户配额配置、周期用量查询 |
| 知识库 | `KnowledgeBaseController`、`KnowledgeFileController`、`RagSearchController` | 库 CRUD、文件上传/解析/重切/重建/版本/标签、检索与搜索 |
| 对话 | `RagChatController`、`ChatController`、`ChatWebSocketController`、`FeedbackController` | SSE 流、停止生成、窗口/会话/消息、反馈 |
| 分享 | `ShareChatController`、`PublicShareChatController`、`PublicChatWindowController`、`ShareLinkAdminController` | 登录态分享、公开分享、匿名窗口、分享链接管理 |
| 开放 API | `OpenApiController`、`ApiKeyAdminController` | 4 个开放接口、API Key 管理 |
| Agent | `AgentController`、`MultiAgentController` | 计划查询/确认/取消/重试/跳过、多 Agent 运行 |
| 工作流 | `WorkflowController`、`WorkflowTriggerController`、`WorkflowTemplateController` | 图 CRUD/运行/调试、触发器、模板市场 |
| 工具 | `ToolController` | 工具 CRUD、四类工具调试 |
| 模型 | `LlmModelController`、`ModelProviderController`、`ModelPriceController` | 模型管理、提供商、价格 |
| 其他 | `TagController`、`PromptTemplateController` | 标签、Prompt 模板 |

**鉴权与权限模型**

- 双模式：本地账号（`source=local`，BCrypt）与 OIDC 资源服务器（JWKS 验签、issuer/audience 校验），见 `rag-auth/.../service/OidcTokenVerifier.java`。
- JWT + RBAC：18 个权限码，管理端接口通过 `@PreAuthorize` 或手工权限判断；区分 ADMIN / USER。
- **资源级归属缺失**：知识库、聊天窗口、工作流、工具均无 owner 字段，只有聊天会话有 `user_identifier`。这意味着“权限”是功能权限，不是数据权限。
- 本地登录显式限制 `source=local`，外部 OIDC 用户拒绝走本地口令登录（`AccountService`）。

**配额**

- `QuotaService` 按 `username + period(DAY/MONTH) + resourceType(CHAT/KB/FILE/TOKEN/COST)` 累计；`wgh_user_quota_config` 配置上限，`wgh_quota_usage` 记录用量；耗尽后抛业务异常并拒绝请求。
- 成本按 `wgh_model_price` 的输入/输出单价估算，写入 `wgh_chat_message`。

**分享与公开访问**

- `ShareLink` 字段：token、scope、targetId、过期时间、密码哈希、最大使用次数/已用次数、撤销、`embedEnabled`、来源域名白名单、IP 白名单、创建人。
- 匿名访问：`PublicChatWindowController` 提供公开窗口信息、创建匿名会话、消息历史、SSE 流；`PublicAccessRateLimiter` 做 IP/维度限流；`SharePasswordGuard` 做口令解锁；`DefaultPublicAccessStore` 有 Redis 实现与内存降级。
- Widget：`rag-frontend/public/widget.js` + iframe 嵌入，`embedOriginWhitelist` / `embedIpWhitelist` 控制来源。

**开放 API**

- `OpenApiController` 4 个端点：知识库列表、文档列表、文本切分、检索。
- `ApiKey` 字段：前缀、哈希、名称、scopes、过期、撤销、最后使用时间、IP 白名单；每次调用写 `wgh_api_call_log`。

**审计日志**

- `@Audit` + `AuditAspect` 异步落 `wgh_audit_log`，记录操作人、方法、参数摘要、IP、结果、耗时。
- **覆盖不完整**：仅 8 个 Controller 标注了 `@Audit`（模型、价格、标签、知识库、分享管理、API Key 管理、Prompt 模板、聊天）；用户/角色/配额/工作流/Agent/多 Agent/开放 API 未见注解式审计。

### 2.3 知识库链路

| 环节 | 实现 | 证据 |
|---|---|---|
| 接入格式 | PDF/DOC/DOCX/XLS/XLSX/CSV/TXT/MD/PPTX/HTML/JSON/EML，统一 `DocumentParserDispatcher` 分发 | `rag-kb/.../service/DocumentParserDispatcher.java`、`parser/*Parser.java` |
| 解析 | Apache Tika / PDFBox 文本抽取 + 元数据；**无 OCR、无版面分析、无表格结构还原** | `parser/PdfParser.java`、`parser/DocxParser.java` |
| 清洗 | 基础空白规整、页眉页脚不处理、无独立清洗管线 | `KnowledgeFileService` 解析后处理 |
| 切分 | 9 种：AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM / STRUCTURED / SEMANTIC；段落滑窗带 overlap；结构化按 Markdown 标题聚合、表格整块保留；语义按相邻句 embedding 余弦断块 | `rag-kb/.../splitter/**`、`TextSplitter.java` |
| 去重 | 文件级 `content_hash` 去重；chunk 级内容哈希去重；重切/重建前清理旧向量 | `KnowledgeFileService`、`wgh_knowledge_file.content_hash` |
| 嵌入 | 模型管理中的 embedding 模型，按模型 ID 分表/分组，避免维度混用 | `LlmModelService`、`RagRetrieveService` 的模型分组 |
| 向量存储 | PostgreSQL pgvector，每库一张表，ANN 索引（HNSW）；复用 HikariCP；pgvector 不可用不阻断启动 | `rag-kb/.../vector/**`、`rag-config` 健康检查 |
| 关键词召回 | `word_similarity(query, text)` + `WHERE query <% text`，阈值 0.5 | `rag-kb/.../service/KeywordSearchService.java` |
| 混合召回 | 向量 + 关键词并行，RRF（k=60）融合 | `rag-kb/.../service/RrfFusion.java`、`RagRetrieveService` |
| 重排 | 可选 rerank 模型，候选数 = topK×3，上限 30，失败跳过 | `rag-kb/.../service/RerankService.java` |
| 查询改写 | REWRITE / MULTI_QUERY / HYDE，5 秒超时，失败用原查询；HYDE 的伪文档会替换原查询 | `rag-kb/.../service/QueryTransformService.java` |
| 过滤 | 最小相似度 0.55；按知识库过滤；相邻 chunk 扩窗；单 chunk 上限 | `RagRetrieveService`、`RetrievalOptions.java` |
| 语义缓存 | 按 `chatWindowId + 知识库版本签名 + 查询哈希` 命中，阈值 0.95；知识库变更时失效 | `rag-kb/.../service/SemanticCacheService.java`、`wgh_semantic_cache` |
| 重建与版本 | 重建到 staging 表后切换、失败回滚；`wgh_knowledge_file_version` 记录每次快照的真实 chunk 数与状态 | `KnowledgeFileService.rebuild`、`FileVersionService` |
| 标签 | 知识库标签、文件标签多对多 | `wgh_tag`、`wgh_kb_tag_rel`、`wgh_file_tag_rel` |

### 2.4 对话与生成

| 能力 | 实现 | 证据 |
|---|---|---|
| 流式 | SSE，事件含 `meta/sources/content/reasoning/done/error/agent_*/tool_*/hitl/accuracy/plan_*`；content 用 JSON 包装避免换行丢失 | `RagChatService`、`ChatEvents.java` |
| 停止生成 | `ChatStreamCancellation` 协作式取消 + `AbortController`；已生成内容落库 | `rag-chat/.../service/ChatStreamCancellation.java`、前端 `ChatPlayground.vue` |
| 多模型 | OpenAI 兼容 + DashScope + DeepSeek + 自定义；`ModelResolver` 选择窗口/系统/默认模型 | `rag-llm/.../service/ModelCacheManager.java` |
| 故障转移 | 模型路由组按 priority 排序，**首 token 前**失败切换下一个；首 token 后不切换 | `rag-llm/.../service/ModelRouter.java` |
| 上下文预算 | 按模型上下文窗口分配 system/history/rag 预算，超出截断 | `rag-llm/.../util/RagContextBudgeter.java` |
| 历史压缩 | 近期保留原文 / 中期 3–5 句摘要 / 远期结构化事实；超过 20 条触发；结果持久化可复用；失败降级截断 | `rag-chat/.../service/ContextCompressionService.java`、`wgh_chat_session` 的 `mid_summary*` / `long_term_facts*` |
| Prompt 模板 | 分类、变量 `{{var}}`、版本、内置标记；输入框前缀联想建议接口 | `PromptTemplateController`、`GET /api/prompt-templates/suggest` |
| 多方案对比 | 检索指标方案（MRR+NDCG）、LLM-as-judge、组合，创建窗口时选择 | `wgh_chat_window.evaluation_scheme`、`evaluation/**` |
| 成本/用量 | 模型价格表 + 消息 token 统计 + 配额累计 | `ModelPrice`、`QuotaService`、`wgh_chat_message` |
| 图片问答 | 支持 vision 的模型可传图 | `ChatImageService`、模型 `supports_vision` |
| 反馈 | 点赞/点踩 + 原因 | `FeedbackController`、`wgh_chat_message.feedback*` |
| 防注入 | RAG 上下文以分隔符包裹并在 system prompt 中声明“仅作资料” | `RagChatService.buildSystemPrompt` |

### 2.5 Agent、工作流与工具

**单 Agent**（`rag-agent`）

- Planner 生成带依赖关系的步骤；Executor 逐步执行；Reflection 对步骤打分并按阈值触发重规划；工具轮次最多 5 轮；单步失败可重试。
- HITL：Planner 标记 `hitl=true` 的步骤暂停，等待人工确认；确认后从断点续跑；支持取消、重试、跳过。
- 6 个接口：计划状态、确认、取消、重试、跳过、按会话查询。
- 计划与步骤持久化到 `wgh_agent_plan` / `wgh_agent_step`；SSE 事件 10 类；前端刷新后可按会话恢复渲染。

**工作流**（`rag-workflow`）

- 11 类节点：START / END / LLM / RETRIEVAL / CONDITION / LOOP / VARIABLE / TOOL / AGENT / SUB_WORKFLOW / PARALLEL。
- 图校验：环检测、孤立节点、入口/出口、SubWorkflow 递归深度（运行时最多 3 层）。
- 版本与回滚、Cron / Webhook 触发器、模板市场与实例化（自动填充占位 modelId）。
- 分享态安全运行：`safeMode=true`，HTTP 工具仅 GET/HEAD，禁用 MCP 写操作；HITL 通过 shareToken 鉴权。
- 与聊天窗口绑定：每条用户消息触发一次完整运行，事件复用聊天 SSE 协议。

**多 Agent**（`rag-agent-multi`）

- 6 种模式：`CREW_SEQUENTIAL`、`CREW_HIERARCHICAL`、`CREW_PARALLEL`、`GRAPH_ROUTED`、`GROUP_CHAT_ROUND_ROBIN`、`GROUP_CHAT_DYNAMIC_ROUTER`。
- 运行/步骤持久化（`wgh_m_agent_run` / `wgh_m_agent_step`），HITL 断点续跑按原 mode 回到对应 Coordinator。

**工具**（`rag-tool`）

- 4 类：HTTP、MCP（HTTP + stdio）、Function Calling、Skill（inline / 目录）。
- 窗口级工具注册用 ThreadLocal 隔离；工具定义 OpenAI function JSON Schema；结果超 4000 字符截断；最多 5 轮。
- SSRF 防护：`SsrfGuard` 解析 DNS 后拦截私网/回环/链路本地地址，每次重定向重新校验；支持可信主机白名单。

### 2.6 评测与测试

| 类型 | 内容 | 证据 |
|---|---|---|
| 在线检索指标 | 覆盖率、命中率、MRR、NDCG（依据消息中命中标记计算） | `rag-chat/.../evaluation/RetrievalMetricsCalculator.java` |
| 离线检索指标 | 基于标注 ground truth 的 Recall@K、Precision@K、查询命中率、覆盖率、延迟 | `rag-kb/.../service/RetrievalEvaluationService.java`、`wgh_kb_ground_truth` |
| 离线脚本 | 指定知识库、K、混合模式、自定义查询集；与在线口径一致 | `rag-backend/eval/eval_recall.py` |
| 生成侧评估 | LLM-as-judge：Faithfulness、Answer Relevancy；多 judge 重复评估并报告一致性；异步不阻塞 SSE | `rag-chat/.../evaluation/LlmJudgeService.java` |
| 单测 | 118 个测试类，覆盖各模块 | `rag-backend/**/src/test/**` |
| 页面 E2E | 93 条用例，含功能、边界、安全；报告与 105 张截图 | `docs/testing/reports/browser-e2e-report-2026-11-06.md` |

### 2.7 前端页面与用户任务

| 页面 | 用户任务 |
|---|---|
| `Login.vue` / `SsoCallbackView.vue` | 本地登录、OIDC 回调 |
| `ChatPlayground.vue` | 调试聊天、工具/Agent/工作流选择、停止生成、反馈、指标 |
| `ShareChat.vue` / `PublicShareChat.vue` | 登录态分享、匿名分享、HITL 确认 |
| `ChatWindowManage.vue` / `ChatWindowSessions.vue` | 窗口管理（模型、检索、Agent、工作流、工具、评估方案） |
| `KnowledgeBase.vue` / `KnowledgeBaseDetail.vue` / `KnowledgeFileDetail.vue` | 库/文件/切分/重建/版本/标签/检索 |
| `RagSearch.vue` | 独立检索测试 |
| `WorkflowList.vue` / `WorkflowEditor.vue` / `WorkflowTemplates.vue` | 工作流 CRUD、画布、模板 |
| `WorkflowShare.vue` | 分享态工作流运行 |
| `AgentPlans.vue` | Agent 计划树、HITL、重试/跳过 |
| `MultiAgent.vue` | 多 Agent 运行与步骤查看 |
| `ToolsNew.vue` | 四类工具管理与调试 |
| `LlmModels.vue` / `ModelPrices.vue` / `ModelProviders.vue` | 模型、价格、提供商 |
| `PromptTemplates.vue` | 模板 CRUD 与版本 |
| `Tags.vue` | 知识库/文件标签 |
| `Users.vue` / `Roles.vue` / `Quotas.vue` | 用户、角色、配额 |
| `ShareLinks.vue` / `ApiKeys.vue` | 分享链接、API Key |
| `AuditLogs.vue` | 审计日志 |

---

## 3. 本系统的真实强项（相对主流不弱甚至更好）

1. **检索链路“可调、可观测、可缓存”三件事都做了。**
   多数开源项目只提供“向量 + 关键词 + 重排”开关；本系统把 RRF、阈值、扩窗、候选倍数、查询改写模式、语义缓存阈值都做成窗口级配置，且检索指标回传前端。证据：`RagRetrieveService`、`RetrievalOptions`、`SemanticCacheService`。

2. **评估是“产品内建”，不是外部脚本。**
   创建聊天窗口即可选评估方案，LLM-as-judge 异步执行、多 judge 一致性、按窗口隔离；离线召回脚本与在线口径一致。这在开源 RAG 项目里少见。

3. **Agent 的 HITL 与断点恢复是完整闭环。**
   Planner/Reflection/Replan + 6 个控制接口 + 刷新恢复 + 分享态 HITL 鉴权，明显强于“只会跑 ReAct 循环”的实现。

4. **安全边界做得比同类细。**
   SSRF 每次重定向重新解析 DNS、工具结果截断、分享态 safeMode、API Key scopes + IP 白名单、嵌入来源域名/IP 白名单、旧 token 立即失效。

5. **治理能力齐全。**
   配额（日/月、多资源类型）、成本估算、审计、限流、降级（Redis/pgvector/压缩/重排）都有代码兜底。

---

## 4. 文档声称 vs 代码实际

| # | README / 文档说法 | 代码实际 | 判断 |
|---|---|---|---|
| 1 | “后端 18 个模块” | `pom.xml` 只有 17 个 Maven 模块；`eval/`、`scripts/` 不是模块 | **口径偏差**，需修正表述 |
| 2 | “ground truth 标注后计算 Recall@K / Precision@K / MRR / NDCG” | 离线 `RetrievalEvaluationService` 只算 Recall@K、Precision@K、命中率、覆盖率、延迟；MRR/NDCG 在在线指标里按“是否命中”计算 | **夸大**：离线没有 MRR/NDCG |
| 3 | “审计日志：`@Audit` 异步记录写操作” | 仅 8 个 Controller 标注 `@Audit`；用户/角色/配额/工作流/Agent/开放 API 未覆盖 | **覆盖不全** |
| 4 | “语义缓存：按知识库隔离的语义缓存” | key 实际是 `chatWindowId + 知识库版本签名 + 查询哈希`，是窗口级而非纯知识库级 | **表述不精确** |
| 5 | “多模型故障转移” | 只在首 token 之前切换；流式开始后失败不会切换 | **能力有限**，需说明边界 |
| 6 | “多模态：支持图片问答” | 仅“把图片传给 vision 模型”，没有图片入库、图片向量检索、图文混合召回 | **不是多模态 RAG** |
| 7 | “Redis 8.10.1 接入” | 有 Redis 实现，但 `DefaultPublicAccessStore` 可内存降级；Redis 不是强依赖 | **不算错，但需说明可选** |

**代码里有、文档没写（或写得很弱）的点**：

- `RagContextBudgeter` 的上下文预算分配规则；
- HYDE 会**替换**原查询而不是与原查询融合；
- RRF 的 `k=60`、重排候选 `topK×3` 上限 30、语义缓存阈值 0.95 等硬编码参数；
- API Key 的 scopes 与 IP 白名单、分享链接的域名/IP 白名单；
- 配额周期 DAY/MONTH 与资源类型；
- 分享态工作流的 `safeMode` 白名单（HTTP 仅 GET/HEAD、禁 MCP）；
- 多 Agent HITL 断点续跑；
- 在线正确率是 3-gram 启发式（`calculateAccuracyRate`），不是语义评估；
- 语义缓存在知识库重切/删除时主动失效；
- 知识库重建的 staging 表切换与失败回滚。

---

## 5. 对照对象选择与能力矩阵

### 5.1 为什么选这 6 个

| 对象 | 选择理由 |
|---|---|
| **Dify** | 开源 LLM 应用平台的事实基线：RAG + 工作流 + Agent + 多模型 + 可观测，功能面最全，最适合当“上限参照”。 |
| **RAGFlow** | 以“深度文档解析 + 可视化 Ingestion Pipeline”立身，正好对照本系统最弱的解析环节。 |
| **FastGPT** | 国内知识库问答与工作流代表，API / OpenAI 兼容 / 团队空间做得成熟，对照开放集成与多租户。 |
| **MaxKB** | 企业知识库问答代表，强调开箱即用、RBAC/SSO、审计，对照企业治理。 |
| **Coze（Coze Studio）** | Agent/插件/工作流与开放 API/SDK 的代表，且已开源可自部署，对照 Agent 生态与嵌入能力。 |
| **Open WebUI** | 自托管对话前端代表，对照“聊天体验 + 多用户 + 轻 RAG”这一路线。 |

> 未选 AnythingLLM / QAnything：前者与 Open WebUI 定位重叠且更偏桌面端；后者偏“本地文档问答一体机”，企业治理与编排维度样本信息不足。矩阵中不再单列。

### 5.2 能力矩阵

图例：● 有且较完整　◐ 有但有限/需插件　○ 基本没有  
（本系统列全部由代码核实；其余列来自公开资料†，可能随版本变化）

| 维度 | 本系统 | Dify† | RAGFlow† | FastGPT† | MaxKB† | Coze Studio† | Open WebUI† |
|---|---|---|---|---|---|---|---|
| 知识库与解析质量 | ◐ | ● | ● | ◐ | ◐ | ◐ | ◐ |
| 检索策略与效果调优 | ● | ● | ● | ● | ◐ | ◐ | ◐ |
| 编排能力（工作流/Agent） | ● | ● | ● | ● | ● | ● | ◐ |
| 模型接入与路由 | ◐ | ● | ● | ● | ● | ● | ● |
| 多租户与权限 | ◐ | ● | ● | ● | ◐ | ● | ◐ |
| 开放集成（API/SDK/嵌入） | ◐ | ● | ● | ● | ● | ● | ● |
| 可观测与评测 | ◐ | ● | ◐ | ◐ | ◐ | ● | ○ |
| 部署与运维 | ◐ | ● | ● | ● | ● | ● | ● |

### 5.3 “部分”和“没有”具体差在哪

**知识库与解析质量：本系统 ◐**
- 有：12 类格式、9 种切分、结构化/语义切分、表格整块保留、content_hash 去重、版本快照。
- 差：没有 OCR（扫描 PDF/图片直接空文本）、没有版面分析（双栏 PDF 顺序错乱）、没有表格结构还原（合并单元格/跨页表格丢失）、没有图片索引与图文混合召回、没有 URL/网盘/数据库连接器与增量同步。
- 对照：RAGFlow 的 Parser/Chunker/Indexer 可视化流水线、多栏检测、OCR、表格模板†；Dify 已支持多模态知识库†；FastGPT 也提供 OCR/MinerU 扩展与训练队列†。

**检索策略与效果调优：本系统 ●**
- 与 Dify/RAGFlow/FastGPT 的差距主要在“可配置颗粒度”和“评测闭环”，而不在有没有混合检索。
- 本系统缺少：按元数据过滤的 UI 化条件构造器（有标签但没有 Dify 那种 metadata filter）、多路召回的独立权重调节（RRF 固定 k）、按 query 类型自动路由检索策略。

**编排能力：本系统 ●**
- 有：工作流 11 节点、版本/回滚、Cron/Webhook、模板、SubWorkflow、并行、HITL；单 Agent 与 6 种多 Agent 模式。
- 差：没有代码执行节点（Code/Script）、没有可视化单步调试与节点级 mock、没有画布内变量实时预览；多 Agent 的 GroupChat 路由依赖 LLM 选择，缺少可视化会话轨迹回放。
- 对照：Coze/Dify 都有代码节点、试运行与节点级日志†；RAGFlow 0.25 有 Agent 沙箱与用户级记忆†。

**模型接入与路由：本系统 ◐**
- 有：OpenAI 兼容、DashScope、DeepSeek、自定义；模型路由组 + 首 token 前故障转移；vision 标记；embedding/chat/rerank 分角色。
- 差：没有 Azure OpenAI / Bedrock / Vertex / Ollama 原生适配（可借 OpenAI 兼容端点绕过，但缺原生鉴权与模型目录）；没有按成本/延迟的自动路由策略；没有模型级限流与熔断；没有 playground 对比。
- 对照：Dify/FastGPT/MaxKB 都提供数十家提供商与统一模型目录†。

**多租户与权限：本系统 ◐（最关键的缺口）**
- 有：登录、RBAC、18 个权限码、配额、审计、分享隔离、会话 `user_identifier` 隔离。
- 差：**没有租户/工作空间/团队成员模型**；知识库、聊天窗口、工作流、工具、模型、Prompt 模板都是全局资源，任何有功能权限的用户都能看到并使用；没有资源级 ACL（私有/团队/公开）；审计未覆盖全部管理动作。
- 对照：Dify 有 workspace + 成员角色†；FastGPT 有团队空间（V4.6 起）与商业版 RBAC/SSO†；MaxKB 专业版提供 RBAC/SSO/操作日志†；Coze 有空间与协作†。

**开放集成：本系统 ◐**
- 有：4 个开放 REST 接口、API Key（scopes/IP/过期/撤销）、iframe Widget、SSE。
- 差：**没有 Python/JS SDK**、没有 OpenAI 兼容的 `/v1/chat/completions` 入口、没有 Webhook 事件订阅、没有把本系统作为 MCP Server 暴露、没有批量导入/导出 API。
- 对照：Dify/FastGPT/Coze 均提供 SDK 与 OpenAI 兼容 API†；Coze 开源版明确包含 API/SDK†。

**可观测与评测：本系统 ◐**
- 有：检索指标、LLM-as-judge、成本、配额、审计、Prometheus 指标、请求日志。
- 差：没有分布式链路追踪（trace/span）、没有逐请求的检索/生成阶段耗时瀑布、没有评测基准集版本化与 CI 回归门禁、生成侧没有 Context Precision/Recall（RAGAS 全套）、没有告警规则与看板。
- 对照：Dify 有单次运行 tracing 与长期监控†；Coze Loop 提供观测与评估†；RAGFlow/FastGPT 偏基础。

**部署与运维：本系统 ◐**
- 有：Docker Compose、dev/test/prod 多环境、健康检查、降级开关、逻辑删除、幂等 SQL。
- 差：没有 K8s/Helm、没有多实例共享状态（限流/语义缓存/取消信号部分依赖单机内存）、没有备份恢复脚本与迁移回滚、上传是单请求 50MB（无分片/断点续传）、pgvector 单库单表无分片与归档策略。
- 对照：Dify/RAGFlow/FastGPT/MaxKB/Open WebUI 都提供容器化与较成熟的部署文档†。

---

## 6. 实质性缺口（按“影响用户能不能用起来”排序）

| 优先级 | 缺口 | 具体表现 | 主流怎么做† | 代价 | 现在做？ |
|---|---|---|---|---|---|
| **P0** | **多租户与资源归属** | KB/窗口/工作流/工具/模板全局共享；无 workspace、无资源 ACL；审计不全 | Dify workspace + 角色；FastGPT 团队空间；MaxKB 专业版 RBAC/SSO | **中**（加 tenant_id/owner_id + 查询过滤 + 迁移存量数据） | **是**，若系统会给多个团队用 |
| **P0** | **文档解析深度** | 无 OCR、无版面分析、无表格结构还原、无图片索引 | RAGFlow Parser 流水线、多栏检测、OCR；Dify 多模态 KB；FastGPT OCR/MinerU | **中**（接 OCR/版面服务 + 表格转 Markdown + 解析质量回归集） | **是**，企业文档场景必做 |
| **P0** | **引用可核验** | 引用只到文档名+分数，无 chunk 编号、页码、原文高亮、跳转 | Dify/RAGFlow 的 chunk 级引用与原文定位 | **小–中**（元数据补 page/offset，前端做引用面板） | **是**，直接影响信任 |
| P1 | 开放集成生态 | 只有 REST + Widget，无 SDK/OpenAI 兼容/Webhook | Dify/FastGPT/Coze 均有 SDK 与 OpenAI 兼容 API | **小–中**（先做 `/v1/chat/completions` 兼容层 + Python SDK） | 是，若要对开发者 |
| P1 | 评测闭环不完整 | 生成侧只有 2 个指标；无 Context Precision/Recall；无基准集版本化与 CI 门禁 | Dify 有 tracing；Coze Loop 有评估；RAGAS 生态 | **中**（补 judge 指标 + 数据集版本 + CI 脚本） | 是，若要把效果当 KPI |
| P1 | 知识库生命周期 | 无连接器/增量同步/定时重建；无文档权限；无标注纠正回流 | RAGFlow/Dify 的数据源同步；FastGPT API 知识库 | **中–大**（连接器框架 + 增量索引 + 纠正样本回流） | 视场景，有外部数据源则必须 |
| P2 | 工作流表达与调试 | 无代码节点、无单步调试/节点 mock、无画布变量预览 | Dify/Coze 代码节点与试运行；RAGFlow Agent 沙箱 | **中** | 可延后 |
| P2 | 规模化与可观测 | 单实例状态、无分布式 trace、无 K8s/Helm、无备份恢复 | 主流均提供 K8s 部署与可观测集成 | **大** | 有生产规模诉求再做 |
| P2 | Agent 长期记忆/沙箱 | 仅有会话压缩；无跨会话用户记忆；无代码沙箱 | RAGFlow 0.25 用户级记忆 + Agent 沙箱；Coze 记忆 | **大** | 可延后 |
| P3 | 模型接入广度 | 4 类提供商；无 Azure/Bedrock/Vertex/Ollama 原生 | 主流提供数十家 | **小** | 按客户需求补 |

**为什么把“多租户”排在“解析”前面**：解析差会让答案不准，用户还能感知并绕过（换文件、人工校对）；但没有资源归属会让“多团队共用”这件事从根上不成立，且是数据泄露风险，属于“能不能上线”的问题。

---

## 7. 产品级必要能力 vs 锦上添花

### 产品级必要（缺了就不能作为产品交付）

1. **资源归属与租户隔离**：至少做到 `owner + 可见性（私有/团队/公开）`，否则不能卖给第二个团队。
2. **文档解析质量底线**：扫描件 OCR、双栏顺序、表格转结构化；否则企业知识库入库即失真。
3. **引用可核验**：chunk 级引用 + 原文定位；否则用户无法判断答案真假。
4. **开放集成**：OpenAI 兼容入口 + 至少一种 SDK；否则接不进客户现有系统。
5. **评测回归**：固定基准集 + CI 门禁；否则每次改检索/换模型都是“拍脑袋”。
6. **备份恢复与升级路径**：SQL 幂等已有，但缺备份/回滚/多实例方案。

### 锦上添花（有更好，不影响能不能用）

- 6 种多 Agent 模式（实际 80% 场景用不到 3 种以上）；
- 语义缓存（已有，收益取决于重复查询比例）；
- Prompt 模板市场、模板版本；
- 图文多模态问答；
- Widget 主题定制；
- 离线召回脚本的更多图表。

---

## 8. 决策建议

### 如果目标是“内部单团队使用”

按这个顺序做，投入产出最高：

1. **引用可核验（S–M）**：解析时记录 `page/offset/chunkIndex`，前端引用面板支持展开原文与跳转。这是信任的第一道门槛。
2. **解析质量（M）**：接 OCR（PaddleOCR/Tesseract 任一）+ 表格转 Markdown；建立 20–30 份真实文档的解析回归集。
3. **评测回归（M）**：把 `wgh_kb_ground_truth` 做成版本化数据集，补 Context Precision/Recall，接入 CI 只在 `-pl rag-kb -am` 跑。
4. **工作流调试（M）**：单步运行 + 节点输入输出查看，降低编排门槛。
5. **开放 API 兼容层（S）**：`/v1/chat/completions` + Python SDK，方便业务系统接入。

### 如果目标是“对外产品 / 多团队 SaaS”

必须先把这三件事做完，再谈体验：

1. **租户与资源 ACL**（M）：`tenant_id` + `owner_id` + `visibility`，所有查询强制过滤；存量数据迁移脚本；审计补齐到全部管理接口。
2. **解析质量**（M）：同上。
3. **引用可核验**（S–M）：同上。

之后再做：SDK/OpenAI 兼容、评测门禁、连接器同步、K8s 部署与可观测。

### 最该优先补的 3 个缺口（我的判断）

1. **资源归属/租户隔离**——决定“能不能多团队用”。
2. **文档解析深度（OCR + 表格）**——决定“入库后准不准”。
3. **chunk 级引用与原文定位**——决定“用户信不信”。

---

## 9. 附：依据的代码位置索引

| 主题 | 关键文件 |
|---|---|
| 模块与依赖 | `rag-backend/pom.xml`、各模块 `pom.xml` |
| 安全与鉴权 | `rag-auth/src/main/java/com/wgh/rag/auth/config/SecurityConfig.java`、`service/JwtService.java`、`service/OidcTokenVerifier.java`、`constant/AuthConstants.java` |
| 接口面 | `rag-controller/src/main/java/com/wgh/rag/controller/*.java` |
| 数据模型 | `rag-backend/sql/schema/*.sql`（37 张表） |
| 解析与切分 | `rag-kb/.../service/DocumentParserDispatcher.java`、`parser/*.java`、`splitter/*.java` |
| 检索链路 | `rag-kb/.../service/RagRetrieveService.java`、`KeywordSearchService.java`、`RrfFusion.java`、`RerankService.java`、`QueryTransformService.java`、`SemanticCacheService.java`、`RetrievalOptions.java` |
| 向量存储 | `rag-kb/.../vector/**`、`rag-llm/.../ModelCacheManager.java` |
| 对话流 | `rag-chat/.../service/RagChatService.java`、`ChatStreamCancellation.java`、`ContextCompressionService.java`、`constant/ChatEvents.java` |
| 评估 | `rag-chat/.../evaluation/LlmJudgeService.java`、`RetrievalMetricsCalculator.java`、`rag-kb/.../service/RetrievalEvaluationService.java`、`rag-backend/eval/eval_recall.py` |
| Agent | `rag-agent/.../service/AgentOrchestrator.java`、`rag-controller/.../AgentController.java` |
| 多 Agent | `rag-agent-multi/.../runtime/MultiAgentRuntime.java`、`crew/CrewCoordinator.java`、`groupchat/GroupChatManager.java` |
| 工作流 | `rag-workflow/.../engine/GraphValidator.java`、`WorkflowRuntimeService.java`、`constant/WorkflowConstants.java`、`WorkflowTriggerController.java` |
| 工具与 SSRF | `rag-tool/.../executor/ToolExecutionEngine.java`、`HttpToolExecutor.java`、`McpToolExecutor.java`、`service/SsrfGuard.java` |
| 分享/开放 API | `rag-public-access/.../service/ShareLinkService.java`、`ApiKeyService.java`、`PublicAccessRateLimiter.java`、`entity/ShareLink.java`、`entity/ApiKey.java` |
| 配额/成本 | `rag-quota/.../service/QuotaService.java`、`entity/ModelPrice.java` |
| 审计 | `rag-log/.../aspect/AuditAspect.java`、`AccountAuditService.java` |
| 前端页面 | `rag-frontend/src/views/**`、`rag-frontend/src/router/index.js` |

---

## 10. 外部信息来源与时效声明

以下为第 5、6 节 `†` 标注内容的参考来源，均为公开资料，**可能随产品版本变化**，不构成本仓库事实：

- Dify：官网与博客关于 Agentic Workflow、多模态知识库、New Agent 观测能力的说明（`dify.ai`）。
- RAGFlow：官方文档 Dataset Configuration、Chunker/Indexer 组件、0.25 Ingestion Pipeline 与 Agent Sandbox/用户级记忆（`ragflow.io/docs`、`ragflow.io/blog`）。
- FastGPT：官方文档 V4.6（团队空间、多向量、ReRank）、V4.15/V4.17（API、模型管理、Skill）（`doc.fastgpt.io`）。
- MaxKB 与 FastGPT 对比资料：RBAC/SSO/操作日志等企业治理差异（`fastgpt.cn/compare`）。
- Coze / Coze Studio：官方功能说明与开源范围（Agent、工作流、知识库、API/SDK、观测与评估）（`docs.coze.com`、`byteplus.com`）。
- AnythingLLM / Open WebUI：2026 年公开对比文章（文档 RAG、引用粒度、多用户与 OAuth）（`anythingllm.com`、第三方评测）。

> 本系统相关结论均来自本仓库代码与 SQL，不依赖上述外部信息。
