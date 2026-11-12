# 架构对照审视报告（2026-11-04）

> **版本**：v1.1（2026-11-09 复核）｜初版 v1.0：2026-11-04；修订记录见 §8。
> **性质**：只读审视。未修改任何业务代码，未执行 `mvn` / `npm`，未提交 commit。2026-11-09 复核同为静态核查。
> **范围**：后端 17 个模块 + `rag-frontend`。
> **方法**：先做全量结构扫描（模块、类规模、注解覆盖、SQL 索引），再对关键链路逐文件读代码取证。每条发现均给出文件路径与行号，**凡未读到代码的推断一律不写入**。
> **证据口径**：`文件路径:行号`；所述"主流做法"指 Spring Boot / LangChain4j / pgvector 生态的通用工程实践，不代表唯一正确解。

---

## 1. 结论先行：最该改的几件事

| 序 | 问题 | 等级 | 一句话理由 |
|---|---|---|---|
| 1 | **会话身份硬编码 `"default_user"`，且 `userIdentifier` / `sessionId` 由客户端随意传入** | **P0** | 所有登录用户共享同一个会话桶，且可通过参数读取/删除他人会话。这是功能缺陷 + 横向越权，改动只有几十行。 |
| 2 | **模型配置实体直出，`apiKey` 明文返回给任意登录用户** | **P0** | `GET /api/models/chat`、`/chat/{id}`、`/chat/list` 无权限注解，返回 `LlmModel` 全字段（含 `apiKey`）。凭据泄露，修复成本极低。 |
| 3 | **管理能力授权覆盖不足：17 个 Controller 中仅 4 个用 `@RequiresPermission`、3 个手工 `requireAdmin`，其余 10 个管理类接口仅"登录即可"** | **P0** | 任何体验/只读账号可改聊天窗口、建 HTTP 工具、跑工作流、吊销 API Key。 |
| 4 | **Markdown 渲染 `v-html` 且无消毒** | **P1** | LLM 输出/知识库内容可注入脚本，token 存在 `localStorage`，可升级为账号接管。引入 DOMPurify 即可。 |
| 5 | **Agent / Workflow 运行态接口无归属校验** | **P1** | 拿到 `planId` / `runId` 即可查看他人计划、确认他人 HITL 步骤、取消他人运行。分享链路有校验，登录链路没有。 |
| 6 | **pgvector 未启用 ANN 索引（`use-index: false`）** | **P1** | 向量检索退化为全表扫描，数据量上万后 P95 延迟线性上升。配置 + 建索引即可，代价很低。 |

> **2026-11-09 复核**：#1、#3、#4、#5 已修复；#2 部分修复（`apiKey` 已隐藏，`shareToken` / `executorConfig` 仍直出）；#6 已补 HNSW（默认开启）与 store 缓存，仅 `ef_search` 未调。逐条状态见 §3 各 F-xx 下的复核行。

---

## 2. 总体评级

| 领域 | 现状水平 | 主要风险 | 结论 |
|---|---|---|---|
| 认证（本地 HS256 + OIDC 双轨） | 中上 | 无明显漏洞 | 实现质量优于多数同类项目，**不建议动** |
| 授权（角色/权限） | 中上（2026-11-09 复核） | 前端门控仍不全 | 后端 13 个 Controller 共 104 处 `@RequiresPermission`，管理类接口已全覆盖；前端仍只有 3 个 admin 页面做门控 |
| 配额 | 中上 | 无明显漏洞 | 原子 upsert + 唯一约束，**符合主流** |
| 公开访问（分享/Widget/API Key/限流） | 中上 | 多实例限流放大、Key 生命周期 | 设计完整，细节可优化 |
| 文件存储 | 中上 | 无明显漏洞 | 路径穿越有防护，**符合主流** |
| 文档解析 | 中上 | 无明显漏洞 | SPI 分发 + 大小校验，**符合主流** |
| RAG 检索与重排 | 中 | 重复 embedding、`ef_search` 未调 | 链路设计完整（RRF/rerank/降级）；2026-11-09 复核：HNSW 已默认开启、store 有 LRU 复用，剩余问题是重复 embedding 与 ANN 参数未调优 |
| 对话与 SSE | 中 | 裸线程、历史无界 | 功能完整；断连取消已补（`ChatStreamCancellation`），线程模型与历史加载仍需收敛 |
| Agent 编排 | 中 | 状态机不可中断、双引擎重复 | 主链路可运行，工程化不足 |
| 工作流引擎与图校验 | 中上 | 每运行建线程池、跨存储一致性 | 拓扑/并行/环路校验到位，运行期资源模型可优化 |
| 可观测性 | 中上 | 无显著问题 | MDC requestId + Micrometer + 滚动日志，**符合主流** |
| 错误码体系 | 中（但与主流偏离） | 监控语义丢失 | 业务异常仍统一 HTTP 200；2026-11-09 复核：AccessDenied 403、唯一键 409、参数类型 400、404/500 已按 HTTP 语义返回 |
| 审计日志 | 中 | 覆盖不全 | AOP + 异步落库可用；聊天窗口/工具已补 `@Audit`，工作流/Agent 仍缺 |

---

## 3. 详细发现

### 3.1 安全与健壮性

#### F-01（P0）会话身份硬编码，跨用户会话可见 / 可删 / 可续聊

> **2026-11-09 复核：已修复。** `RagChatService` 改用 `SecurityUtils.currentUsername()`，`ensureSession` 校验会话归属（[RagChatService.java:1496-L1498](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1496-L1498)）；`ChatController` 不再接收外部 `userIdentifier`。

- **现状**
  - `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java:1361`：`ensureSession(...)` 内部 `String userIdentifier = "default_user";` —— 新会话一律落到固定桶。
  - `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/ChatController.java:135,161,169,178`：`listSessions` / `pageMessages` / `createSession` / `deleteSession` 均把 `userIdentifier` 作为 **请求参数**，默认值 `"default_user"`。
  - `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/ChatWindowService.java:262-265`：会话列表按 `chat_window_id + user_identifier` 查询，因此所有登录用户看到的是**同一批会话**。
  - `ensureSession` 对传入 `sessionId` 只做存在性/状态校验，**不校验会话归属**，任何人拿到 sessionId 即可继续对话并向该会话写入。
