# 独立向量数据库 / 搜索引擎规划

> **版本**：v1.0
> **日期**：2026-11-19
> **性质**：规划文档（新增块）。不修改业务代码、不改配置、不执行 DDL、不迁移数据、不重启服务、不做 git 操作。
> **定位**：评估「PostgreSQL + pgvector」之外的独立向量库与搜索引擎选型，给出目标架构、分阶段路径（V0–V3）、改造点、风险与验收。
> **与既有设计的关系**：不推翻 [design-rag-retrieval-enhancement.md](../rag-retrieval/design-rag-retrieval-enhancement.md) 的检索链路结论；不推翻多租户「共享表 + `tenant_id`，向量经 KB 路由」的选型（[multi-tenancy-analysis-2026-11-18.md](../../../review/multi-tenancy-analysis-2026-11-18.md) §2.4）；调度侧的重建/回填任务复用 [design-execution-scheduler-center.md](design-execution-scheduler-center.md) 的 `KB_REBUILD` / `VECTOR_BACKFILL`。
> **分析方式**：源码逐文件核对 + 配置读取；未做压测、未做容量实验。
> **未验证/推断项**：见 §9

---

## 0. 结论摘要

1. **现状不是「一个向量库」，而是「每个知识库一张 pgvector 物理表」**：建库即建表（`wgh_kb_<ts>`），重建再建新表（`wgh_kb_<id>_<ts>`），表名存 `wgh_knowledge_base.collection_name`（[KnowledgeBaseService.java#L77](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L77)、[KnowledgeBaseService.java#L142-L170](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L142-L170)）。
2. **chunk 原文只存在于向量表里**：没有独立的 chunk 表，文本与 metadata 都在 pgvector 表的 `text` / `metadata` 列（[KeywordSearchService.java#L23-L27](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L23-L27)）。**这决定了任何后端替换都不能只迁向量，必须同时解决原文与元数据的唯一副本问题**——这是本块最重要的前置结论。
3. **检索是「逐知识库串行 fan-out」**：`searchMultiple` 对每个 KB 单独查一次向量表，再做 RRF 融合与可选 Rerank（[RagRetrieveService.java#L99-L122](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L99-L122)、[RagRetrieveService.java#L168-L212](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L168-L212)）。跨 10 个 KB 就是 10 次 SQL + 10 次 HNSW 查询，耗时随 KB 数线性增长。
4. **关键词路是 pg_trgm 而非 BM25**：`word_similarity(query, text) >= 0.5` + 每表一个 GIN 索引（[KeywordSearchService.java#L47](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L47)、[#L66-L118](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L66-L118)）。没有中文分词、没有字段过滤、没有高亮、没有 BM25 相关性。
5. **抽象层已经存在但不完整**：`VectorStorePort` / `KeywordSearchPort` 已把业务与 PG 方言隔离；但 `createEmbeddingStore` 直接返回 LangChain4j 的 `EmbeddingStore`，`deleteByFileId` 依赖 `metadata->>'fileId'` 的 PG JSON 语法，换后端必须扩接口（[VectorStorePort.java#L16-L60](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/port/VectorStorePort.java#L16-L60)、[VectorStoreService.java#L105-L133](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L105-L133)）。
6. **推荐路线：先补齐「可替换性」，再按收益顺序替换**——
   - **V0**：端口补全 + chunk 原文独立化 + 检索质量/延迟基线 + 规模阈值定义（不引入新组件）；
   - **V1**：先接独立搜索引擎（关键词/过滤/高亮），向量仍留 pgvector（风险最低、收益最快）；
   - **V2**：达到阈值后再接独立向量库（双写 + 蓝绿 + 可回滚）；
   - **V3**：统一检索编排与多后端治理（缓存、配额、降级）。
7. **明确不建议**：
   - 为了「架构先进」一开始就把向量与关键词一起换掉（同时引入两个新故障域）；
   - 在 V0 未完成前直接替换后端（没有原文副本、没有基线、没有回滚手段）；
   - 依赖搜索引擎/向量库自身的多租户 ACL 作为唯一隔离手段（租户过滤必须留在应用侧与端口层）。
8. **触发阈值（建议值，需压测校准）**：单表向量行数、KB 数量、检索 P95、HNSW 索引内存、重建耗时、跨 KB fan-out 次数。达到任意两项再启动 V2，写入 §7 验收。

---

## 1. 现状评估

### 1.1 拓扑

```
应用（单 JVM）
  └── RagRetrieveService（检索编排：多查询变体 + 关键词路 + RRF + Rerank）
        ├── VectorStorePort ──▶ VectorStoreService ──▶ PgVectorEmbeddingStore（每 KB 一张表）
        └── KeywordSearchPort ─▶ KeywordSearchService ─▶ pg_trgm（每表一个 GIN 索引）

PostgreSQL（单实例，pgvector + pg_trgm）
  ├── wgh_kb_<ts> / wgh_kb_<id>_<ts>   每知识库一张向量表（text + embedding + metadata）
  ├── wgh_semantic_cache_vec           语义缓存共享向量表（metadata.windowId）
  └── wgh_agent_memory_vec             Agent 记忆共享向量表（metadata.scopeKey）
```

### 1.2 关键实现事实

| 项 | 现状 | 证据 |
|---|---|---|
| 扩展 | `vector` + `pg_trgm`，schema 初始化时创建 | [01-schema.sql#L8-L10](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L8-L10) |
| 集合命名 | 新建 `wgh_kb_<currentTimeMillis>`；重建 `wgh_kb_<kbId>_<ts>` | [KnowledgeBaseService.java#L77](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L77)、[#L158-L160](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L158-L160) |
| 集合指针 | `collection_name` 唯一；`pending_collection` 记录重建目标 | [01-schema.sql#L101-L110](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L101-L110) |
| 维度 | KB 维度存 `dimension`，随 embedding 模型；默认 1536 | [01-schema.sql#L99-L100](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L99-L100)、[application.yml#L96-L109](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L96-L109) |
| ANN 索引 | 每表 HNSW（`vector_cosine_ops`，m=16，ef_construction=64），惰性幂等创建 | [VectorStoreService.java#L269-L290](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L269-L290) |
| 句柄缓存 | 进程内 LRU（表名 + 维度），默认 64 | [VectorStoreService.java#L36-L56](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L36-L56)、[application.yml#L105](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L105) |
| 关键词 | `word_similarity >= 0.5`，GIN（`gin_trgm_ops`）惰性创建 | [KeywordSearchService.java#L47](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L47)、[#L106-L118](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L106-L118) |
| 融合 | RRF，k=60 | [RrfFusion.java#L13-L19](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RrfFusion.java#L13-L19) |
| Rerank | 候选放大 `topK*3`，上限 30；失败降级为原序 | [RagRetrieveService.java#L39-L40](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L39-L40)、[#L269-L292](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java#L269-L292) |
| 共享向量表 | 语义缓存 `wgh_semantic_cache_vec`；记忆 `wgh_agent_memory_vec` | [SemanticCacheService.java#L32-L42](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/SemanticCacheService.java#L32-L42) |
| chunk 原文 | 无独立表，存于向量表 `text` 列 | [KeywordSearchService.java#L23-L27](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L23-L27) |

### 1.3 能力与瓶颈矩阵

| 维度 | 现状能力 | 瓶颈 / 风险 | 影响面 |
|---|---|---|---|
| 向量检索 | HNSW 余弦，每表独立 | 表数量 = 知识库数量；每表一份 HNSW 内存与构建开销 | 大规模 KB 时内存与 catalog 压力 |
| 跨 KB 检索 | 逐 KB 串行查询后融合 | N 个 KB = N 次 SQL + N 次 ANN；延迟线性增长 | 多库联合问答 |
| 关键词检索 | pg_trgm 字符三元组 | 无 BM25、无中文分词、无字段/时间过滤、无高亮 | 关键词召回质量与可解释性 |
| 过滤 | metadata JSONB（无独立 GIN） | 过滤与 ANN 组合时只能先 ANN 后过滤，选择性差时退化 | 按文件/标签/时间过滤 |
| 写入 | 解析 → 切分 → embedding → 批量插入 | 大库重建分钟到小时级；重建期间双表并存 | 重建窗口与存储放大 |
| 删除 | 按 `metadata->>'fileId'` 删除 | PG JSON 语法绑定，换后端需改实现 | 迁移成本 |
| 备份 | pg_dump 覆盖向量与原文 | 单库体量大时备份/恢复窗口长 | 运维 |
| 扩缩容 | 单 PostgreSQL 实例 | 无独立扩缩容；只读副本不解决 HNSW 内存 | 峰值容量 |
| 多租户 | 无 `tenant_id` 列 | 依赖 KB 鉴权；共享表只有 windowId/scopeKey | 阶段 2 必须补 |
| 原文归属 | 向量表即唯一副本 | 换后端 = 换原文存储，风险最高 | 迁移可行性 |

### 1.4 抽象层的缺口（V0 的核心工作）

| 缺口 | 现状 | V0 目标 |
|---|---|---|
| 返回类型泄漏 | `createEmbeddingStore` 返回 LangChain4j `EmbeddingStore` | 增加 `search(collection, vector, topK, filter)` / `upsert` / `delete`，业务侧不接触 `EmbeddingStore` |
| 删除接口绑定 PG | `deleteByFileId` 用 `metadata->>'fileId'` | 改为实现内消化方言；契约只描述语义 |
| 过滤能力缺失 | 检索只支持 `maxResults`，无 metadata 过滤 | 契约增加 `filter`（键值/范围），实现各自翻译 |
| 打分语义 | 余弦 / RRF / Rerank 三种分数混用 | 契约显式返回 `scoreType`，编排层统一换算 |
| 批量与游标 | 无分页/游标读取 | 增加 `scan(collection, cursor, batchSize)` 供回填 |
| 集合元数据 | 只有表名与维度 | 增加集合级元数据（embedding 模型、维度、tenantId、后端类型） |
| 租户断言 | 无 | 契约方法带 `tenantId`（可为空），实现负责过滤 |

---

## 2. 目标架构

### 2.1 方案对比

| 维度 | A：pgvector + 独立搜索引擎 | B：独立向量库 + 保留 pg_trgm | C：一体化引擎（向量 + 关键词同一引擎） |
|---|---|---|---|
| 改动面 | 中（只换关键词路 + 过滤） | 中高（换向量路 + 原文存储） | 高（同时换两路） |
| 新增故障域 | 1 个（搜索集群） | 1 个（向量集群） | 1 个（但承担两路） |
| 召回质量收益 | 高（BM25/分词/过滤/高亮） | 中（ANN 性能与规模） | 高 |
| 规模收益 | 低（向量仍在 PG） | 高（向量独立扩缩容） | 高 |
| 迁移风险 | 低（可双读灰度） | 高（原文与向量一起迁） | 最高（一次性切换） |
| 多租户过滤 | 应用层 + 引擎 filter | 应用层 + 引擎 filter | 应用层 + 引擎 filter |
| 运维成本 | 中 | 中高 | 高 |
| 回滚难度 | 低 | 中 | 高 |
| 适合阶段 | V1 | V2 | V3（可选） |

### 2.2 推荐架构（端口化 + 可组合后端）

```
                         ┌────────────────────────────────────────┐
                         │ RetrievalOrchestrator                   │
                         │ - 多查询变体 / RRF(k=60) / Rerank        │
                         │ - 租户与可见性过滤（应用层强制）          │
                         │ - 后端选择（配置 + 灰度）                │
                         └───────┬───────────────────┬────────────┘
                                 │                   │
                    ┌────────────▼──────┐   ┌────────▼─────────────────┐
                    │ VectorStorePort    │   │ KeywordSearchPort        │
                    │ PgVectorAdapter    │   │ PgTrgmAdapter            │
                    │ Qdrant/MilvusAdapter（V2）│ OpenSearchAdapter（V1） │
                    └────────────┬───────┘   └────────┬─────────────────┘
                                 │                    │
                    ┌────────────▼────────────────────▼───────────────┐
                    │ 事实层（source of truth）                        │
                    │ wgh_kb_chunk（原文 + metadata + tenant_id）       │
                    │ wgh_knowledge_base（collection 指针 + 维度 + 模型）│
                    └─────────────────────────────────────────────────┘
```

**三条设计原则**

1. **事实层与索引层分离**：原文与元数据落 `wgh_kb_chunk`（关系库），向量与关键词索引都是「可重建的派生数据」。这是允许替换后端的**唯一前提**。
2. **租户过滤在编排层强制**：所有端口调用都带 `tenantId`，实现必须翻译成后端过滤条件；不允许「靠引擎权限」。
3. **切换用蓝绿指针，不用原地迁移**：复用现有 `collection_name` / `pending_collection` 的「新建-回填-切换-保留旧」模式（[KnowledgeBaseService.java#L139-L170](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L139-L170)）。

### 2.3 事实层表设计（建议，本次不执行 DDL）

```sql
CREATE TABLE IF NOT EXISTS wgh_kb_chunk (
  id              BIGSERIAL PRIMARY KEY,
  tenant_id       BIGINT      NOT NULL DEFAULT 0,
  kb_id           BIGINT      NOT NULL,
  file_id         BIGINT      NOT NULL,
  chunk_index     INT         NOT NULL,
  content         TEXT        NOT NULL,
  content_hash    VARCHAR(64) NOT NULL,
  metadata_json   JSONB       NOT NULL DEFAULT '{}',
  embedding_id    VARCHAR(64),              -- 派生库中的向量 ID（回填后可空）
  embedding_model_id BIGINT,
  dimension       INT,
  vector_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/SYNCED/STALE
  version         INT         NOT NULL DEFAULT 0,
  create_time     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  update_time     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  deleted         SMALLINT    DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_chunk
  ON wgh_kb_chunk(kb_id, file_id, chunk_index) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_tenant
  ON wgh_kb_chunk(tenant_id, kb_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_status
  ON wgh_kb_chunk(vector_status) WHERE deleted = 0;
```

> 说明：`content_hash` 沿用现有去重语义（`existingContentHashes`）；`vector_status` 是双写一致性对账的依据；`embedding_id` 允许为空以支持「先写事实、后建索引」。

---

## 3. 分阶段落地路径（V0–V3）

### 3.1 V0：可替换性基线（不引入新组件）

**目标**：让「换后端」成为一个可执行、可回滚的常规操作，而不是一次重构。

**改动**
1. 端口补全（§1.4 的 7 项），业务侧不再依赖 `EmbeddingStore` 与 PG 方言。
2. 新增 `wgh_kb_chunk` 事实层；入库链路改为「先写事实，再写向量」；历史数据一次性回填（作为 `VECTOR_BACKFILL` 作业）。
3. 建立检索质量基线：固定数据集 + Recall@K / NDCG / MRR（复用现有评测体系与 CI 门禁），并记录 P50/P95 延迟、单 KB 与多 KB 场景分开统计。
4. 指标：`wgh.retrieval.backend.latency`、`wgh.retrieval.fanout.kb_count`、`wgh.retrieval.vector.table_count`、`wgh.retrieval.index.build.seconds`。
5. 定义 V1/V2 的触发阈值（写入配置或运维手册，不写死代码）。

**验收**
- 事实层与向量层行数一致（对账 SQL 无差异）；删除/重切后无孤儿向量。
- 检索结果与改造前逐条对比，Recall@K 与 NDCG 不下降（允许 ±0.01 波动）。
- 端口契约单测覆盖：过滤、批量、游标、租户断言、分数类型。

**风险**：事实层引入双写，写入放大；需要事务边界与失败补偿（写事实成功、写向量失败时置 `PENDING`，由对账作业补）。

### 3.2 V1：独立搜索引擎（关键词 / 过滤 / 高亮）

**目标**：在不动物理向量的前提下，补齐关键词质量与过滤能力。

**改动**
1. 新增 `OpenSearchAdapter implements KeywordSearchPort`（实现类名可按实际选型调整），索引文档 = chunk 事实 + `tenantId`/`kbId`/`fileId`/标签/时间。
2. 写入链路：事实写入后异步同步搜索索引（失败置 `PENDING`，由 `VECTOR_BACKFILL` 复用同一对账）。
3. 检索编排：关键词路可配置走 `pg_trgm` / 搜索引擎；支持「双读对比」开关（同一 query 两路都跑，只记录差异，不改变返回）。
4. 灰度：按 KB 白名单或流量比例切换；RRF 权重与阈值可配。
5. 删除/重建：`deleteByFileId` / `dropCollection` 同时作用于两路。

**验收**
- 关键词召回质量（人工标注集）优于 pg_trgm；无命中率下降。
- 过滤（按文件、标签、时间、租户）结果正确，且 ANN 主路径不受影响。
- 单路故障时自动降级到另一路，聊天不中断（记录 `retrieval.degraded` 指标）。
- 搜索引擎不可用不影响向量检索与聊天主链路。

**风险**：索引一致性与延迟；中文分词器选型与词典维护；集群运维成本。

### 3.3 V2：独立向量库（按阈值触发）

**触发条件（建议，任意两项）**

| 指标 | 建议阈值 | 说明 |
|---|---|---|
| 向量总行数 | > 5,000 万 | 需压测校准 |
| 知识库数量 | > 2,000 | 每 KB 一表的 catalog 与索引开销 |
| 单次多 KB 检索 KB 数 P95 | > 8 | fan-out 延迟 |
| 检索 P95（向量段） | > 300ms | 排除网络与 LLM |
| HNSW 索引内存占用 | > PG 实例内存 40% | 影响共库业务 |
| 单库重建耗时 | > 2 小时 | 影响发布与运维窗口 |

**改动**
1. 新增 `QdrantAdapter`（或 Milvus / 其他）实现 `VectorStorePort`，能力对齐：upsert、search(filter)、deleteByFileId、scan、count。
2. 双写：事实层写入 → PG 向量 + 目标向量库；`vector_status` 标记两路状态。
3. 蓝绿：按 KB 新建目标后端集合 → 回填 → 校验（行数 + 抽样召回对比）→ 切换 `collection_name`/后端指针 → 保留旧集合 N 天可回滚。
4. 检索编排支持「按 KB 选择后端」，允许同一集群内 PG 与独立向量库并存（迁移期与长期混合）。
5. 租户过滤：写入时落 `tenantId`，检索时强制 filter。

**验收**
- 切换后 Recall@K / NDCG 不低于基线；P95 延迟满足目标。
- 双写不一致率 < 0.1%，且能被对账作业修正。
- 切换过程零停机；回滚在 10 分钟内完成。
- 删除/重切在目标库同样生效（无孤儿向量）。

**风险**：双写一致性、维度/模型绑定、运维新组件、成本；迁移期两套索引并存导致存储翻倍。

### 3.4 V3：统一检索编排与多后端治理（可选）

**内容**：多路融合策略可配置（RRF 权重、去重、时间衰减）；检索缓存分层（语义缓存已存在，可扩展）；按租户/知识库的检索配额；后端健康检查与自动降级；容量与成本看板。

**验收**：任一后端故障时检索可用性 ≥ 99%；容量与成本可按租户归集。

---

## 4. 与现有模块的改造点

| 模块 | 改造 | 阶段 |
|---|---|---|
| `rag-kb`（`VectorStorePort`/`VectorStoreService`） | 端口补全；PG 实现收敛方言；新增目标后端 Adapter | V0 / V2 |
| `rag-kb`（`KeywordSearchPort`/`KeywordSearchService`） | 新搜索引擎 Adapter；双读对比开关 | V1 |
| `rag-kb`（`RagRetrieveService`） | 后端选择、过滤透传、降级、指标；保持 RRF/Rerank 语义 | V0–V3 |
| `rag-kb`（`KnowledgeFileService` / 重建链路） | 先写 `wgh_kb_chunk` 再写索引；蓝绿切换；`KB_REBUILD`/`VECTOR_BACKFILL` 作业 | V0 / V2 |
| `rag-kb`（`SemanticCacheService` / `AgentMemoryService`） | 共享向量表加 `tenantId` 过滤；随端口切换 | V0 / V2 |
| `rag-kb`（`EvalDatasetService` / `RetrievalEvaluationService`） | 评测同时跑多后端，产出对比报告 | V1 / V2 |
| `rag-config`（`PgVectorProperties`） | 新增后端选择与目标库连接配置；保留 PG 配置 | V1 / V2 |
| `rag-chat` | 检索降级提示（关键词路不可用时不影响主流程） | V1 |
| `rag-app` | 新组件连接池、健康检查、优雅停机 | V1 / V2 |
| `rag-scheduler` | `KB_REBUILD`、`VECTOR_BACKFILL`、对账作业 | V0 |
| 运维 | 新组件部署、备份、监控、容量；`deploy/` 增补配置 | V1 / V2 |
| SQL | `wgh_kb_chunk`；`wgh_knowledge_base` 增加后端类型字段（`vector_backend`/`search_backend`） | V0 |

---

## 5. 风险与不做的代价

### 5.1 主要风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 原文只存向量表 | 换后端等于换原文，无法回滚 | V0 先建事实层，禁止跳过 |
| 双写不一致 | 召回缺失或返回已删内容 | `vector_status` + 对账作业 + 抽样对比 |
| 召回质量回退 | 用户可感知答案变差 | 固定评测集 + 灰度 + 逐条对比 |
| 维度/模型绑定 | 换模型必须全量重建 | 集合元数据记录维度与模型；蓝绿重建 |
| 过滤性能 | 过滤 + ANN 组合退化 | 后端侧过滤索引；限制高选择性过滤场景 |
| 新组件运维 | 故障域增加 | 单路故障降级；保留 pgvector 作为兜底 |
| 租户过滤遗漏 | 跨租户召回 | 编排层强制 `tenantId` + 负向测试 + 端口断言 |
| 备份/恢复 | 两套索引 + 事实层的恢复顺序 | 以事实层为准重建索引；备份只保事实层与 KB 元数据 |

### 5.2 不做的代价

| 不做 | 代价 |
|---|---|
| 不做 V0（事实层 + 端口） | 永远无法安全替换后端；每次评估都停在「不敢动」 |
| 不做 V1（搜索引擎） | 关键词召回停留在字符三元组；无法按字段过滤与高亮 |
| 不做 V2（向量库） | 超过阈值后检索延迟与重建时间不可控；共库业务受向量索引内存挤压 |
| 不做租户过滤 | 换后端即引入跨租户召回风险（与多租户阶段 2 冲突） |

---

## 6. 观测与运维

| 指标 | 类型 | 标签 | 用途 |
|---|---|---|---|
| `wgh.retrieval.backend.latency` | Timer | `backend`, `kbMode` | 分后端延迟 |
| `wgh.retrieval.fanout.kb_count` | Histogram | — | 跨 KB 规模 |
| `wgh.retrieval.keyword.hit` | Counter | `backend` | 关键词路命中率 |
| `wgh.retrieval.degraded` | Counter | `reason` | 降级次数 |
| `wgh.vector.index.build.seconds` | Timer | `backend`, `collection` | 建索引成本 |
| `wgh.vector.sync.lag` | Gauge | `backend` | 双写延迟 |
| `wgh.vector.sync.mismatch` | Gauge | `backend` | 对账差异行数 |
| `wgh.vector.table.count` | Gauge | — | 集合数量 |
| `wgh.vector.rows.total` | Gauge | — | 向量总量 |

**运维要求**：新后端必须提供健康检查接入 `/actuator/health`（或独立探针）；备份策略以事实层为准，索引可重建；切换动作必须在管理端可审计。

---

## 7. 验收标准与测试场景

### 7.1 验收标准

| 编号 | 标准 |
|---|---|
| V-A1 | 事实层与索引层行数一致；删除/重切/重建后无孤儿向量 |
| V-A2 | 切换后端后 Recall@K、NDCG、MRR 不低于基线（±0.01） |
| V-A3 | 多 KB 检索 P95 满足目标（阈值需压测确认） |
| V-A4 | 任一后端故障时聊天不中断，降级有指标与日志 |
| V-A5 | 切换零停机，回滚 ≤10 分钟 |
| V-A6 | 租户过滤在单后端与混合后端下均生效（负向测试通过） |
| V-A7 | 双写不一致率 <0.1%，对账作业可自动修正 |
| V-A8 | 备份仅依赖事实层即可完整重建所有索引 |
| V-A9 | 关键词路支持字段过滤与高亮（V1 起） |
| V-A10 | 管理端可见后端类型、索引状态、同步延迟 |

### 7.2 测试场景

| 编号 | 场景 | 期望 |
|---|---|---|
| V-T1 | 单 KB 1,000 万向量检索 | P95 在目标内；无 OOM |
| V-T2 | 10 个 KB 联合检索 | fan-out 延迟可接受；结果与单库一致 |
| V-T3 | 重建大库（双表并存） | 检索持续可用；切换后旧表可回滚 |
| V-T4 | 双写过程中杀掉进程 | 重启后对账补齐；无重复向量 |
| V-T5 | 删除文件 | 两路索引同时清理；无残留召回 |
| V-T6 | 搜索引擎宕机 | 关键词路降级到 pg_trgm；聊天正常 |
| V-T7 | 向量库宕机（V2） | 按配置降级到 PG 或返回明确错误；有指标 |
| V-T8 | 租户 A 检索租户 B 的 KB | 404；共享集合不返回 B 的内容 |
| V-T9 | 切换 embedding 模型（维度变化） | 蓝绿重建；旧集合保留；检索不中断 |
| V-T10 | 从备份恢复 | 只恢复事实层即可重建索引并通过质量门禁 |
| V-T11 | 关键词中文错别字 | BM25/分词实现下召回优于 pg_trgm 基线 |
| V-T12 | 过滤选择性极低（命中 90%） | 延迟可控，不出现全表扫描 |

---

## 8. 与其它三块的衔接

| 块 | 衔接点 |
|---|---|
| 多租户 | 事实层 `tenant_id` 前导索引；共享集合 metadata 加 `tenantId`；阶段 2 的负向测试覆盖向量召回 |
| 调度中心 | `KB_REBUILD`、`VECTOR_BACKFILL`、对账作业都走 BACKGROUND lane；重建进度与失败可见 |
| K8s 编排 | 新后端作为独立工作负载部署（StatefulSet + PVC 或托管服务）；资源与容量规划见 [design-kubernetes-orchestration.md](design-kubernetes-orchestration.md) |

---

## 9. 未验证与推断声明

1. **未做容量与压测**：文中所有阈值（5,000 万行、2,000 KB、300ms 等）为建议值，未实测。
2. **未验证 pgvector 在本项目的实际规模**：本地库数据量未知，无法给出当前水位。
3. **未验证 HNSW 内存占比**：结论按「索引常驻内存」的通用特性推断。
4. **搜索引擎与向量库的具体产品未选型**：本文只给方案类别与适配要求，不锁定厂商。
5. **RRF/Rerank 在不同后端的分数可比性**：未做实验；切换时必须重新校准阈值。
6. **中文分词效果**：未做离线对照，V1 需以标注集验证。
7. **事实层写入放大**：未评估对入库吞吐的影响，需在 V0 压测。

---

## 附录 A：证据索引

| 结论 | 证据 |
|---|---|
| 扩展初始化 | `rag-backend/sql/schema/01-schema.sql#L8-L10` |
| 集合名与维度 | `rag-backend/sql/schema/01-schema.sql#L99-L110` |
| 建库即建表 | `rag-backend/rag-kb/.../service/KnowledgeBaseService.java#L77-L86` |
| 重建蓝绿 | `rag-kb/.../service/KnowledgeBaseService.java#L139-L170` |
| HNSW 索引 | `rag-kb/.../service/VectorStoreService.java#L269-L290` |
| 句柄缓存 | `rag-kb/.../service/VectorStoreService.java#L36-L56` |
| 删除绑定 PG 方言 | `rag-kb/.../service/VectorStoreService.java#L105-L133` |
| 端口契约 | `rag-kb/.../port/VectorStorePort.java#L16-L60`、`port/KeywordSearchPort.java#L13-L18` |
| 关键词实现 | `rag-kb/.../service/KeywordSearchService.java#L47-L118` |
| 逐 KB 串行 | `rag-kb/.../service/RagRetrieveService.java#L99-L122` |
| RRF k=60 | `rag-kb/.../service/RrfFusion.java#L13-L19` |
| Rerank 候选 | `rag-kb/.../service/RagRetrieveService.java#L39-L40`、`#L269-L292` |
| 语义缓存向量表 | `rag-kb/.../service/SemanticCacheService.java#L32-L42` |
| pgvector 配置 | `rag-backend/rag-app/src/main/resources/application.yml#L96-L109` |
| 生产 pgvector 配置 | `rag-backend/rag-app/src/main/resources/application-prod.yml#L42-L50` |
