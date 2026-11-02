# 平台化特性设计（阶段 5）

对应 README §7.4 计划项：#12 审计/配额/成本、#13 语义缓存、#15 反馈闭环、#16 多模态、#17 模型路由、#18 Prompt 模板中心、#20 知识库标签/文档版本。

统一约定：布尔列 SMALLINT + BooleanToSmallintTypeHandler；schema.sql 幂等（IF NOT EXISTS）并手动应用到 live DB；逻辑删除 deleted；所有表继承 BaseEntity 六字段。

---

## A. 审计日志 / 配额限流 / 成本统计

### 表
```sql
wgh_audit_log (id, username, action, resource_type, resource_id, detail TEXT,
               ip, status, create_time...)  -- 无 update/deleted 语义，但继承 BaseEntity 保持一致
wgh_model_price (id, provider, model_name, input_price_per_1k DOUBLE, output_price_per_1k DOUBLE,
                 currency VARCHAR(10) DEFAULT 'CNY', ...)
wgh_quota_usage (id, username, date_key VARCHAR(10), chat_count INT, token_used BIGINT,
                 UNIQUE(username, date_key))
```
- 审计：自定义注解 `@Audit(action, resourceType)` + AOP 切面（rag-common），织入 controller 写操作（POST/PUT/DELETE）。异步线程写入，失败不阻断主流程。
- 配额：`wgh.quota.chat-daily-limit`（默认 200 条/人/天）、`wgh.quota.token-daily-limit`（默认 200 万）。聊天发送前检查 wgh_quota_usage，超限返回业务异常 42900？用 PARAM_ERROR 语义的 FAIL + 提示文案。发送后累加。
- 成本：ChatMessage 已有 token_count；新增 `cost DOUBLE` 列（回复后按模型价格表计算回填）。统计接口按天/模型/用户聚合。

### API
- `GET /api/admin/audit-logs?page&size&username&action` 分页查审计
- `GET /api/admin/stats/cost?from&to` → [{date, modelName, tokens, cost}]
- `GET /api/admin/stats/quota` → 当前用户当日用量与上限
- `GET/POST/PUT/DELETE /api/admin/model-prices` 价格表 CRUD

### 关键类
- rag-common: `annotation/Audit.java`、`aspect/AuditAspect.java`
- rag-chat: `ChatMessage.cost` 字段；rag-llm: `ModelPrice` 实体 + `PriceService.estimateCost(model, tokens)`
- rag-controller: `AdminStatsController`、`ModelPriceController`
- rag-common: `quota/QuotaService`（ RagProperties 加 quota 段）

---

## B. 语义缓存

### 表
```sql
wgh_semantic_cache (id, window_id BIGINT, query_text VARCHAR(2000),
                    answer_text TEXT, embedding_id VARCHAR(64), hit_count INT DEFAULT 0, ...)
```
向量存 pgvector 独立表 `wgh_semantic_cache_vec`（复用 VectorStoreService.createEmbeddingStore，dimension 按窗口知识库的 embedding 模型；无知识库窗口不启用缓存）。

### 流程（RagChatService.streamChat 前置）
1. 窗口开启 `semantic_cache_enabled` 且绑定了知识库 → embed 用户问题
2. 在 `wgh_semantic_cache_vec` 中按 window_id 过滤（metadata 过滤）搜 top1，`score >= wgh.cache.similarity-threshold`（默认 0.95）→ 直接 SSE 推送缓存答案（事件带 `cacheHit:true`），跳过检索+LLM
3. 未命中 → 正常流程，完成后把 (query, answer, embedding) 写入缓存
4. 配置：`wgh.cache.enabled`（全局开关默认 true）、`similarity-threshold`、`max-per-window`（默认 500，超出删最旧）

### 关键类
- rag-kb 或 rag-chat: `SemanticCacheService`（embed/search/save/evict）
- ChatWindow 加 `semantic_cache_enabled` 列（SMALLINT）
- SSE：start 事件后若为缓存命中，发 `event:cache` + content + done