- **主流做法**：身份只从 `SecurityContext`（`SecurityUtils.currentUsername()`）派生，服务端不接受客户端声明的用户标识；所有按 id 的读写都做 `owner == currentUser` 校验（或把 owner 作为查询条件之一）。
- **差距**：身份来源不可信 + 归属校验缺失，二者叠加成完整的横向越权链。
- **影响**：**安全（跨用户数据泄露/篡改、会话污染）+ 功能（用户看不到自己的会话、体验账号与管理账号混在一起）**。
- **改动范围与代价**：`RagChatService.ensureSession` 改为从 `SecurityUtils` 取身份；`ChatController` 4 个接口删除 `userIdentifier` 参数（分享链路走 `PublicShareController`，用访客标识，单独保留）；`ChatWindowService` 的按 id 查询统一加归属条件。前端 `rag-frontend/src/api/index.js` 相应少传一个参数。**约 1-2 人日**，无表结构变更（`user_identifier` 列已存在）。
- **风险**：历史数据全部在 `default_user` 桶下，切换后老会话在 UI 中"消失"（数据仍在）；需要一次数据归属迁移或明确"仅新会话生效"。分享访客链路（`PublicShareController` 用访客 sessionKey）必须与登录链路分开处理，避免把匿名访客也绑定到用户名。

#### F-02（P0）模型配置实体直出，`apiKey` 明文泄露

> **2026-11-09 复核：部分修复。** `LlmModel.apiKey` 已改为 `@JsonProperty(access = WRITE_ONLY)`，不会被序列化返回；但 `Workflow.shareToken`、`ToolConfig.executorConfig` 仍无 `@JsonIgnore`/`WRITE_ONLY`，仍随实体直出（[Workflow.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/entity/Workflow.java)、[ToolConfig.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/entity/ToolConfig.java)）。

- **现状**
  - `rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/entity/LlmModel.java:46`：`private String apiKey;`，类上为 `@Data`，**无 `@JsonIgnore`**（全模块检索无 `JsonIgnore`）。
  - `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/LlmModelController.java:31-37,39-43,70-74`：三个 GET 接口直接 `Result<LlmModel>` / `Result<List<LlmModel>>`，且**均未加 `@RequiresPermission`**，只要登录即可读。
  - 对照：写接口（46-68）有 `@RequiresPermission(PERM_MODEL_MANAGE)`，说明"管理"意图存在，只是读接口漏了。
- **主流做法**：对外 DTO 与持久化实体分离；密钥类字段只做"掩码读"（`sk-****abcd`）或在服务端脱敏，写操作单独接收明文。
- **差距**：实体即 DTO，且敏感字段无序列化排除。
- **影响**：**安全（任一体验账号可拿到全部供应商 API Key，直接造成费用/配额损失）**。
- **改动范围与代价**：最小改法 = 服务层在返回前 `setApiKey(null)` 或掩码（3 处）；更稳的改法 = 新增 `LlmModelVO`。**0.5-1 人日**。
- **风险**：若前端表单依赖 `apiKey` 回填编辑，掩码后编辑保存会把掩码写回库；需要在保存时识别掩码值或改为"不修改则留空"。同类问题还有：`Workflow.shareToken`（`rag-workflow/.../entity/Workflow.java:34`，经 `WorkflowController.java:44-60` 的 `Result<Workflow>` 直出，可导致他人无需授权进入分享运行页）与 `ToolConfig.executorConfig`（`rag-tool/.../entity/ToolConfig.java:41`，经 `ChatController.java:366-460` 直出，可能含 HTTP 鉴权 Header）。建议一并做字段级排除。

#### F-03（P0）授权落地不完整：10 个 Controller 仅"登录即可"

> **2026-11-09 复核：基本修复。** 13 个 Controller 共 104 处 `@RequiresPermission`，覆盖聊天、工作流、Agent、多 Agent、Prompt 模板、API Key、分享链接、模型价格、检索评测等；`AdminUser/AdminRole/Quota` 仍为手工 `requireAdmin`（可接受）；前端仍只有 3 个 admin 页面做门控，属剩余缺口。

- **现状**（自动统计，`rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/`）
  - 使用 `@RequiresPermission`：`KnowledgeBaseController`、`LlmModelController`、`TagController`、`AdminStatsController`（共 4 个）。
  - 手工 `requireAdmin`：`AdminUserController`、`AdminRoleController`、`QuotaAdminController`（共 3 个）。
  - **无任何授权（仅要求登录）的 10 个管理类 Controller**：`ChatController`（聊天窗口/工具/技能 CRUD，`:60-521`）、`WorkflowController`（工作流 CRUD + 运行 + HITL + 取消）、`ApiKeyAdminController`（列出/吊销任意 Key）、`ShareLinkAdminController`、`ModelPriceController`、`PromptTemplateController`、`AgentController`、`MultiAgentController`、`QuotaUserController`、`RetrievalEvaluationController`。
  - 另有 4 个公开入口（`AuthController`、`PublicShareController`、`OpenApiController`、`WorkflowTriggerController`）按设计走各自的 token/API Key 校验，不在此列。
  - 前端同样只在 `rag-frontend/src/router/index.js:36-38` 给用户/角色/配额三个页面加了 `requiresAdmin`；知识库、模型、工作流、工具、API Key、开放平台等管理页对所有登录用户可见。与后端 10 个未授权 Controller 叠加后，前端可见性等价于可用性。
  - `ApiKeyAdminController` 的 page/revoke 不按 `createdBy` 过滤，可吊销他人 Key（`rag-public-access/.../ApiKeyService.java` 对应方法无归属参数校验）。
- **主流做法**：URL 级统一授权（`SecurityFilterChain` 按前缀 + 方法维度，或方法级 `@PreAuthorize`），新增接口默认拒绝（deny-by-default），前端菜单仅作体验兜底。
- **差距**：授权靠"逐个记得加注解"，漏一个就是越权；且缺少默认拒绝策略。
- **影响**：**安全（只读账号可执行写/删/外部请求）+ 合规（审计无法证明权限边界）**。
- **改动范围与代价**：给上述 Controller 补 `@RequiresPermission`（复用 `AuthConstants` 现有权限码），或在 `SecurityConfig` 增加按前缀的 `hasAuthority` 规则；前端补 `meta.requiresPermission`。**2-3 人日**（机械性工作，风险主要在权限码设计）。
- **风险**：若权限码粒度过细而角色未同步授权，会造成"管理员也 403"；建议先按"读/写/管理"三档收敛，再逐步细化。**注意不要改动 `SecurityConfig` 的既有白名单语义（`/api/public/**`、`/api/open/**`）**。

#### F-04（P1）前端 Markdown 渲染存在 XSS

