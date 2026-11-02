# RAG 检索增强设计文档

> 版本：v1.0 ｜ 日期：2026-11-02 ｜ 状态：待评审
> 范围：Rerank 重排序、混合检索（向量+关键词）、查询改写 / Multi-Query / HyDE、语义切分 / 结构化切分

## 1. 背景与目标

当前检索链路（`RagRetrieveService.searchMultiple`）为纯向量单路召回：按 embeddingModelId 分组 → pgvector 相似度检索（minScore=0.55）→ 全局 topK。存在四类典型短板：

| 短板 | 现状问题 | 增强手段 |
|---|---|---|
| 向量分数排序粗糙 | 余弦相似度与真实相关性不对齐，topK 内噪声多 | Rerank 重排序 |
| 专有名词/编号召回差 | 向量对精确词（型号、法规编号、人名）不敏感 | 混合检索（向量+关键词） |
| 用户口语化/模糊查询 | 查询与文档表述差异导致召回失败 | 查询改写 / Multi-Query / HyDE |
| 切分破坏语义边界 | 固定长度/段落切分切断表格、跨标题语义块 | 语义切分 / 结构化切分 |

设计原则：

- **分阶段低成本落地**：每阶段独立可用、独立开关，默认关闭不影响现有行为
- **窗口级配置优先**：与工具/Agent 配置惯例一致，聊天窗口可独立开关各增强能力
- **复用现有基建**：DataSource/HikariCP 直连 PG、模型管理统一管 RERANK 模型、SSE 指标推送复用现有协议
- **降级不阻断**：rerank/改写 LLM 调用失败一律降级为原始向量检索结果，不阻塞聊天主流程

## 2. 现状关键事实（代码勘察结论）

- 检索主链路：[RagRetrieveService.java](../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java) `searchMultiple`（分组向量检索）→ [RagChatService.java](../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java) L211-L240（计算 recallRate/hitRate 并 SSE 推送）
- 配置：[RagProperties.java](../rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java) 已有 `topK/minScore/enableRerank` 及切分 `maxLength/overlapLength/defaultStrategy`
- 切分：[TextSplitter.java](../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/splitter/TextSplitter.java) 策略枚举 `AUTO/RECURSIVE/CHAPTER/PARAGRAPH/FAQ/LENGTH/CUSTOM`，段落滑窗+长度回退已实现
- pgvector：`VectorStoreService.createEmbeddingStore(tableName, dimension)` 基于 `PgVectorEmbeddingStore.datasourceBuilder`，每知识库一张表（collectionName），text 列存 chunk 原文
- LangChain4j 1.19.0 core 自带：`ScoringModel`、`ReRankingContentAggregator`、`ExpandingQueryTransformer`、`CompressingQueryTransformer`、`DefaultQueryTransformer`
- langchain4j-community-dashscope **无** rerank/scoring 实现 → DashScope rerank 需 HTTP 直连
- 模型管理：`wgh_llm_model.model_type` 当前为 CHAT/EMBEDDING（字符串），可扩展 RERANK；provider 为字符串（DEEPSEEK/DASHSCOPE/CUSTOM_OPENAI）
- 知识库文件：`wgh_kb_file.split_strategy`（枚举名）+ `split_config`（JSON）已支持按文件独立配置

## 3. 总体架构

```
用户查询
   │
   ▼
[阶段3] 查询变换（可选）：REWRITE / MULTI_QUERY / HYDE  ──失败降级原查询
   │  （LLM 调用，超时 5s，产出 1~N 个查询变体）
   ▼
[阶段2] 多路召回（每个查询变体）：
   ├─ 向量路：pgvector cosine，minScore 过滤，取 vectorTopN
   └─ 关键词路：pg_trgm similarity，取 keywordTopN（hybridEnabled 时）
   │
   ▼
[阶段2] RRF 融合（k=60）跨路跨变体合并 → candidateTopN（默认 topK*3，上限 30）
   │
   ▼
[阶段1] Rerank 重排序（可选）：候选 → RERANK 模型 → 按 rerank 分数取 topK ──失败降级 RRF 序
   │
   ▼
输出 + 指标（recallRate/hitRate/rerank 耗时/变换前后查询）SSE 推送
```

配置分层（优先级：窗口级 > 全局默认）：

| 配置 | 窗口级字段（wgh_chat_window 新增） | 全局默认（application.yml: rag.*） |
|---|---|---|
| Rerank 开关 | `rerank_enabled` | `rag.rerank.enabled`（false） |
| Rerank 模型 | `rerank_model_id` → wgh_llm_model(RERANK) | `rag.rerank.model-id` |
| 混合检索开关 | `hybrid_enabled` | `rag.hybrid.enabled`（false） |
| 查询变换模式 | `query_transform_mode`（NONE/REWRITE/MULTI_QUERY/HYDE） | `rag.query-transform.mode`（NONE） |