---

## C. 反馈闭环

### 表与列
- `wgh_chat_message` 加列：`feedback SMALLINT`（0 无 / 1 赞 / -1 踩）、`feedback_comment VARCHAR(500)`
- 只对 role=ASSISTANT 消息可反馈

### API
- `POST /api/chat/messages/{id}/feedback {feedback: 1|-1|0, comment?}`
- `GET /api/chat/windows/{id}/feedback/stats` → {likes, dislikes, rate}
- 差评查询：`GET /api/admin/feedback/dislikes?page`（运营闭环：可一键把 query 加入 ground truth 待标注清单 —— 前端跳转知识库标注页）

### 前端
- ChatPlayground 助手消息气泡下加 👍/👎 按钮 + 差评可填 comment
- 窗口详情页显示满意度

---

## D. 多模态（图片问答）

范围：仅图片输入问答（语音/TTS 不做）。
- ChatWindow.stream 接口请求体加 `imageUrls: List<String>`（公网 URL 或 base64 data URL）
- 上传：`POST /api/chat/upload` 存 uploads/chat-images/，返回内网可访问 URL 的 data URL（直接转 base64 存储更稳：文件落盘 + 读取转 data URL 发给模型）
- LangChain4j：`UserMessage.from(TextContent, ImageContent)`；仅当窗口模型 `supports_vision=true`（wgh_llm_model 加列）时允许，否则返回提示
- 消息落库：content 存文本，imageUrls 序列化进新列 `attachments TEXT`（JSON 数组）
- 前端：输入框加图片上传按钮，气泡显示缩略图

---

## E. Prompt 模板中心

### 表
```sql
wgh_prompt_template (id, name VARCHAR(100), category VARCHAR(50),
                     content TEXT, variables VARCHAR(500),  -- ["userName","kbName"]
                     version INT DEFAULT 1, builtin SMALLINT DEFAULT 0, ...)
```
- CRUD + 按名称引用：`{{var}}` 占位符渲染 `PromptTemplateRenderer.render(content, map)`
- 聊天窗口 `system_prompt` 支持 `template:模板名` 语法，运行时解析
- 内置 3 个种子模板：RAG 问答 / 客服 / 总结

### API
`/api/prompt-templates` CRUD + `POST /api/prompt-templates/{id}/render {vars}` 预览
### 前端
新页面 views/prompt/，编辑器带变量提示与实时预览。

---

## F. 知识库标签 / 文档版本

### 标签
- `wgh_tag` (id, name UNIQUE, color)
- `wgh_kb_tag_rel` (id, kb_id, tag_id, UNIQUE(kb_id,tag_id))；文件级 `wgh_file_tag_rel`（同构）
- API：`/api/tags` CRUD；`/api/kb/{id}/tags` 绑定/解绑/列出；知识库列表支持 `?tagId=` 过滤
- 前端：知识库卡片显示标签 chip + 管理弹窗

### 文档版本
- `wgh_knowledge_file` 加列 `version INT DEFAULT 1`
- `wgh_knowledge_file_version` (id, file_id, version, chunk_count, split_strategy, status, create_time...) 每次重切/重传生成新版本行，旧版本 chunks 删除前快照元数据（不回滚向量，仅记录历史与统计；回滚=按旧版本记录的策略重切一次）
- API：`GET /api/kb/files/{id}/versions`、`POST /api/kb/files/{id}/rollback/{version}`

---

## G. 模型路由与故障转移