> **2026-11-09 复核：已修复。** 新增 `utils/markdown.js`，统一用 DOMPurify 清洗后再 `v-html`；聊天、分享聊天、公开分享、多 Agent、工作流分享等页面均改为调用 `renderMarkdown`。

- **现状**
  - `rag-frontend/src/views/chat/ChatPlayground.vue:953` `renderMarkdown` 用 `marked.parse` 直接返回 HTML，模板 `:72` 用 `v-html` 渲染。
  - 同样的写法至少还有 5 处：`rag-frontend/src/views/chat/PublicShareChat.vue:324`（`:77`）、`rag-frontend/src/views/multi-agent/MultiAgentPlayground.vue:311`（`:77`）、`rag-frontend/src/views/workflow/ShareWorkflow.vue:90`（`:21`），以及登录态分享路由使用的 `rag-frontend/src/views/chat/ShareChat.vue:797`。
  - 初版记录：全仓库无 DOMPurify / sanitize-html（检索无命中）。**2026-11-09 复核：已引入 DOMPurify 并统一到 `utils/markdown.js`。**
- **主流做法**：`marked` + `DOMPurify.sanitize`（或改用 `marked` 的 sanitize 扩展）；Chat 类应用必须假设模型输出不可信。
- **差距（初版）**：无任何输出消毒。**2026-11-09 复核：已由 DOMPurify 关闭该差距。**
- **影响**：**安全**。模型可被提示词注入产出 `<img src=x onerror=...>`；知识库文档亦可携带 payload；`localStorage.wgh_token` 可被读取，等价账号接管。分享页对匿名访客同样成立。
- **改动范围与代价**：新增 `dompurify` 依赖，把 5 处 `renderMarkdown` 抽到 `utils/markdown.js` 并统一消毒。**0.5 人日（已完成）。**
- **风险**：消毒后部分 HTML（如自定义样式表格）可能被裁剪；需回归引用角标/代码块渲染。

#### F-05（P1）Agent / Workflow 运行态接口缺少归属校验

> **2026-11-09 复核：已修复。** Agent 计划通过 `assertPlanAccessible` 校验归属，工作流运行实例通过 `RunAccessGuard` 校验；跨用户访问返回拒绝。

- **现状**
  - `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/AgentController.java:37-46`（按 `planId` 查状态）、`:84-99`（按 `stepId` 做 HITL 确认），均未见归属校验。
  - `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/WorkflowController.java:189,195,204`（`runId` 查状态、取消、HITL 确认）同样没有归属校验。
  - 对照组：分享链路已实现 `assertRunOfWorkflow`（`rag-workflow/WorkflowService` 分享分支），说明校验模式已存在，只是登录链路没接上。
- **主流做法**：运行记录写入 `owner`（或 `session_id`），所有按 id 的操作先 `assertOwner`。
- **影响**：**安全（横向越权）**；且 HITL 确认可被他人代替，属于业务流程篡改。
- **改动范围与代价**：`WorkflowRun` / `AgentPlan` 增加/复用 `user_identifier`，Controller 入口加 2-3 行断言。**1 人日**（含存量数据兼容）。
- **风险**：旧 run/plan 无归属字段，需要 `NULL` 时回退为"仅 admin 可查看"，否则历史数据不可访问。

#### F-06（P1）HTTP 工具与工作流 HTTP 节点无 SSRF 防护

> **2026-11-09 复核：已修复。** 新增 `SsrfGuard`，对初始 URL 与重定向目标做协议/私网/回环校验，并限制响应体大小；`HttpToolExecutor` 已接入，工作流 HTTP 节点复用同一执行器。

- **现状**：`rag-backend/rag-tool/src/main/java/com/wgh/rag/tool/executor/HttpToolExecutor.java:60-98`，按工具配置的 `url/method/headers` 直接 `httpClient.send`，**无协议/主机/IP 白名单，无内网段拦截**；工具创建与 `/tools/{id}/debug` 位于 `ChatController:366-521`，同样无权限注解。
- **主流做法**：出站请求统一走受控客户端：协议仅 http/https、解析后 IP 拒绝环回/链路本地/私有网段（可配置白名单）、限制重定向与响应大小。
- **影响**：**安全（内网探测、云元数据端点访问、任意出站写请求）**。分享链路已有 `safeMode` 限制（仅 GET/HEAD、禁 MCP），但登录链路无同等约束。
- **改动范围与代价**：在 `HttpToolExecutor` 增加 host/IP 校验工具类，`AgentNodeExecutor` / `HttpNodeExecutor` 复用。**1-2 人日**。
- **风险**：合法的内网集成（如企业内 API）会被误杀，需提供配置化白名单；DNS 重绑定需在连接层校验而非仅解析层。

#### F-07（P2）CORS 允许任意来源 + 携带凭证

> **2026-11-09 复核：已修复。** CORS 来源改为配置项驱动，`allow-credentials` 默认 false；仅公开访问路径按需放开通配来源，不再默认 `*` + 凭证。

- **现状**：`rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/config/SecurityConfig.java:81-84`，`allowedOriginPatterns("*")` + `allowCredentials(true)`。
- **主流做法**：来源白名单来自配置（dev/prod 分离），`allowCredentials` 仅在确有 Cookie 鉴权时开启。
- **影响**：**安全（若有 Cookie/浏览器自动携带凭证的链路则等同 CSRF 面扩大）**；当前 Bearer Token 模式降低实际风险，但分享/嵌入场景（Widget 需要跨域）存在被第三方站点滥用的可能。
- **改动范围与代价**：把来源改为配置项，嵌入场景按 `ShareLink` 白名单动态返回。**0.5 人日**。
- **风险**：本地开发/预览域名需同步加入白名单，否则前端联调 403。

#### F-08（P2）客户端断开后不取消生成

> **2026-11-09 复核：已修复。** 新增 `ChatStreamCancellation`，在 SSE emitter 的 `onTimeout` / `onError` / `onCompletion` 回调中设置取消标记，生成循环检测后停止继续调用模型并落库。

- **现状**：`RagChatService.java:153-155` 的 `onTimeout/onError/onCompletion` 只打日志；`doRagStreamChat` 在子线程继续跑完，未对模型流做 cancel。
- **主流做法**：断连时置取消标记并 `StreamingChatResponseHandler` 侧中断，避免为无人接收的答案继续计费。
- **影响**：**性能/成本**（长回答在用户关页后仍消耗 token 与线程）。
- **改动范围与代价**：增加 `AtomicBoolean cancelled` 并在 send 失败/onError 时置位，处理器内提前结束。**0.5-1 人日**。
- **风险**：需区分"客户端主动中断"与"真实异常"，避免误发错误事件（项目已有此约定）。