## 4. 阶段 1：Rerank 重排序

### 4.1 方案选型

- **模型管理扩展**：`model_type` 增加 `RERANK`。provider 取值：
  - `DASHSCOPE`：DashScope text-rerank API（`POST {baseUrl}/api/v1/services/rerank/text-rerank/text-rerank`，模型 gte-rerank-v2，header `Authorization: Bearer {apiKey}`）
  - `CUSTOM_RERANK`：通用 `/rerank` 端点（SiliconFlow / Jina 兼容格式：`POST {baseUrl}/rerank`，body `{model, query, documents[], top_n}`）
- **服务层**：新增 `RerankService`（rag-kb）实现 LangChain4j `ScoringModel` 接口（`scoreAll(List<TextSegment>, String)`），内部按 provider 分发 HTTP 调用（JdkHttpClient，超时 8s）；分数归一化到 [0,1]（DashScope 返回 relevance_score 已在 [0,1]，Jina 同）
- 不引入本地 cross-encoder（无 GPU、部署重，违背低成本原则）

### 4.2 检索流程改动

`RagRetrieveService.searchMultiple` 增加重载 `searchMultiple(kbIds, query, RetrievalOptions)`：

1. 向量候选数放大：`candidateTopN = min(topK * 3, 30)`
2. rerankEnabled 且有可用 RERANK 模型 → 调 `RerankService.scoreAll` → 按新分数取 topK
3. 异常/超时/模型未配置 → 记 WARN 日志，返回原向量序（降级）
4. SSE 指标事件增加字段：`rerankApplied`、`rerankLatencyMs`

### 4.3 前端

- 模型管理：模型类型下拉增加「RERANK 重排序模型」；provider 增加 CUSTOM_RERANK
- 聊天窗口创建/编辑：新增「检索增强」配置区：Rerank 开关 + Rerank 模型下拉（数据源 = 启用的 RERANK 模型）

### 4.4 验收标准

- 配置 DashScope gte-rerank-v2 后，同一查询 rerank 前后 top5 顺序可由接口对比验证（调试搜索接口返回 rerankApplied=true）
- 关闭开关 / 模型 API 401 时，聊天与搜索接口行为与现状一致（降级）
- 单测：RerankService 两种 provider 报文构造、降级路径；RagRetrieveService rerank 集成路径

## 5. 阶段 2：混合检索（向量 + 关键词）

### 5.1 关键词侧选型

- **pg_trgm**：`CREATE EXTENSION IF NOT EXISTS pg_trgm`（postgres-with-vector 镜像自带），对每个 pgvector 表的 text 列建 GIN 索引，关键词 SQL：`SELECT metadata, similarity(text, :q) AS score FROM {table} WHERE text % :q ORDER BY score DESC LIMIT :n`
- 选 pg_trgm 而非 zhparser tsvector：零依赖、幂等可执行、对中文子串/编号/英文混排有实际效果；zhparser 需容器内装扩展，列为后续迭代项
- chunk 原文就在 pgvector 表 text 列，无需新表；索引创建纳入 `VectorStoreService.ensureCollection`（幂等）

### 5.2 融合算法

RRF（Reciprocal Rank Fusion，k=60）：

```
rrfScore(d) = Σ_路 1 / (60 + rank_路(d))
```

- 向量路 topN = candidateTopN，关键词路 topN = `rag.hybrid.keyword-top-n`（默认 10）
- 融合后按 rrfScore 排序取 candidateTopN 进入 rerank（若开启）或直接取 topK
- 关键词路为空（无匹配）时自动退化为纯向量路，行为同现状

### 5.3 改动点

- `RagRetrieveService`：hybridEnabled 时对每组 embedding 表追加关键词查询（JdbcTemplate，复用 pgvector DataSource）
- 关键词结果与向量结果统一为内部 `ScoredChunk(text, metadata, score, source=VECTOR|KEYWORD)`，metadata 解析复用现有逻辑
- 指标：SSE 增加 `hybridApplied`、`keywordHitCount`
- 调试搜索接口返回每条结果增加 `source` 字段（前端可标注来源）

### 5.4 验收标准

- 含精确编号/型号语料的库：纯向量未召回的查询，开混合后能召回（调试接口可见 source=KEYWORD）
- 关键词无匹配时结果与纯向量一致；pg_trgm 索引重复执行 ensureCollection 无 ERROR
- 单测：RRF 融合分数与排序、关键词 SQL 参数化注入安全

## 6. 阶段 3：查询改写 / Multi-Query / HyDE

### 6.1 模式定义（窗口级 `query_transform_mode`）