- `wgh_llm_model` 加列：`route_group VARCHAR(50)`、`route_priority INT DEFAULT 0`、`supports_vision SMALLINT DEFAULT 0`（D 复用）
- 聊天窗口 `llm_model_id` 可指向"路由组"：新表 `wgh_model_route_group`？简化——不加表，窗口存 `model_route_group` 字符串列；非空时按 route_group 查 enabled 模型按 priority 升序形成调用链
- `ModelRouter`（rag-llm）：`List<LlmModel> resolveChain(window)`；`ChatModelFactory` 调用处包 `FailoverChatModel`：onError 时切换下一个模型重试一次（非流式直接重试；流式在首个 token 前失败才重试，已输出则降级错误事件）
- 路由策略：priority 顺序 + 失败转移（轮询/随机不做）

---

## 实施顺序与提交粒度
A → B → C → D → E → F → G，每阶段：schema + 实体/Mapper + Service + Controller + 单测 + live DB 应用 + curl 验证 + 独立 commit 推双远端。前端在每个阶段内只做最小可用页面/组件。全部完成后统一更新 README §7.4 状态 + 测试数。

---

## 已落地进度记录（2026-11-02 校准）

### A. 审计日志 / 配额限流 / 成本统计 — ✅ 已完成
- **表**：`wgh_audit_log`、`wgh_model_price`、`wgh_quota_usage`、`wgh_chat_message.cost`/`model_name`
- **后端**：`@Audit` 注解（rag-common）+ `AuditAspect` AOP 异步写入；`QuotaService` 在 `RagChatService` 发送前拦截；`ModelPriceService.estimateCost` 按 provider+modelName 匹配价格表
- **前端**：`AdminStats.vue` 审计查询 / 成本聚合 / 配额用量；`ChatPlayground.vue` 单条消息成本显示
- **API**：`GET /api/admin/audit-logs`、`GET /api/admin/stats/cost`、`GET /api/admin/stats/quota`、`/api/admin/model-prices` CRUD、`GET /api/admin/feedback/dislikes`
- **测试**：`QuotaServiceTest` ✅、`ModelPriceServiceTest` ✅
- **已知限制**：配额超限当前返回业务异常（非 HTTP 429），文案提示"超出每日配额"

### B. 语义缓存 — ✅ 已完成
- **表**：`wgh_semantic_cache` + pgvector 独立表 `wgh_semantic_cache_vec`（metadata.windowId 隔离）
- **后端**：`SemanticCacheService`（embed/search/save/evict），`RagChatService.streamChat` 前置拦截（line 272），`wgh.cache.similarity-threshold`（默认 0.95）、`wgh.cache.max-per-window`（默认 500）
- **前端**：`ChatWindows.vue` 开关 `semanticCacheEnabled`；`ChatPlayground.vue` 消息气泡显示"命中语义缓存"徽标
- **测试**：`SemanticCacheServiceTest` ✅

### C. 反馈闭环与离线评测 — 🔶 部分完成
- **表**：`wgh_chat_message.feedback`（0/1/-1）、`feedback_comment`
- **后端**：`FeedbackService.submitFeedback` + `windowStats` + `dislikePage`；ground truth + Recall@K 接口 + 离线 `eval_recall.py`
- **前端**：`ChatPlayground.vue` 👍/👎 按钮 + 差评 comment + 满意度统计条；`AdminStats.dislikes` 运营闭环
- **未做**：A/B 对比、ground truth 数据集版本管理（本次剩余任务 #3）

### D. 多模态图片问答 — ✅ 已完成
- **表**：`wgh_llm_model.supports_vision`、`wgh_chat_message.attachments`
- **后端**：`ChatImageService`（上传存 `uploads/chat-images/` + data URL 转换），`RagChatService` 支持 `imageUrls` 入参 → LangChain4j `UserMessage(TextContent, ImageContent)`
- **前端**：`ChatPlayground.vue` 图片上传按钮 + pendingImages 预览 + 消息气泡缩略图；`ChatModels.vue` supportsVision 开关
- **测试**：`ChatImageServiceTest` ✅

