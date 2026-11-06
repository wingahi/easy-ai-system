# RAG 能力清单对照评估（2026-11-05）

> 范围：`rag-backend` 17 模块中与 RAG 相关的 `rag-kb / rag-llm / rag-chat / rag-controller / rag-config` + `sql/schema`。
> 方式：纯静态只读核查（未执行 mvn/npm，未修改任何业务代码）。
> 判定标准：`已具备` = 主链路真实生效且有配置入口；`部分具备` = 有代码但存在明确断点；`缺失` = 无实现。

---

## 0. 结论先行

1. **基础链路是完整的、可跑通的**：解析（10 种格式）→ 9 种切分策略 → pgvector（每 KB 独立表 + HNSW）→ 向量/关键词双路 + RRF → 可选 Rerank（8s 超时降级）→ LLM 生成 + SSE。设计密度高于多数同规模项目。
2. **但有两个"看起来有、实际基本不生效"的功能**，必须优先处理：
   - `kbName` 元数据**从未写入**，却被 6 处代码读取 → 所有"来源知识库"标注恒为空（[RagChatService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1262) 等）。
   - pg_trgm 关键词路用 `similarity(整段chunk, 短query)` + `WHERE text % ?`，在 512 字 chunk 上数学上几乎**恒不命中**，关键词路实际静默退化为空（[KeywordSearchService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L58-L59)）。
3. **五个难点里，只有"查询改写"算真做了**（REWRITE/MULTI_QUERY/HYDE 三模式齐全且超时降级）；小样本补偿、模糊检索、跨文档聚合、长文本 map-reduce 均无实质实现；更新同步有实现但有事务一致性缺口。
4. **真正的能力短板不在"有没有代码"，而在"入库质量"**：无文本清洗、overlap 在满长 chunk 上被静默丢弃、段落切分会产生碎片块。这三项直接压低召回上限，且修复成本低。

---

## 1. 16 项能力总表