#### F-09（P2）API Key 每次调用都写 `lastUsedTime`

> **2026-11-09 复核：已修复。** `lastUsedTime` 更新增加进程内 60 秒节流，避免每次校验都产生一次 UPDATE。

- **现状**：`rag-public-access/.../ApiKeyService.java` 的 `verify()` 在校验成功后更新 `lastUsedTime`（每请求一次写）。
- **主流做法**：内存节流（如每分钟最多落库一次）或异步批量更新。
- **影响**：**性能**（开放 API 高频调用时 DB 写放大）。
- **改动范围与代价**：加时间窗口节流。**0.5 人日**。
- **风险**：`lastUsedTime` 精度下降，用于"最近使用"展示无影响。

#### F-10（P2）审计覆盖不全

> **2026-11-09 复核：部分修复。** 聊天窗口创建/更新/删除与工具管理已补 `@Audit`；`WorkflowController`、`AgentController` 仍未加审计注解，覆盖缺口仍在。

- **现状（初版记录）**：`@Audit` 主要覆盖模型、知识库、API Key、分享链接等（`LlmModelController:47,55,63`）；当时聊天窗口/工具/技能/工作流的增删改没有审计注解。**2026-11-09 复核：聊天窗口与工具已补，工作流/Agent 仍缺。**
- **主流做法**：所有状态变更类接口记录"谁、何时、对什么、结果如何"，与权限变更同等重要。
- **影响**：**合规/可维护性**（出了越权事件无法回溯）。
- **改动范围与代价**：补注解 + 在 `AuditAspect` 采集 `requestId`。**0.5 人日**。
- **风险**：审计异步落库失败只告警（现状），不阻断业务，属可接受。

---

### 3.2 性能

#### F-11（P1）pgvector 未启用 ANN 索引，且每次检索重建 store

> **2026-11-09 复核：基本修复。** `use-hnsw-index` 默认 true（`application.yml:98-L112`），HNSW 索引按表幂等创建（`vector_cosine_ops`），EmbeddingStore 使用 LRU 缓存（`store-cache-max-size:64`），重复 `selectById` 已合并为单次。剩余：`hnsw.ef_search` 仍未设置，查询召回/延迟未调优。

- **现状**
  - `rag-backend/rag-app/src/main/resources/application.yml:86-95`：`wgh.rag.pgvector.use-index: false`。
  - `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java:28-42`：按 `useIndex=false` 构建 `PgVectorEmbeddingStore`，检索走顺序扫描。
  - 同文件 `:29` 每次调用都 `log.info` 建表/连库信息；`RagRetrieveService.java:123-157` 对每个知识库、每次请求都新建一次 store。
  - `RagRetrieveService.java:80` 与 `:114` 对同一 `kbId` 各查一次 `selectById`（重复查询）。
- **主流做法**：`CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)`，查询侧设置 `hnsw.ef_search`；store 按 `collection_name + dimension` 缓存复用。
- **差距**：索引缺位 + 对象重复构建。
- **影响**：**性能**。万级 chunk 以上检索从毫秒级退化到秒级；每次请求的 INFO 日志在并发下成为 I/O 噪声。
- **改动范围与代价**：配置改 `use-index: true`，由现有建表逻辑创建 HNSW；store 增加本地 `ConcurrentHashMap` 缓存；日志降为 debug；合并两次 `selectById`。**1 人日**。
- **风险**：HNSW 建索引期间写入变慢、内存占用上升；`index-list-size` 需按数据量调参。若历史表已存在且未建索引，需要一次性补建（幂等 SQL）。

#### F-12（P1）LLM 上下文历史全量加载，压缩同步阻塞首 token

> **2026-11-09 复核：未修复。** `buildMessageHistory` 仍对会话历史做全量 `selectList`，未加"最近 N 条"窗口；分层压缩已存在，但不改变数据库侧全量读取。

- **现状**：`RagChatService.java:1101-1109` 用 `selectList` 拉取**该会话全部消息**再在内存中交给 `ContextCompressionService` 处理；压缩在流式子线程内、首 token 之前同步执行。
- **主流做法**：按 `(session_id, create_time)` 倒序取最近 N 条（N 由 token 预算推导），更早的内容依赖已持久化的中期摘要/远期事实，不再回表全量。
- **影响**：**性能**。会话越长，每次问答的 DB 传输、内存占用与首 token 延迟线性增长；压缩 LLM 调用的耗时直接叠加在用户可感知的等待上（且压缩失败才降级）。
- **改动范围与代价**：`buildMessageHistory` 改为分页/窗口查询（利用现有 `idx_cm_session_create`（`schema.sql:358`）等索引）；压缩结果已冗余存储在 `wgh_chat_session`（`mid_summary*` / `long_term_facts*` 字段，`schema.sql:288-298`），只需读取最近摘要 + 最近 N 条消息。**1-2 人日**。
- **风险**：窗口过小会丢上下文，需要按模型上下文长度计算预算；与"摘要复用"逻辑（prompt 版本匹配）耦合，需回归压缩链路。

#### F-13（P1）流式链路线程模型：每请求裸线程，每次运行新建线程池

> **2026-11-09 复核：未修复。** `RagChatService` 仍 `new Thread`，`QueryTransformService` 仍自建 cached pool，`WorkflowEngine` 每运行建池，`AgentNodeExecutor` 每节点建池，多 Agent 与上下文压缩也各自建线程；未收敛到统一 `ThreadPoolTaskExecutor`。

- **现状**
  - `RagChatService.java:173-196`：`new Thread(...).start()`，**每聊天请求一个非守护裸线程**，无上限、无队列、无拒绝策略、无优雅停机。
  - `rag-backend/rag-kb/.../QueryTransformService.java:58`：`Executors.newCachedThreadPool()`，无界。
  - `rag-backend/rag-workflow/.../WorkflowEngine.java:148-152`：**每次工作流运行**新建 `ThreadPoolExecutor`（`AbortPolicy`），`:203 shutdownNow`；调度线程用 `Thread.sleep(20)` 忙轮询。
  - `rag-backend/rag-workflow/.../executor/AgentNodeExecutor.java:78-94`：**每个节点执行**新建单线程池做超时控制。