### E. Prompt 模板中心 — ✅ 已完成
- **表**：`wgh_prompt_template`（name UNIQUE、category、content、variables、version、builtin）
- **后端**：`PromptTemplateService`（CRUD + render + `template:` 前缀解析 + 变量提取）；`RagChatService.buildSystemPrompt` 第二重载注入 `{{kbName}}/{{userName}}/{{windowName}}`
- **前端**：`PromptTemplates.vue` 列表 + 编辑弹窗 + 实时变量检测 + 后端渲染预览；`ChatWindows.vue` systemPrompt placeholder 提示 `template:` 语法
- **API**：`GET /api/prompt-templates/page`、`GET /api/prompt-templates/list`、`GET /api/prompt-templates/{id}`、`POST/PUT/DELETE`、`POST /api/prompt-templates/{id}/render`
- **测试**：`PromptTemplateServiceTest` ✅
- **内置种子**：RAG问答助手、客服专员、内容总结专家（3 条，幂等 INSERT）

### F. 知识库标签 / 文档版本 — 🔶 后端已完成，前端未做
- **表**：`wgh_tag`、`wgh_kb_tag_rel`、`wgh_file_tag_rel`、`wgh_knowledge_file_version`、`wgh_knowledge_file.version`
- **后端**：`TagService`（CRUD + setKbTags/setFileTags + filterKbIdsByTag）；`FileVersionService.list` + `rollback`（按旧版本快照重切）；`KnowledgeFileService.reSplit` 加快照写入
- **Controller**：`TagController`（标签 CRUD + KB 绑定 + 文件版本查询/回滚）
- **API**：`/api/tags` CRUD、`PUT /api/kb/{id}/tags`、`GET /api/kb/{id}/tags`、`GET /api/kb/files/{id}/versions`、`POST /api/kb/files/{id}/rollback/{version}`
- **测试**：❌ 缺 `TagServiceTest`、❌ 缺 `FileVersionServiceTest`
- **未做**：前端 `KnowledgeBase.vue` 标签 chip + 版本历史 Tab（本次剩余任务 #1）

### G. 模型路由与故障转移 — 🔶 后端已完成，前端未做
- **表**：`wgh_llm_model.route_group` / `route_priority`、`wgh_chat_window.model_route_group`
- **后端**：`ModelRouter.resolveChain(routeGroup, llmModelId)` 按 priority ASC 排序形成链路；`RagChatService.runWithFailover` 首 token 前异常 → 切下一个模型重试 → SSE 推 `model_switch` 事件
- **测试**：❌ 缺 `ModelRouterTest`
- **成本回填问题**：`settleCostAndQuota` 用路由链首模型而非实际响应模型的 modelName（本次剩余任务 #4 修复）
- **未做**：前端 `ChatModels.vue` routeGroup/routePriority 字段、`ChatWindows.vue` modelRouteGroup 下拉（本次剩余任务 #2）

### 代码质量已知问题（本次剩余任务 #4）

| 级别 | 问题 | 影响 | 修复方案 |
|------|------|------|---------|
| P0 | G 成本回填 `settleCostAndQuota` 只取路由链首模型，故障转移后 model_name 不准 | 成本统计与真实用量偏差 | `runWithFailover` 返回实际命中的 `LlmModel`，在 settle 中取其 provider+modelName |
| P1 | G `runWithFailover` 首 token 后异常无显式区分 | 已输出内容的错误可能被误重试 | 在 handler 外包裹 `AtomicBoolean firstTokenSent`，首 token 后抛异常只记错误事件不切链 |
| P1 | F `TagService.setKbTags` 全量覆盖（DELETE + BATCH INSERT），前端误传空数组会清空绑定 | 数据丢失 | 改为 INSERT ... ON CONFLICT DO NOTHING + DELETE WHERE tagId NOT IN (newIds) 的 diff 逻辑 |
| P2 | F/G 阶段独立 Service 无单元测试 | 回归风险 | 新增 3 个 Test 类 |

---

## 本次剩余任务设计（按排序）

### 任务 1：#20 知识库标签/版本前端补全

