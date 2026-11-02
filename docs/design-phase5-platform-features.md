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