- **主流做法**：应用级有界线程池（核心/最大/队列/拒绝策略 + 线程命名）+ `TaskDecorator` 传递 MDC；SSE 场景通常使用 `spring.mvc.async` 或专用 executor，不占用请求线程。
- **差距**：线程数量随并发线性增长且不可控，故障时表现为"线程耗尽 / 容器 OOM / 无法优雅下线"。
- **影响**：**性能与稳定性**。并发聊天 + 工作流并发时线程数不可预测；`newCachedThreadPool` 在 LLM 超时抖动时可能瞬间创建大量线程。
- **改动范围与代价**：新增统一 `ThreadPoolConfig`（chat / kb / workflow 三个池，命名 + MDC 装饰 + 有界队列 + CallerRuns），替换上述 4 处。**2-3 人日**。
- **风险**：`CallerRunsPolicy` 会让请求线程执行长任务（需评估）；线程池参数需要压测确定，避免过小导致排队。

#### F-14（P2）管理端用户列表内存分页 + N+1

> **2026-11-09 复核：未修复。** `AdminUserController` 仍 `selectList` 全量用户后内存分页，并对每个用户调用 `rolesOf` 查询角色（N+1）。

- **现状**：`rag-controller/.../AdminUserController.java:85-105`：`selectList` 全量加载用户，内存过滤后手工分页，且每个用户单独查一次角色（N+1）。
- **主流做法**：`selectPage` + 关联查询（或角色一次性 `IN` 批量查）。
- **影响**：**性能**（用户上千后列表接口变慢、内存抖动）。
- **改动范围与代价**：改 `selectPage` + 批量角色查询。**0.5-1 人日**。
- **风险**：低；注意与现有搜索条件语义保持一致。

#### F-15（P2）语义缓存重复 embedding，失效扫描全窗口

> **2026-11-09 复核：未修复。** `SemanticCacheService` 仍对 query 单独 `embed`，`RagRetrieveService` 随后再次 `embed`；未把 query 向量透传给缓存查询或检索复用。

- **现状**
  - `rag-kb/.../SemanticCacheService.java:73` 先对 query 做一次 embedding 查缓存，未命中后 `RagRetrieveService.java:135` 再对同一 query 做第二次 embedding —— **每条消息两次 embedding 调用**。
  - `SemanticCacheService.java:176-197` 的 `invalidateByKbId` 先加载全部窗口映射（`selectList`），再逐窗口删除。
- **主流做法**：query 向量在请求内复用；失效按 `kb_id` 元数据批量 `DELETE`。
- **影响**：**性能/成本**（embedding 延迟与费用翻倍；窗口多时失效慢）。
- **改动范围与代价**：`RagRetrieveService` 接受外部传入的 query 向量；失效改为单条元数据批量删除。**1 人日**。
- **风险**：不同模型维度不一致时不能复用向量（现有按模型分组逻辑需沿用）。

#### F-16（P2）知识库文件列表 / 会话列表无分页

> **2026-11-09 复核：部分修复。** 会话列表已加 `LIMIT 50`（`ChatWindowService:270-L276`）；知识库文件列表 `listByKbId` 仍 `selectList` 全量返回，未分页。

- **现状**：`rag-kb/.../KnowledgeFileService.java:236-241` `listByKbId` 直接 `selectList`；`rag-chat/.../ChatWindowService.java:262-265` `listSessions` 同样全量。
- **主流做法**：列表接口默认分页（或明确"仅用于下拉、上限 N"）。
- **影响**：**性能**（文件数上千时首屏加载慢、响应体过大）。
- **改动范围与代价**：文件列表改 `selectPage`，会话列表加 `limit`。**0.5-1 人日**。
- **风险**：前端调用需同步改；分享页会话下拉需保留全量语义时可显式传大 size。

#### F-17（P2）向量批量写入未分批

> **2026-11-09 复核：未修复。** 入库仍是一次 `embedAll` + 一次 `addAll` 提交整份文件的所有 chunk，无分批、无失败重试。

- **现状**：`rag-kb/.../VectorStoreService.java:120-144`：`embedAll(segments)` 后 `store.addAll(...)` 一次性提交全部 chunk。
- **主流做法**：按批（如 100-500 段/批）调用 embedding 与写入，避免单请求超限与长事务式占用。
- **影响**：**性能/稳定性**（超大文件可能触发 provider 请求体限制或超时）。
- **改动范围与代价**：分批循环 + 进度回写。**1 人日**。
- **风险**：分批中途失败会产生部分向量，需要按 `version` 清理（现有"重切清旧向量"机制可复用）。

---

### 3.3 设计模式与可维护性

#### F-18（P1）跨存储一致性：数据库事务内执行向量表 DDL/DML

> **2026-11-09 复核：未修复。** `KnowledgeBaseService.delete` 仍在 `@Transactional` 内 `dropCollection`，`KnowledgeFileService.reSplit/delete` 仍在事务内删除向量；向量操作走独立自动提交连接，业务回滚后向量已删除。

- **现状**：`rag-kb/.../KnowledgeBaseService.java:85-100` 的删除流程在 `@Transactional` 方法内调用 `vectorStoreService.dropCollection(...)`；`KnowledgeFileService.java:129-138`（重切）与 `:168-179`（删除文件）在事务内 `deleteByFileId` 并失效语义缓存。
- **主流做法**：DB 事务只覆盖关系数据；向量库操作放到事务提交后（`TransactionSynchronization.afterCommit`）或走补偿/对账，保证"先关系、后向量"或"先标记待删、异步清理"。
- **差距**：两个存储没有共同事务，却按"同一事务"顺序执行。
- **影响**：**正确性**。若 DB 事务回滚（唯一键冲突、并发更新），向量已被删除而记录仍在 → 知识库静默变空；反之则产生幽灵引用。
- **改动范围与代价**：把向量操作移出事务边界（`afterCommit`）或改为软删除标记 + 异步清理任务。**1-2 人日**。
- **风险**：异步清理存在短暂"已删除但仍可召回"的窗口，需要在检索侧过滤 `status`；补偿任务需幂等（现有 `IF NOT EXISTS` 索引脚本风格可复用）。

#### F-19（P2）前端 SSE 解析逻辑 5 份重复

> **2026-11-09 复核：未修复。** `rag-frontend/src/api/index.js` 仍有 5 处 `getReader()` 流解析实现，未抽出公共 SSE 工厂。