**改动文件**：
- `rag-frontend/src/views/kb/KnowledgeBase.vue`
  - 知识库卡片：增加标签 chip 行（从 `GET /api/kb/{id}/tags` 拉取）
  - 知识库详情弹窗：增加"标签管理"按钮（打开标签多选）
  - 新增"文件版本历史"Tab 或 drawer：`GET /api/kb/files/{id}/versions` 渲染版本列表 + 版本元数据（chunk_count / split_strategy）+ "回滚到此版本"按钮 → `POST /api/kb/files/{id}/rollback/{version}`
  - 文件列表每行增加标签 chip + 右键菜单"文件标签管理"

**配置项**：无新增。

### 任务 2：#17 模型路由前端补全

**改动文件**：
- `rag-frontend/src/views/models/ChatModels.vue`
  - 模型编辑表单增加 `routeGroup`（文本输入）、`routePriority`（数字输入，默认 0）
  - 列表行增加"路由组"列

- `rag-frontend/src/views/chat-admin/ChatWindows.vue`
  - 模型选择旁增加 `modelRouteGroup` 下拉（从已配置 routeGroup 非空的 Chat 模型里聚合 distinct group 名）
  - tooltip 提示："非空时优先走模型路由组，llm_model_id 作为备选"

**配置项**：无新增。

### 任务 3：#15 反馈闭环 — ground truth 数据集版本管理

**最小实现**：给 `wgh_kb_ground_truth` 加 `dataset_version INT DEFAULT 1` 列 + 快照接口。

**表变更**（追加，幂等）：
```sql
ALTER TABLE wgh_kb_ground_truth ADD COLUMN IF NOT EXISTS dataset_version INT DEFAULT 1;
COMMENT ON COLUMN wgh_kb_ground_truth.dataset_version IS '数据集版本号（批量更新时 +1，用于对比不同标注集合的评测结果）';
```

**后端**：
- `RetrievalEvaluationService` 的 `/evaluate` 接口增加可选 `datasetVersion` 参数，不传则取最新版本
- `KnowledgeBaseController` 或新接口：`POST /api/kb/{id}/gt/version-snapshot` → 复制当前所有 gt 记录到新 version
- 评测结果按 dataset_version 聚合对比（`GET /api/kb/{id}/evaluate/compare?v1=1&v2=2`）

**前端**：`KBSearch.vue` 标注/评测 Tab 增加 datasetVersion 版本切换下拉 + "对比两版"按钮。

### 任务 4：代码质量修复

**4a. 成本回填口径修正**
- `RagChatService.runWithFailover` 从 `void` 改为返回 `LlmModel`（实际命中的模型）
- `doRagStreamChat` 在 settleCostAndQuota 时用返回模型的 provider+modelName

**4b. 首 token 后异常区分**
- 在 `streamChatWithTools` 的 `SseEventSource` handler 里加 `AtomicBoolean firstTokenSent = new AtomicBoolean(false)`
- `onNext` 处理 content 事件时 firstTokenSent.compareAndSet(false, true)
- 外层 catch 如果 firstTokenSent.get() → 只推 error 事件不重试

**4c. TagService.setKbTags 改为 diff 合并**
- SQL 改为两条：INSERT ... ON CONFLICT DO NOTHING (newIds) + DELETE ... WHERE tag_id NOT IN (newIds) AND kb_id = ?

### 任务 5：补充单元测试

- `TagServiceTest`：create/update/delete + setKbTags + setFileTags + filterKbIdsByTag + diff 合并语义
- `FileVersionServiceTest`：list + rollback（mock reSplit）
- `ModelRouterTest`：resolveChain 正常 + 空组 + priority 排序 + 全部失败抛异常

---

## 本次落地进度（2026-11-02 续推进）

### 已完成项