| 模式 | 实现 | LLM 调用次数 |
|---|---|---|
| NONE | 不变换（默认） | 0 |
| REWRITE | 改写为 1 个规范检索查询（优先复用 LangChain4j `ExpandingQueryTransformer`，prompt 中文模板自管理） | 1 |
| MULTI_QUERY | 自实现：一次调用生成 3 个语义变体（JSON 数组返回，解析失败降级原查询），每变体独立召回后 RRF 合并 | 1 |
| HYDE | 自实现：生成 150 字内假设性答案，用假设答案的 embedding 替代查询 embedding 走向量路（关键词路仍用原查询） | 1 |

- 执行模型：默认复用窗口当前聊天模型，可选 `rewriteModelId` 覆盖（复用阶段 1 的模型选择组件，类型 CHAT）
- 超时 5s / 异常 / 空输出 → 降级原查询，记 WARN
- 指标：SSE 增加 `transformedQueries`（数组）、`transformLatencyMs`

### 6.2 验收标准

- MULTI_QUERY 下口语化查询（"那个东西怎么弄"类）召回率较 NONE 提升（用现有评估链路 Recall@k 对比）
- 三种模式分别可开关且互斥；LLM 401/超时全部降级不报错
- 单测：变体解析容错、HYDE embedding 替换路径、降级路径

## 7. 阶段 4：语义切分 / 结构化切分

### 7.1 新增策略（TextSplitter 枚举扩展）

| 策略 | 说明 |
|---|---|
| `STRUCTURED` | Markdown 结构化切分：按标题层级（`#`~`####`）聚合成块，标题路径（如 "第3章/3.2节"）写入 chunk metadata；表格（连续 `|` 行）整块保留不拆，超 maxLength 才回退长度切分 |
| `SEMANTIC` | 语义切分：按句切分 → 相邻句 embedding 余弦相似度 < 阈值（默认 0.5，splitConfig 可调）处断块 → 块内滑窗合并至 maxLength；使用知识库绑定的 embedding 模型，切分成本 = 句数次 embedding 调用 |

- 两策略均实现现有 `ChunkingStrategy` 接口，复用：异步线程池、落库、旧向量清理、重切链路（零改动接入）
- `SEMANTIC` 批量 embedding 走现有 `EmbeddingModelFactory`（已有 batch embed 能力）；embedding 失败降级为 PARAGRAPH
- metadata 新增：`headingPath`（STRUCTURED）、`splitStrategy`（已有）、`semanticBlockIndex`（SEMANTIC）

### 7.2 前端

- 知识库文件切分策略下拉增加「结构化切分（Markdown）」「语义切分」；选 SEMANTIC 时提示切分耗时与 embedding 消耗
- 搜索结果/引用面板展示 headingPath（如有）

### 7.3 验收标准

- Markdown 测试文档：STRUCTURED 切分后同标题下内容不被标题边界切断，表格完整保留在单 chunk
- SEMANTIC 对话题转折明显的文档，断块位置与人工预期一致（抽样 3 篇）
- 重新分割/删除文件后 pgvector 无残留（遵循既有约束）；embedding 失败自动降级 PARAGRAPH

## 8. 数据库变更（幂等，纳入 schema.sql）

```sql
-- wgh_chat_window 新增（均 IF NOT EXISTS，带注释）
ALTER TABLE wgh_chat_window ADD COLUMN IF NOT EXISTS rerank_enabled SMALLINT DEFAULT 0;
ALTER TABLE wgh_chat_window ADD COLUMN IF NOT EXISTS rerank_model_id BIGINT;
ALTER TABLE wgh_chat_window ADD COLUMN IF NOT EXISTS hybrid_enabled SMALLINT DEFAULT 0;
ALTER TABLE wgh_chat_window ADD COLUMN IF NOT EXISTS query_transform_mode VARCHAR(20) DEFAULT 'NONE';
-- wgh_llm_model 注释更新：model_type 支持 CHAT / EMBEDDING / RERANK
```

## 9. 阶段计划与依赖

| 阶段 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| 1 | Rerank | DashScope key 有效（当前欠费需充值）或提供 CUSTOM_RERANK 端点 | 低 |
| 2 | 混合检索 | pg_trgm 扩展可用 | 低 |
| 3 | 查询变换 | 阶段 2 的多路融合（MULTI_QUERY 复用 RRF） | 中（prompt 调优） |
| 4 | 切分增强 | 无 | 中（SEMANTIC 成本） |

每阶段交付：后端实现 + 单测 + 前端配置 + curl/页面验证 + 提交推送。顺序执行，阶段内验证通过再进入下一阶段。

## 10. 非目标（本期不做）

- zhparser 中文全文检索、本地 cross-encoder 部署
- 检索增强配置的评估指标自动化对比报表（复用现有评估链路手工对比即可）
- HyDE 多假设答案、Multi-Query 变体数动态调节