- **现状**：`rag-frontend/src/api/index.js:127-177`、`:207-246`、`:328-368`、`:474-514`、`:636` 附近存在 5 套 `fetch + ReadableStream + event/data` 解析；其中分享链路用 `data += line.substring(5).trim()` 拼接（`trim()` 会丢弃块首尾空白），与主链路 JSON 包装的处理方式不同。
- **主流做法**：一个 `createSseStream({url, body, onEvent, signal})` 工厂，错误/中断/多行 data 统一处理。
- **影响**：**可维护性**。历史经验显示 SSE 修复需要重复改多处，易出现"主链路已修、分享链路未修"的不一致。
- **改动范围与代价**：抽公共函数（不改协议）。**1 人日**。
- **风险**：需逐个页面回归（聊天、分享、工作流、多 Agent、HITL 5 条链路）。

#### F-20（P2）前端死代码与超大组件

> **2026-11-09 复核：部分修复。** `ShareChat.vue` 已不再是死代码，被登录态分享路由 `/share/chat/:token` 使用（`router/index.js`）；但组件体积未降：`ChatPlayground.vue` 1603 行、`ShareChat.vue` 1074 行、`WorkflowEditor.vue` 1025 行、`MultiAgentPlayground.vue` 1023 行。

- **现状**
  - 初版记录：`rag-frontend/src/views/chat/ShareChat.vue` 共 1066 行，当时全仓库无任何 import 引用（路由 `router/index.js:12` 用的是 `PublicShareChat.vue`）。**2026-11-09 复核：该文件已被登录态分享路由 `/share/chat/:token` 引用，不再是死代码。**
  - 超大组件：`ChatPlayground.vue` 1577 行、`MultiAgentPlayground.vue` 1027 行、`WorkflowEditor.vue` 997 行。
- **主流做法**：页面只做编排，逻辑下沉到 composable/store；单文件 300-500 行为宜。
- **影响**：**可维护性**（重复实现与死代码增加改动成本，新人上手困难）。
- **改动范围与代价**：~~删除 `ShareChat.vue`~~（2026-11-09 复核：已不是死代码，删除项作废）；大组件拆分可分期。**2-3 人日（拆分）**。
- **风险**：拆分期间容易引入渲染回归，建议先补关键路径测试再动。

#### F-21（P2）后端超大类与职责混杂

> **2026-11-09 复核：未改善且更大。** `RagChatService` 1569 行、`ChatController` 633 行、`WorkflowService` 672 行、`AgentOrchestrator` 536 行，均未拆分。

- **现状**：`RagChatService.java` 1425 行（会话创建、配额、检索、压缩、SSE、工具循环、故障转移、指标全在一处）；`ChatController.java` 594 行（窗口 CRUD + 工具 CRUD + 技能文件 + 调试）；`WorkflowService.java` 670 行；`AgentOrchestrator.java` 460 行。
- **主流做法**：按用例拆分为 `ChatSessionService` / `RetrievalOrchestrator` / `StreamingResponder` / `ToolLoopService`，Controller 只做参数与协议适配。
- **影响**：**可维护性/可测试性**（任何改动都要回归整条链路；单测难以隔离）。
- **改动范围与代价**：渐进式提取（先提取工具循环与压缩）。**3-5 人日**，收益中等。
- **风险**：大重构期间功能回归风险高，建议在关键路径自动化测试覆盖率提升后再做。

#### F-22（P2）Controller 直连 Mapper / Entity 直出

> **2026-11-09 复核：未修复。** 仍有 10 个 Controller 直接注入 `*Mapper`（共 19 处），绕过 Service 层。

- **现状**：`OpenApiController`、`AdminStatsController`、`PublicShareController`、`AgentController` 等直接注入 `*Mapper` 并做业务拼装；`ChatController`、`LlmModelController` 直接返回实体。
- **主流做法**：Controller → Service → Mapper 单向依赖；对外用 DTO/VO。
- **影响**：**可维护性**（SQL 与协议耦合，测试困难）；**安全**（F-02 的根因）。
- **改动范围与代价**：优先修敏感字段（见 F-02），其余可随重构逐步收敛。**按需**。
- **风险**：全局引入 DTO 层改动面大，不建议一次性铺开。

#### F-23（P2）多 Agent 双引擎重复

> **2026-11-09 复核：未修复。** `CrewCoordinator`（467 行）与 `GraphCoordinator`（423 行）仍并存，各自维护执行循环与事件发送。

- **现状**：`rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/crew/CrewCoordinator.java`（467 行，角色分层/顺序/并行）与 `.../graph/GraphCoordinator.java`（423 行，图驱动 + CONDITION/LOOP）各自实现拓扑调度、入度计数、事件推送与状态机。
- **主流做法**：一个调度内核 + 可插拔的"编排策略"（Crew / Graph 只是不同的图构建器 + 路由策略）。
- **影响**：**可维护性**（同一缺陷要在两处修；事件协议易漂移）。
- **改动范围与代价**：先抽取共享的 `ExecutionGraph` + `NodeScheduler`，再让两套 Coordinator 复用。**3-5 人日**。
- **风险**：两套语义（Crew 的角色上下文 vs Graph 的条件分支）差异大，抽象不当会引入条件判断地狱；建议暂不合并，只统一事件与状态定义。

#### F-24（P2）`@Transactional` 与子线程组合的语义陷阱

> **2026-11-09 复核：未修复。** `streamChat` 仍标注 `@Transactional` 并在方法内 `new Thread` 启动生成，事务无法覆盖子线程、长事务占用连接的问题仍在。

- **现状**：`RagChatService.java:101-119` 的 `streamChat` 标注 `@Transactional`，但 `:173` 立刻起子线程执行主体；事务在方法返回时提交，子线程在此期间可能读不到未提交的用户消息。
- **主流做法**：流式入口不做事务；需要原子的短操作（写用户消息）单独用 `TransactionTemplate`，其余读写走独立事务。
- **影响**：**可维护性/正确性**（当前依赖"子线程只读已提交数据"的巧合；一旦子线程需要读刚写入的消息就会出错）。
- **改动范围与代价**：去掉方法级注解，改为局部事务。**0.5 人日**。
- **风险**：需确认消息写入与标题更新是否需要原子性；若需要，抽到独立小事务方法。

#### F-25（P2）业务异常统一返回 HTTP 200

> **2026-11-09 复核：部分修复。** `GlobalExceptionHandler` 已对 AccessDenied 返回 403、唯一键冲突 409、参数类型错误 400、资源不存在 404、未知异常 500；但 `BusinessException` 与参数校验失败仍返回 HTTP 200 + 业务码，选型本身未变。