**#17 模型路由与故障转移 — 前端补全** ✅（README 已更新为 ✅）
- `ChatModels.vue`：表格新增路由组列 + 表单 routeGroup/routePriority 两个字段 + 默认值
- `ChatWindows.vue`：新增 routeGroups computed（去重聚合）+ 模型路由组下拉 + 默认值
- 后端接口已在之前会话落地（`ModelRouter` + `runWithFailover`）

**#20 知识库标签/文档版本 — 文件级 + 版本历史** ✅（README 已更新为 ✅）
- 后端新增 `TagService.listFileTags(Long fileId)` 方法
- TagController 新增两个端点：
  - `GET  /api/kb/files/{fileId}/tags` — 列出文件已绑定标签
  - `PUT  /api/kb/files/{fileId}/tags` — 设置文件标签（完整覆盖）
- 前端 `api/index.js` 新增 `kbFileTags` / `kbFileSetTags`
- 前端 `KBFiles.vue` 完整重写：
  - 表格新增 **标签列**（显示 el-tag chip，带 tag.color）
  - 表格新增 **版本列**（显示 currentVersion / "v1"）
  - 操作列新增 **标签** 按钮 → 文件标签弹窗（全量标签 checkbox-group）
  - 操作列新增 **版本** 按钮 → 版本历史弹窗（el-table + 回滚按钮，当前版本或非 SUCCESS 禁用回滚）
  - load() 后批量 Promise.allSettled 拉每个文件的 tags
  - 保留原有：预览 / 重切 / 删除 功能不变

### 验证结果

| 环节 | 结果 |
|------|------|
| `mvn compile` | ✅ 0 error |
| `mvn install -DskipTests` | ✅ BUILD SUCCESS |
| `npm run build` | ✅ 37.97s，0 error |
| `mvn test` 全量 | ✅ **136 tests 全绿**（Fail 0, Error 0）|
| Live API 冒烟（5 个端点） | ✅ 全部 200 |
| 后端健康检查 | ✅ UP（db=UP）|

### 变更文件清单（本次会话）

```
# 后端新增
rag-kb/src/main/java/.../service/TagService.java           # +listFileTags 方法
rag-controller/src/main/java/.../controller/TagController.java  # +GET/PUT /api/kb/files/{fileId}/tags

# 前端修改
rag-frontend/src/api/index.js                                 # +kbFileTags / kbFileSetTags
rag-frontend/src/views/kb/KBFiles.vue                        # 完整重写：+标签列 +版本列 +标签弹窗 +版本历史弹窗 +回滚

# 文档
README.md                                                     # #17 ✅ + #20 ✅（落地位置同步更新）
docs/design-phase5-platform-features.md                       # 本次落地记录（本节）
```

### 遗留项

| 项 | 原因 | 优先级 |
|----|------|--------|
| #15 ground truth 数据集版本管理 | schema + 后端 + 前端均未实现 | P1 |
| #15 A/B 对比 | 依赖数据集版本管理 | P2 |
| 单元测试补全（TagServiceTest / FileVersionServiceTest / ModelRouterTest） | 测试覆盖，非阻塞功能 | P2 |

---

## 后续迭代保留（不在本次承诺范围）

| # | 功能 | 简述 |
|---|------|------|
| 5 | 更多文档格式 | HTML / JSON / PPTX 解析器 + 图片 OCR |
| 6 | URL 抓取/连接器 | 网页爬取 + Notion/飞书文档同步 |
| 9 | 工作流 AGENT 节点 | 节点白名单增加 AGENT 类型，复用 Planner/Executor |
| 10 | 免登录分享/Widget/开放 API | API Key 鉴权 + iframe embed + JS SDK |
| 11 | 多用户权限 | 租户 + RBAC + 知识库/文档级权限 |
| 14 | 可观测性与部署 | Prometheus metrics + 链路追踪 + docker-compose |
| 15a | 反馈 A/B 对比 | 同上任务 3，最小实现只做版本管理，A/B 对比留待后续 |
| 19 | 移动端与国际化 | i18n + 响应式/移动端适配 |