| # | 能力项 | 状态 | 一句话证据 | 缺的是哪一环 |
|---|---|---|---|---|
| 1 | 文档爬取 / URL 导入 | **缺失** | 唯一入库入口是 `upload(MultipartFile)`（[KnowledgeFileService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L74)）；`HtmlParser` 只解析已上传的 .html 文件（[HtmlParser.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/parser/HtmlParser.java#L36)），无 `Jsoup.connect` 抓取 | 无 URL 抓取、无正文抽取（Readability）、无抓取调度/去重/失败重试 |
| 2 | 解析 PDF/Word/Excel/Markdown/HTML | **部分具备** | 10 个解析器由 `DocumentParserDispatcher` 分发（[DocumentParserDispatcher.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/parser/DocumentParserDispatcher.java#L38-L61)；pdf/doc/docx/xls/xlsx/pptx/csv/md/txt/html/json/eml） | 扫描版 PDF 无 OCR（`PDFTextStripper` 取不到字 → 0 chunk 但状态 SUCCESS，[PdfParser.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/parser/PdfParser.java#L38-L49)）；PDF 表格按换行拆散，无结构化还原 |
| 3 | 文本清洗 | **缺失** | 解析产物直接进切分：`parse → splitDetailed → insertVectors`，中间无任何 normalizer（[KnowledgeFileService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L262-L286)） | 无空白折叠、无页眉页脚/水印剔除、无乱码修复、无重复段落去重（HtmlParser 仅做 `contains` 精确去重 [HtmlParser.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/parser/HtmlParser.java#L43)） |
| 4 | 智能分块 | **已具备** | 9 种策略：CHAPTER/PARAGRAPH/LENGTH/RECURSIVE/FAQ/CUSTOM/STRUCTURED/SEMANTIC/AUTO（[TextSplitter.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/splitter/TextSplitter.java#L24-L34)）；AUTO 按 FAQ 密度/章节密度路由（[:115-L127](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/splitter/TextSplitter.java#L115-L127)）；STRUCTURED 写 headingPath（[StructuredChunkingStrategy.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/splitter/StructuredChunkingStrategy.java#L66-L77)）；SEMANTIC 相邻句相似度断块（[SemanticChunkingStrategy.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/splitter/SemanticChunkingStrategy.java#L82-L107)） | 无"父子块/小-大检索"（small-to-big）；STRUCTURED 仅在 Markdown 标题存在时有效 |
| 5 | 重叠策略 | **部分具备** | 全局 `maxLength=512 / overlap=64`（[RagProperties.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java#L55-L56)、[application.yml](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L148-L149)） | overlap 不可按文件/策略覆盖（只有 SEMANTIC 的 threshold 可覆盖）；**满长 chunk 时 overlap 被直接放弃**（[:128-L131](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/splitter/RecursiveChunkingStrategy.java#L128-L131)）；段落切分在边界产生 64 字孤立碎片（[ParagraphChunkingStrategy.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/splitter/ParagraphChunkingStrategy.java#L50-L56)） |
| 6 | 关键词提取 | **缺失** | 无 KeyBERT/TextRank/RAKE 等抽取；`KeywordSearchService` 是"关键词**检索**"而非"关键词**提取**"（[KeywordSearchService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L49-L77)） | 无关键词写入 metadata、无关键词索引，因而无法做关键词召回/标签聚合 |
| 7 | 嵌入（模型/维度/批量/重试/增量） | **部分具备** | 模型按 provider 工厂构建并进程缓存（[ModelCacheManager.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/factory/ModelCacheManager.java#L68-L77)）；维度写入 KB（[KnowledgeBaseService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L60-L63)） | **整文件一次 `embedAll`，无分批、无重试、无增量**（[VectorStoreService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L186-L193)）；50MB 文件可能一次提交数千 chunk，超 provider 批量上限即整文件 FAILED；重切是全删全嵌 |
| 8 | 向量存储（pgvector/索引/collection 隔离） | **已具备** | 每 KB 一张 `wgh_kb_<ts>` 表（[:63](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L63)）；HNSW cosine 索引幂等创建（[VectorStoreService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L202-L221)）；schema.sql 有历史表补建块（[01-schema.sql](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1226-L1253)） | `ef_search` 未设置（用默认 40）；检索期不补建索引（只在建表/入库时）；无表级配额/分区 |
| 9 | 多路召回（向量+关键词+融合） | **部分具备** | 向量路按查询变体分路 + 关键词路，RRF(k=60) 融合（[RagRetrieveService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L132-L193)、[RrfFusion.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RrfFusion.java#L28-L58)） | 关键词路**实际恒空**（见 §2.2）；`enable-hybrid` 默认 false 且 yml 未暴露；无第三路（如标题/metadata 过滤召回） |
| 10 | 重排 | **已具备** | DashScope / 通用 `/rerank` 双协议；8s 超时；候选 ×3（上限 30）；任何异常降级原序（[RerankService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RerankService.java#L36-L73)、[RagRetrieveService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L215-L241)） | 同步 `HttpClient.send` 阻塞首 token 最多 8s；截断策略是"取 topK"，无长度/去重约束；无本地模型选项 |
| 11 | 答案生成（拼装/引用/拒答/幻觉约束） | **部分具备** | 上下文直拼 + 来源行（[:1258-L1274](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1258-L1274)）；system prompt 有 5 条约束（[:1157-L1164](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1157-L1164)）；检索为空才硬拒答（[:458-L477](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L458-L477)） | 引用来源的**知识库名为 null**；无 chunk 级引用编号/回溯；低分命中（min-score=0.3）不拒答；无 grounding 校验，`accuracyRate` 只是 3-gram 覆盖率（[:1330-L1368](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1330-L1368)） |
| 12 | 小样本检索（改写/扩展/HyDE） | **部分具备** | 三模式齐全 + 5s 超时降级（[QueryTransformService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java#L28-L41)） | 默认 `NONE`（[01-schema.sql](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L237)），需逐窗口人工开启；**HYDE 只用假设答案检索，原查询被丢弃**（[:123-L127](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java#L123-L127)）；无"库内文档少"的检测与自动降阈值/扩 topK |
| 13 | 模糊检索（错别字/同义词/口语/缩写） | **部分具备（实为失效）** | 唯一手段是 pg_trgm `similarity(text, query) + WHERE text % ?`（[KeywordSearchService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L58-L59)） | 长文档 vs 短查询相似度数学上被分母稀释（见 §2.2）；**无同义词/缩写词典、无查询纠错、无 word_similarity/tsvector** |
| 14 | 跨文档关联 | **缺失** | 仅 chunk 级 RRF 融合；无实体抽取、无实体链接、无图谱、无多文档摘要（[RrfFusion.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RrfFusion.java#L28-L58)）；同一内容跨 KB 重复时不合并 | 无实体维度聚合能力，同一实体散落多文档时只能靠 topK 碰运气同时命中 |
| 15 | 长文本问答（map-reduce/refine/预算） | **部分具备（只有会话侧）** | 会话历史有分层压缩（[ContextCompressionService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/ContextCompressionService.java)）；但 RAG 上下文**无 token 预算、无 map-reduce**（[RagChatService.java:1258-L1274](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1258-L1274)） | 只取 topK=5 块（≈2500 字），无法回答"全书总结"类问题；超上下文窗时直接截断/报错，无分段归并 |
| 16 | 数据更新同步 | **部分具备** | reSplit 先删旧向量再重切（[KnowledgeFileService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L130-L164)）；删除文件清向量（[:168-L188](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L168-L188)）；版本快照 + 回滚（[FileVersionService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/FileVersionService.java#L34-L52)） | **无内容哈希去重**（同名文件重复上传会重复入库）；**换 embedding 模型不重建表**（[KnowledgeBaseService.update](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L76-L84) 只 update 记录）；向量删除走独立连接**自动提交**，不在 `@Transactional` 内；无孤儿向量巡检任务 |

---

## 2. 五个难点深入诊断

### 2.1 小样本检索：机制存在，但补偿不会自动触发

- **现状机制**：`QueryTransformService` 实现 REWRITE / MULTI_QUERY / HYDE 三条 LLM 改写路径，5s 超时后回退原查询（[QueryTransformService.java:107-L133](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java#L107-L133)）；MULTI_QUERY 会把原查询 + 3 个变体一起进向量路（[:153-L157](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java#L153-L157)）。
- **真实表现**：默认 `query_transform_mode='NONE'`（[01-schema.sql:237](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L237)），线上绝大多数窗口不会走改写；HYDE 分支输出 `vectorQueries=[假设答案]`，**原查询被完全替换**（[:123-L127](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java#L123-L127)），小库更容易跑偏。
- **失效场景**：知识库仅 1~2 篇文档、用户用口语提问（"那个报销怎么走"）→ 纯向量 + min-score=0.3（[application.yml:145](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L145)）取不满 topK 5，`coverageRate` 低，但没有任何自动补救。
- **差距**：缺少"检索质量反馈闭环"——没有任何代码读取 `coverageRate/hitRate` 决定是否二次检索或扩 topK；也没有小库自动切换改写策略。
- **补齐方案**：① 默认对 KB chunk 数 < 500 的库自动启用 `MULTI_QUERY`（HyDE 同时保留原查询，双路 RRF）；② 用 `hitRate < 0.4` 触发"重检索一次（topK×2 + minScore 降 0.1）"；③ 可选接 RAG-Fusion（原查询 + 3 变体 + RRF），现有 RRF 已具备能力，只是未接。
- **改动范围/代价**：`RagRetrieveService.doSearchMultiple` 内加一次重试分支 + `TextSplitter`/KB 元数据查询；约 60~100 行，1 人日。风险：多一次检索会放大延迟（+embedding 1 次 + 向量查询 1 次）。
- **优先级**：P1（成本低，直接改善小库体验）。

### 2.2 模糊检索：pg_trgm 用法在数学上失效

- **现状机制**：`SELECT ... similarity(text, ?) AS score FROM <table> WHERE text % ? ORDER BY score DESC LIMIT ?`（[KeywordSearchService.java:58-L59](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L58-L59)），GIN `gin_trgm_ops` 索引惰性创建（[:83-L95](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L83-L95)）。
- **真实表现（关键）**：`similarity` = 共享 trigram / trigram 并集。一个 512 字 chunk 约有 ~510 个 trigram，10 字 query 约 8 个；即使 query **全部**出现在 chunk 中，`similarity ≈ 8/(510+8-8) ≈ 1.5%`，远低于 `pg_trgm.similarity_threshold` 默认 0.3 → `WHERE text % ?` 直接把所有行过滤掉。**关键词路在正常 chunk 长度下恒返回空**。
- **连带影响**：`hybrid=true` 时 `keywordMatches` 为空 → `keywordParticipates=false` → 静默退化为纯向量（[RagRetrieveService.java:169-L183](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L169-L183)），前端仍显示 `hybridApplied=true`，**指标失真**。
- **差距**：方向用错——pg_trgm 适合"短字段 vs 短查询"（标题、标签、实体名），不适合"长正文 vs 短查询"；同时完全没有中文分词（缩写/口语/同义词全靠 trigram 字符重合，中文同义改写（"报销" vs "费用核销"）零重合）。
- **补齐方案**：① 立刻改为 `word_similarity(?, text) > 0.6`（衡量 query 作为子串在 text 中的最大词匹配度，专为"短 query vs 长文本"设计，PG 11+ 自带，无需新依赖）；② 中期加 `tsvector` + `pg_jieba`/`zhparser` 中文分词列，GIN 全文索引，`ts_rank` 做 BM25 近似；③ 同义词表（业务词典：`报销↔费用核销`、`OA↔办公自动化`）在查询侧做一次扩展；④ 纠错可在 query 侧接 Pinyin/编辑距离，但优先做 ①。
- **改动范围/代价**：① 是**改 1 行 SQL + 1 行建索引表达式**（`gin_trgm_ops` 仍可用，但查询换 `word_similarity`，不依赖索引过滤时需保留 `%>` 操作符）；约 1 小时，风险极低，且立刻让"混合检索"名副其实。② 需要装扩展 + 建生成列/触发器，约 1~2 人日。
- **优先级**：P0（一行改动把"假功能"变成真功能）。

### 2.3 跨文档关联：只有 chunk 级融合，没有实体维度

- **现状机制**：多 KB 检索按 embedding 模型分组，各表独立召回后 RRF 合并（[RagRetrieveService.java:119-L165](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L119-L165)）；去重 key 是 `embeddingId` 或文本（[RrfFusion.java:60-L63](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RrfFusion.java#L60-L63)）。
- **真实表现**：同一实体（如"某产品型号"）散落在 3 份文档的不同章节时，需要这 3 个 chunk **恰好都进入 topK=5** 才能被拼到一起；否则 LLM 只看到局部。且同一内容被两个 KB 收录时，会作为两个不同 embeddingId 同时出现，浪费上下文额度。
- **失效场景**："把这些文档里关于 X 的规则汇总一下" → topK=5 只覆盖 5 个块，跨文档覆盖基本靠运气；`metadata` 只有 `fileId/fileName/kbId/chunkIndex`（[KnowledgeFileService.java:270-L279](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L270-L279)），没有任何实体/主题字段可供聚合。
- **差距**：缺实体抽取（NER）、实体规范化/链接、按实体聚合检索（entity-centric retrieval）、多文档摘要。
- **补齐方案**：① 低成本版：入库时用 LLM 抽取 3~5 个实体/主题写进 metadata，检索时用 `fileId` 聚合 + 同实体扩展 topK；② 中等成本：建 `kb_entity` 关联表（entity → chunkId），查询命中实体后按实体召回兄弟 chunk，再 RRF；③ 高成本：GraphRAG（实体图 + 社区摘要）——**不建议在本项目做**（见 §5）。
- **改动范围/代价**：① 需要入库链路异步加一次 LLM 调用（成本/延迟增加），约 2 人日；② 约 4~5 人日。风险：LLM 抽取不稳定，需缓存与降级。
- **优先级**：P2（当前产品定位更像"单库问答"，跨文档汇总不是主诉求）。

### 2.4 长文本问答：没有 map-reduce，上下文无预算

- **现状机制**：`extractRagContext` 把 matches 顺序拼接，**没有长度上限、没有 token 计数**（[RagChatService.java:1258-L1274](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1258-L1274)）；topK 固定 5（[application.yml:144](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L144)）。
- **真实表现**：5×512 字 ≈ 2500 字，对现代模型完全够用——**所以"超窗"问题不存在**；真正的问题是"覆盖不足"：问"把整份 50 页手册的部署步骤总结一下"，5 个块回答不了，模型只能基于局部作答，且不会拒答（有结果就走生成）。
- **失效场景**：`upload` 允许 50MB 文件（[application.yml:33](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L33)），单文件可能上千 chunk；用户问全局性问题时无任何"全局视图"。
- **差距**：缺 ① 查询意图分类（全局/局部）；② 全局问题的 map-reduce（分块摘要 → 归并）；③ refine 迭代；④ 上下文 token 预算（按模型 `contextWindow` 分配）。
- **补齐方案**：① 先加"预算"：按 `TokenEstimator`（已有，[TokenEstimator.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-llm/src/main/java/com/wgh/rag/llm/util/TokenEstimator.java)）限制 context ≤ 模型窗口的 60%，超限时按分数截断块并标注"内容已截断"；② 全局意图（prompt 判定或关键词"总结/汇总/全部"）→ map-reduce：对 topK×5 块并发摘要（复用 `QueryTransformService` 的线程池模式）→ 再合成；③ refine 作为兜底。
- **改动范围/代价**：① 约 50 行（`extractRagContext` 加预算参数），半天；② 新增 `GlobalQaService`（约 150 行）+ 意图判定，2~3 人日。风险：map-reduce 会让首 token 从 ~1s 涨到 ~5~10s，必须 SSE 进度事件。
- **优先级**：① P1（防止未来长上下文撑爆）；② P2（仅当有"整篇总结"真实诉求）。

### 2.5 数据更新同步：有清理，但事务边界与一致性不成立

- **现状机制**：`reSplit` 在 `@Transactional` 内先 `deleteByFileId` 再异步重切（[KnowledgeFileService.java:130-L164](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L130-L164)）；`delete` 同理（[:168-L188](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L168-L188)）。向量删除用 `dataSource.getConnection()` 独立连接（[VectorStoreService.java:108-L132](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L108-L132)）。
- **真实表现（三个可推断的失效场景）**：
  1. **不可回滚删除**：向量表连接走自动提交，`@Transactional` 回滚时**向量已经删掉**。若 `fileMapper.updateById` 失败导致 reSplit 回滚 → 文件仍是旧 `chunkCount`，但检索不到任何内容（前端显示"成功、N 块"）。
  2. **同文件重复入库**：`upload` 无内容哈希/唯一约束（[KnowledgeFileService.java:74-L113](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L74-L113)、[01-schema.sql:151-L153](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L151-L153) 无 `content_hash` 唯一索引），同一份文件传两次 → 向量两份，召回出现重复块。
  3. **换 embedding 模型不重建**：`KnowledgeBaseService.update` 只写记录（[:76-L84](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L76-L84)），`collectionName` 不变 → 旧向量由旧模型生成，新查询向量来自新模型，**语义空间错配**，召回质量断崖；维度变化时 `addAll` 直接失败。
- **差距**：缺"内容寻址（hash）+ 版本 + 原子切换（影子表/双写）"；向量清理与业务事务不在同一一致性边界；无孤儿巡检。
- **补齐方案**：① 加 `content_hash`，同 hash 同 KB 直接复用/覆盖；② reSplit 改为"新建影子 collection → 全量写入 → 成功后原子改名/切换 `collection_name` → 删除旧表"，彻底避免"先删后建"窗口；③ 换模型时强制重建（update 里检测 `embeddingModelId` 变更 → 触发全库重建任务）；④ 加每日巡检：`metadata->>'fileId'` 与 `wgh_knowledge_file` 做 LEFT JOIN 找孤儿；⑤ 删除/重切改成事务提交后的 `afterCommit` 回调，或改显式事务管理同一连接。
- **改动范围/代价**：① + ③ 约 100 行 + 1 张迁移 SQL；② 是结构性改造（表名切换 + 缓存失效），约 3 人日，风险中（切换期需处理并发检索）。④ 半天。
- **优先级**：③ P0（换模型是常见运维动作，出错隐蔽且后果严重）；② P1；① P1；④ P2。

---

## 3. "假实现"点名（最需要关注）

| # | 现象 | 证据 | 为什么算假实现 | 影响 |
|---|---|---|---|---|
| 1 | `kbName` 元数据从未写入，却被 6 处读取 | 写入只有 `fileId/fileName/kbId/chunkIndex`（[KnowledgeFileService.java:270-L279](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L270-L279)）；读取见 [RagChatService.java:1262](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1262)、[:1321](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1321)、[KnowledgeBaseController.java:170](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/KnowledgeBaseController.java#L170)、[OpenApiController.java:133](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/OpenApiController.java#L133)、[PublicShareController.java:273](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/PublicShareController.java#L273)、[StepExecutor.java:219](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/StepExecutor.java#L219)、[AgentInstanceExecutor.java:369](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/AgentInstanceExecutor.java#L369) | 读方全部按"存在 kbName"写逻辑（`if (kbName != null || fileName != null)` 拼"【来源: kb - file】"），写方从来没写。属于**契约两侧不一致**，不是遗漏 | 所有引用/来源展示的知识库名恒为空；LLM 被告知"标注知识库名称"却拿不到该字段 → 引用约束落空 |
| 2 | pg_trgm 关键词路在正常 chunk 上恒返回空 | [KeywordSearchService.java:58-L59](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L58-L59) + [:73-L76](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L73-L76) 静默 catch 返回 `List.of()` | 配置项、索引、RRF 融合、前端 `hybridApplied` 全都在，但核心 SQL 的相似度语义用错（长文本 vs 短查询），**结果恒空且无任何告警** | "混合检索"是空转；模糊检索（错别字/缩写）实际上只有向量语义兜底；`keywordHitCount` 恒 0 |
| 3 | `minScore` 默认值三处不一致 | `RagProperties.Retrieve.minScore = 0.3`（[:40](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java#L40)）；代码兜底写 0.55（[RagRetrieveService.java:107](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L107)、[RagChatService.java:335](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L335)）；yml 写 0.3（[application.yml:145](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L145)） | 注释说"0.55 因为 0.3 噪声过多"，但实际生效是 0.3。注释与行为相反 | 低分块进入上下文 → 噪声、幻觉概率上升；`hitRate` 指标口径也随之漂移 |
| 4 | `HYDE` 丢弃原查询 | [QueryTransformService.java:123-L127](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java#L123-L127) | 注释写"HYDE 假设性答案"，但 `vectorQueries=[answer]` 替换原 query；MULTI_QUERY 分支却保留原 query（[:153-L157](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java#L153-L157)），两个分支语义自相矛盾 | 开启 HYDE 后小库更容易被"虚构答案"带偏，反而比不改写差 |
| 5 | `enable-hybrid` 配置从未出现在 yml | `RagProperties` 有 `enableHybrid`（[:46](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java#L46)），`application.yml:143-L146` 只配了 top-k/min-score/enable-rerank | 有配置项但无默认值声明 → 依赖代码默认 false，运维无法从 yml 发现该能力 | 混合检索默认关闭，且与 §3.2 叠加 → 该能力实际从未被使用 |
| 6 | `use-index:false` 容易被误判为"无索引" | [application.yml:102-L106](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L102-L106) | `use-index` 是 LangChain4j IVFFlat 开关；HNSW 由 `use-hnsw-index` 独立控制（默认 true，[VectorStoreService.java:202-L221](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L202-L221)） | 快速误判清单："pgvector 无索引"其实不成立；应写成"有 HNSW，但 `ef_search` 未调" |

---

## 4. Top 5 性价比改进项（按性价比排序）

### 1️⃣ 补写 `kbName` 元数据（P0，0.5 小时）
- **改动**：`KnowledgeFileService.processFileAsync` 构造 metadata 时加 `meta.put("kbName", kb.getName())`（1 行），存量数据用一次 SQL 回填（可选）。
- **为什么排第一**：6 处读取方已经在等这个字段，改 1 行立刻让引用/来源真实可用，且零风险。当前"知识库名恒空"会让用户对"引用标注"功能失去信任，属于**最便宜的形象修复**。

### 2️⃣ pg_trgm 关键词路改为 `word_similarity`（P0，1 小时 + 1 次配置验证）
- **改动**：SQL 从 `similarity(text,?)/WHERE text % ?` 改为 `word_similarity(?, text) >= 0.6`（或 `%>` 操作符）；阈值入 `RagProperties`。
- **为什么排第二**：不改就是"永远空"，改了立刻多一条有效召回通道；且 RRF、前端来源标注、`keywordHitCount` 全部已有，属于**只差最后一公里**。改完必须补一个集成测试：断言 512 字 chunk + 10 字 query 能命中。

### 3️⃣ 修复 overlap 丢弃与碎片块（P0，0.5 人日）
- **改动**：`RecursiveChunkingStrategy.mergeWithOverlap` 改为"先按 `maxLength - overlap` 切、再固定拼接 overlap 窗"，保证每块都带重叠且不超长；`ParagraphChunkingStrategy` 在 flush 后若 `tail + 下一段 > maxLength` 则不保留 tail（或单独成块后立即并入）。
- **为什么排第三**：这是**召回质量的根因之一**——切在语义边界却丢重叠，等于多花了 64 字预算却没买到连续性；碎片块还会污染 topK。修复只动两个类，可单测验证。

### 4️⃣ 换 embedding 模型强制重建 + 内容哈希去重（P1，1 人日）
- **改动**：`KnowledgeBaseService.update` 检测 `embeddingModelId` 变化 → 置 `status=REBUILDING` 并投递全库重建任务；`wgh_knowledge_file` 加 `content_hash`，`upload` 同 KB 同 hash 走"覆盖/跳过"。
- **为什么排第四**：两者都是**低频但后果严重**的隐蔽问题（语义空间错配后召回"看起来正常但答案错"，排查成本极高）；改动集中在一个 Service + 一条迁移 SQL。

### 5️⃣ RAG 上下文 token 预算（P1，0.5 人日）
- **改动**：`extractRagContext` 增加 `maxTokens` 参数（用已有 `TokenEstimator`），按分数顺序装箱，超限截断并在 prompt 里注明"内容已按额度截断"。
- **为什么排第五**：现在 topK=5 不会超窗，但随着 `topK` 调大、`map-reduce` 或长 chunk 策略上线，这是**必须先有的护栏**；改动 30~50 行，防止未来一次线上事故。

> 未进 Top5 但同等重要的：文本清洗（P1）。它没有排进来是因为工作量随清洗规则膨胀（页眉页脚识别需要版面分析），而前 5 项都是"改一处、收益立刻可见"。

---

## 5. 明确"别做"的部分

| 看起来缺 | 为什么不做 |
|---|---|
| **引入独立向量库（Qdrant/Milvus/Weaviate）** | 当前定位是"单 PG + pgvector"，KB 数量与向量量级远未到 pgvector 的瓶颈；HNSW 已就绪（[VectorStoreService.java:202](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L202)）。换库会带来双写、运维、备份一致性成本，收益不成立。先把 `ef_search` 调参（默认 40 → 按 topK 设 64/100）即可。 |
| **GraphRAG / 知识图谱 / 社区摘要** | §2.3 的 ③。需要 NER + 实体消歧 + 图存储 + 社区检测 + 摘要生成，成本以"人月"计；当前产品是知识库问答，不是"企业知识图谱"。用 metadata 实体字段 + 兄弟 chunk 扩展（§2.3 ①/②）就能拿到 80% 收益。 |
| **本地 rerank 模型（bge-reranker / Cohere 自托管）** | 已有 DashScope + 通用 `/rerank` 双协议，8s 超时降级（[RerankService.java:36](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RerankService.java#L36)）。自托管增加 GPU 依赖与部署复杂度，除非有"数据不出域"硬约束，否则不值得。 |
| **独立全文检索引擎（Elasticsearch / OpenSearch）** | PG 的 `tsvector + GIN`（配 `pg_jieba`）足以支撑当前量级；引入 ES 等于再养一套存储、同步链路和运维。只有当日志/检索 QPS 达到 PG 明显吃力时才考虑。 |
| **完整网页爬虫调度系统（深度爬取/增量爬取/去重队列）** | 清单第 1 项"网页抓取"容易扩展成 CMS 级工程。按当前定位，补一个"URL → 单页正文导入（Readability + Jsoup）"即可（约 1 人日）；爬虫调度、robots、增量抓取不做。 |
| **关键词提取（KeyBERT/TextRank）用于"展示"** | 若只是给文档打标签展示，对检索无增益；若要做关键词召回，应走 §2.2 的 tsvector + 词典，而不是把 LLM/统计关键词塞进 metadata。除非有"按关键词筛选文档"的明确产品需求，否则不做。 |
| **父子块（small-to-big / parent-document retriever）** | 需要额外存父块表并按子块回查父块，改动入库与检索两处；当前 chunk 512 已接近合适粒度。等 §4.3 的 overlap 修好、评估确认召回不足后再做。 |
| **多路召回加"标题路/metadata 过滤路"** | 现有 2 路 + RRF 已足够；再加路会让调参与评测复杂度上升。等 §2.2 的模糊检索真正生效、有评测数据支撑后再评估。 |
| **语义缓存做成分布式（Redis 向量）** | 现有 pgvector 独立表 + 窗口过滤 + TTL + 惰性清理（[SemanticCacheService.java](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/SemanticCacheService.java#L205-L247)）已可自洽；且默认关闭。没必要为它引入新组件。 |

---

## 6. 核查边界与后续

- **本次为纯静态只读**：未执行 `mvn`/`npm`，未改动任何业务代码。唯一新增文件即本报告。
- **未验证项（需运行期证据）**：① pg_trgm 恒空结论基于 `similarity` 定义推导，建议用一条 SQL 在现网验证（`SELECT similarity(<512字chunk>, <10字query>)`，预期 < 0.05）；② HNSW 是否真的建在某张历史表上，可用 `\d+ wgh_kb_*` 查；③ reSplit 事务竞态需要用并发/故障注入复现。
- **与上一份报告的衔接**：[architecture-review-2026-11-04.md](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/docs/architecture-review-2026-11-04.md) 的 P0 项（`default_user` 会话身份、apiKey 直出、授权覆盖、前端 XSS）与本次 RAG 能力评估不重叠，仍应优先处理；本次新增的 P0 是 §3.1（kbName）与 §3.2（关键词路失效）。
- **"以上提到问题全部修复"的请求**：本轮任务明确为只读评估，未执行任何修复；如需按本报告 Top5 落地，请确认后另起执行轮次。
</content>