- **现状**：`rag-common/.../GlobalExceptionHandler.java:27-33`（`BusinessException` → HTTP 200 + 业务码）、`:35-44`（参数校验同样 200）；`QuotaService.java:91-119` 注释称"超限抛 429"，实际对外的 HTTP 状态是 200。
- **说明**：这是**一致的前后端契约**，不是随手写的 bug；前端已按 `code` 分支处理。
- **影响**：**可观测性**（网关/Nginx/监控无法按 4xx/5xx 区分业务失败与系统故障；限流无法被标准组件识别）。
- **结论**：**列为 P2，且不建议现在改**。若要改，建议在网关或监控侧按业务码聚合，而不是动前端契约；仅对"配额超限/未认证"这类语义明确的场景，在未来大版本里改为 429/401。
- **风险**：改动会波及全部前端错误分支与已有测试断言，收益与风险不匹配。

---

### 3.4 功能模块逐项对照（主流做法 vs 本项目）

| 模块 | 本项目实现 | 与主流对照 | 结论 |
|---|---|---|---|
| 认证 | 本地 HS256（`LocalTokenService.java:89-139`，`iss/aud/tokenType` 校验）与 OIDC 非对称验签（`OidcTokenVerifier.java:27-76`，算法白名单、`iss/aud`）；JWKS 有缓存与刷新限频（`JwksProvider`）；生产强制配置密钥（`LocalTokenService.java:55-75`） | 双轨隔离正确，避免 HS256 算法混淆 | **优于多数同类实现** |
| 授权 | 自定义 `@RequiresPermission` + `AuthConstants` 权限码，AOP 校验；2026-11-09 复核：13 个 Controller 共 104 处注解，管理类加 3 个手工 `requireAdmin`，仅公开/自助类接口不设权限 | 主流为 `@PreAuthorize` + URL 级规则 + deny-by-default | **机制可用，覆盖已基本补齐**（F-03）；前端门控仍不全 |
| 配额 | `QuotaService.java:91-119` 先查后 `upsertIncrement`，`schema.sql:854-888` 有唯一约束 | 原子累加 + 唯一键，标准做法 | **符合主流** |
| 公开访问 | 分享链接（`ShareLinkService.java:119-177` 密码 BCrypt + 失败锁定、`:240-268` 条件更新消费次数）、Widget 嵌入（`EmbedAccessService.java:39-118` Origin/IP 白名单 + 短期 grant）、API Key（SHA-256 存储、明文仅返回一次）、限流（`DefaultPublicAccessStore.java:27-92` Redis Lua + 内存降级） | 覆盖完整，细节到位 | **中上**；Redis 不可用时降级为单机内存，多实例部署会使总额度按实例数放大，需在部署文档中说明 |
| 文件存储 | `LocalFileStorageService` 抽象 + 大小限制 + 临时文件原子移动 + `base-dir` 越界校验（`:138-166`）；Skill 目录写入同样做归一化与 `startsWith` 校验 | 主流为对象存储抽象 + 签名 URL | **符合主流**（单机本地存储，扩展点已留） |
| 文档解析 | 解析器 SPI（`DocumentParserDispatcher`，注册表 + 大小/类型校验，未知类型明确报错） | 主流为 Tika/POI 统一入口 | **符合主流** |
| RAG 检索与重排 | 按 embedding 模型分组、RRF 融合、可选 rerank（失败降级原始序）、关键词混合检索（`word_similarity` 阈值 0.5）、生效 `minScore` 0.3（代码兜底 0.55） | 主流为混合检索 + cross-encoder 重排 | **链路完整**；2026-11-09 复核：HNSW 已默认开启，`ef_search` 未调、重复 embedding 仍在（F-11/F-15） |
| 对话与 SSE | JSON 包装 content 事件、`safelyComplete`、ThreadLocal 工具注册在 `finally` 清理；2026-11-09 复核已补断连取消（`ChatStreamCancellation`） | 主流为 WebFlux/ResponseBodyEmitter + 有界 executor | **功能正确**，线程模型仍未收敛（F-13） |
| Agent 编排 | 计划持久化、`maxSteps` 上限、反思重试、HITL 暂停/恢复/取消、失败重规划（`AgentOrchestrator.java:68-158,239-399`） | 主流为状态机 + 持久化 checkpoint | **功能完整**，线程与越权为短板 |
| 工作流引擎与图校验 | 入度驱动 + 并行 frontier + `CONDITION/LOOP` 有界、`GraphValidator` 校验 START/END 唯一性、环路（排除 LOOP 回边）、并行入度（`:27-170`） | 主流为 DAG 校验 + 拓扑调度 | **中上**，运行期资源与事务边界待改 |
| 可观测性 | MDC requestId、Micrometer 指标（`RagMetrics`）、`SizeAndTimeBased` 日志滚动、actuator 独立端口与健康检查（含 pgvector 扩展校验） | 主流为 Micrometer + tracing | **符合主流** |
| 错误码 | 统一 `Result` 包装 + `GlobalExceptionHandler`（含未映射 `/api/**` → 404/3001）+ 兜底 500 不暴露栈；AccessDenied 403、唯一键 409、类型错误 400 已按 HTTP 语义返回，业务异常仍 200 | 主流按 HTTP 语义分层 | **契约一致但部分偏离 HTTP 语义**（F-25，不建议大改） |
| 审计日志 | `@Audit` + AOP + 异步落库，失败不阻断；2026-11-09 复核：聊天窗口/工具已补，工作流/Agent 仍缺 | 主流为统一操作日志 | **可用，覆盖仍不全**（F-10） |

---

## 4. 已经符合主流、不建议误伤的实现

1. **本地 JWT 与 OIDC 严格隔离**：OIDC 拒绝 HS256、校验 `iss/aud`，JWKS 缓存 300s 且带刷新最小间隔、fail-closed。很多项目在这里栽跟头，本项目是对的。
2. **API Key 只存哈希、明文仅返回一次**，吊销/过期/IP 白名单/scope 齐全。
3. **分享链接的密码锁定与次数消费**使用条件更新（`consumeUse`），没有"先读后写"的竞争窗口。
4. **配额原子 upsert + 唯一约束**，并发下不会超发。
5. **SSE emitter 生命周期**：工作流侧有 `emitters.remove`（完成/超时/异常），聊天侧虽有日志型回调但没有 emitter 集合泄漏问题。
6. **路径穿越防护**：文件存储与 Skill 目录写入都做了归一化 + 前缀校验。
7. **数据库索引覆盖**：消息按 `(session_id, create_time)`、运行记录按 `(workflow_id, create_time)`、分享 token 唯一索引，主要列表查询都有支撑。
8. **降级策略意识**：rerank / query 改写 / 语义缓存 / pgvector 连接失败均有降级路径，且不阻断主流程。
9. **MDC `requestId` 贯穿异步线程**（`MdcContext.wrap`），排障成本显著低于同类项目。
10. **图校验**覆盖 START/END 约束、非法环、并行入度，属于主流水平。

---

## 5. 看起来不规范，但不值得现在动

| 项 | 为什么不改 |
|---|---|
| 业务异常统一 HTTP 200 | 前后端契约已一致，改造波及全部错误分支与测试；收益仅在网关监控可读性，建议网关侧按业务码聚合即可。 |
| 实体直出（非敏感字段） | 一次性引入 DTO 层改动面大、回归成本高。只修 `apiKey` / `shareToken` / `executorConfig` 这类敏感字段即可（F-02）。2026-11-09 复核：`apiKey` 已隐藏，`shareToken` / `executorConfig` 仍待处理。 |
| 静态 `FILE_PROCESSOR` 线程池 | 有界队列 + `CallerRunsPolicy`，行为可控；替换为 Spring 管理只在"优雅停机 + 指标"上有收益，优先级低于 F-13。 |
| 手写 OIDC 过滤器 | 质量高于多数现成方案，无漏洞证据；换成 `spring-boot-starter-oauth2-resource-server` 反而可能丢失现有角色映射逻辑。 |
| 前端组件直接 `v-html` 渲染表格/引用样式 | 只要先做 DOMPurify（F-04），渲染方式本身无需改。2026-11-09 复核：DOMPurify 已接入 `utils/markdown.js`，该项已满足前提。 |
| 多 Agent 两套 Coordinator | 语义差异真实存在（角色上下文 vs 条件分支），强行合并收益不确定，先统一事件协议即可（F-23）。 |
| 聊天窗口全局共享（无 owner 列） | 这可能是产品设计（窗口/知识库是平台资源，由管理员配置），不属于缺陷；只需配合授权（F-03）即可。 |

---

## 6. 按性价比排序的前 10 项

排序依据 = **（风险消除或收益）× 确定性 ÷ 改动成本**。靠前的不一定最严重，而是"改得动、改完立刻见效"。

| 排名 | 项 | 为什么排这里 |
|---|---|---|
| 1 | **F-02 敏感字段直出（`apiKey`）** | P0 凭据泄露，改动 3 处返回值/加注解，几乎零回归风险，收益立竿见影。 |
| 2 | **F-01 会话身份 `default_user` + 归属校验** | P0 数据泄露 + 功能错误；核心逻辑几十行，但需一次数据归属决策（见风险）。 |
| 3 | **F-03 授权覆盖补齐** | P0 越权面最广；机械性补注解，但需先定权限码粒度，是"一次投入长期生效"的基础设施。 |
| 4 | **F-04 Markdown 消毒** | P1 XSS → 账号接管；引入 DOMPurify 半天完成，安全性提升幅度最大。 |
| 5 | **F-05 运行态接口归属校验** | P1 横向越权；校验模式已存在（分享链路），复制到登录链路即可。 |
| 6 | **F-11 pgvector 索引与 store 复用** | P1 性能；配置 + 缓存 + 日志降级，改动集中在一个服务类，数据量增长后收益陡增。 |
| 7 | **F-12 历史消息窗口化** | P1 性能与成本；利用现有索引与已持久化摘要，改动局部但需回归压缩链路。 |
| 8 | **F-13 线程模型收敛** | P1 稳定性；影响并发上限，改动跨 4 处但模式统一，长期收益高。 |
| 9 | **F-06 SSRF 白名单** | P1 安全；改动集中在 HTTP 执行器，需配置化白名单避免误杀。 |
| 10 | **F-07 CORS 收敛 + F-09 Key 写节流** | P2 但成本极低，属于"顺手清理"，可与 1-3 同批提交。 |

> **2026-11-09 复核落地情况**：第 1、2、3、4、5、9、10 名已落地；第 6 名基本落地（HNSW 已开、store 已缓存，`ef_search` 未调）；第 7、8 名（历史窗口化、线程模型收敛）仍未落地。未进前十的 F-18、F-15、F-14 也仍未处理。

---

## 7. 附注

- **初版工作区快照（2026-11-04，历史记录）**：`git status` 显示工作区无已跟踪文件改动，存在 1 个未跟踪文件 `docs/design-data-access-extensibility.md`（非本次产生，未处理、未修改）。
- **初版未执行**：任何 `mvn` / `npm` 命令；未启动/停止 8080 上的进程（PID 41603，历史快照）。
- **2026-11-09 复核边界**：仅静态读取源码、SQL 与前端路由，未执行 `mvn` / `npm`，未启动/停止任何服务，未操作数据库，未修改业务代码。
- **未修改**：除本文件外无任何文件写入。
- **证据局限**：本次为静态审读，未做运行时压测与并发验证；F-11 / F-12 / F-13 的量化影响建议在测试环境用真实数据量复测后再定优先级。

---

## 8. 修订记录

| 版本 | 日期 | 修订人 / 触发原因 | 主要变更点 |
|---|---|---|---|
| v1.0 | 2026-11-04 | TraeCode；首次只读架构审视 | 提出 25 条发现（F-01\~F-25），给出模块评级、Top10 排序与"不建议现在动"清单 |
| v1.1 | 2026-11-09 | TraeCode；2026-11-04 后代码迭代，需确认发现闭环情况 | ① 逐条复核 25 条发现并在每条下增加"2026-11-09 复核"状态；② 已修复：F-01、F-03、F-04、F-05、F-06、F-07、F-08、F-09；部分修复：F-02（`apiKey` 已隐藏）、F-10（工作流/Agent 仍缺审计）、F-16（会话已限 50 条）、F-20（`ShareChat.vue` 已被登录态分享路由使用）、F-25（框架异常已按 HTTP 语义返回）、F-11（HNSW 已开、`ef_search` 未调）；未修复：F-12、F-13、F-14、F-15、F-17、F-18、F-19、F-21、F-22、F-23、F-24；③ 同步更新 §1 结论、§2 模块评级、§3.4 对照表、§5 与 §6 落地情况、§7 核查边界；④ 保留初版历史判定，不删除原始证据 |
