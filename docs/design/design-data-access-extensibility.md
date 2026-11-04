# 数据接入层多数据库支持评估与可扩展设计

> 范围：`rag-backend` 全部数据访问链路（MyBatis-Plus、自定义 SQL、pgvector / pg_trgm、schema 与测试基座）。
> 方法：只读静态盘点（代码、SQL、配置、测试资源），未执行 mvn / npm，未修改任何代码。
> 关联文档：[overall-design.md](../overall-design.md)、[design-detailed.md](design-detailed.md)、[quota-design.md](quota-design.md)、[design-file-storage.md](../design-file-storage.md)。

---

## 0. 结论先行

**一句话结论：当前不具备"多数据库部署"的真实需求，也不建议现在为换库铺开适配层；但应把两处已经事实存在的"数据库方言泄漏"收敛为接口——向量/关键词检索访问，以及测试库基线。**

更准确地说，要区分两个目标：

| 目标 | 判断 | 理由 |
|------|------|------|
| A. 支持 MySQL / SQLite / 国产库部署 | **不建议现在做** | 无需求证据；pgvector 是核心能力且无跨库等价物；schema 与检索链路 100% 绑 PG 方言 |
| B. 让数据接入层"可扩展、可替换、测试可信" | **建议做，且成本可控** | 方言泄漏集中在 **4 个直连 JDBC 文件 + 1 个注解 Mapper**；CRUD 层本身已由 MyBatis-Plus 抽象；测试库与生产 schema 已严重漂移 |

三档清单见 §4，关键决策见 §5。

---

## 1. 现状盘点（附证据）

### 1.1 模块与依赖分布

`rag-backend/pom.xml` 共 17 个模块（`rag-common`、`rag-data`、`rag-log`、`rag-auth`、`rag-quota`、`rag-config`、`rag-storage`、`rag-llm`、`rag-kb`、`rag-chat`、`rag-tool`、`rag-agent`、`rag-workflow`、`rag-agent-multi`、`rag-public-access`、`rag-controller`、`rag-app`）。

- `rag-data` 只承载 `BaseEntity`、MyBatis-Plus 配置、类型处理器，**没有仓储层**（[rag-data/pom.xml](../../rag-backend/rag-data/pom.xml)）。
- PostgreSQL 驱动不是集中声明，而是分散在 `rag-config`、`rag-agent`、`rag-agent-multi`、`rag-workflow` 的 pom 中（各自直接依赖 `org.postgresql:postgresql`）。
- 全仓库**没有** Flyway / Liquibase / JOOQ / R2DBC / Hibernate 依赖（对全部 `pom.xml` 检索无命中）。
- **没有任何 MyBatis XML Mapper**：`**/*Mapper.xml` 命中 0；但 `application.yml` 仍配置了 `mapper-locations: classpath*:/mapper/**/*.xml`（[application.yml#L44-L46](../../rag-backend/rag-app/src/main/resources/application.yml#L44-L46)），属于历史遗留的空配置。
- 已有可借鉴的抽象先例：`FileStorageService`（local / OSS，按 `@ConditionalOnProperty` 装配，见 [design-file-storage.md](../design-file-storage.md)）、`PublicAccessStore` 接口、LangChain4j 的 `EmbeddingStore<TextSegment>`。**说明项目具备"按能力抽象"的工程习惯，只是尚未用于数据库。**

### 1.2 MyBatis-Plus 使用广度

| 维度 | 实测 | 证据 |
|------|------|------|
| 实体 | 33 个 `@TableName` 实体 | `grep -rl '@TableName' rag-backend` = 33 |
| Mapper | 33 个接口 `extends BaseMapper` | `**/mapper/*.java` |
| Service 层 | **0 个** `IService` / `ServiceImpl`，服务直接调 Mapper | 全仓库检索无命中 |
| Wrapper | 158 处 `LambdaQueryWrapper` / `UpdateWrapper` 等（不含 import），分布于 40 个文件 | 检索计数 |
| 自定义 SQL | 仅 8 个 Mapper 使用注解 SQL（`@Select/@Update/@Insert/@Delete`），其余 25 个纯 BaseMapper | `**/mapper/*.java` 检索 |
| `.last("LIMIT n")` | 28 处 | 检索计数 |
| 分页方言 | 硬编码 `DbType.POSTGRE_SQL` | [MybatisPlusConfig.java#L19](../../rag-backend/rag-data/src/main/java/com/wgh/rag/data/mybatis/MybatisPlusConfig.java#L19) |
| 布尔列 | 11 个 `@TableField(typeHandler = BooleanToSmallintTypeHandler.class)`，分布于 9 个实体 | `ChatWindow`、`LlmModel`、`ShareLink` 等 |
| 逻辑删除 | `@TableLogic` + 全局 `logic-delete-field: deleted` | [BaseEntity.java#L33-L38](../../rag-backend/rag-data/src/main/java/com/wgh/rag/data/entity/BaseEntity.java#L33-L38)、[application.yml#L56-L61](../../rag-backend/rag-app/src/main/resources/application.yml#L56-L61) |
| ID | `@TableId(type = IdType.AUTO)`，依赖数据库自增 | [BaseEntity.java#L18-L19](../../rag-backend/rag-data/src/main/java/com/wgh/rag/data/entity/BaseEntity.java#L18-L19) |

**解读**：CRUD 主体已经是"表结构 + 通用 Mapper"模式，换库时这部分主要受**列类型**（`SMALLINT` vs `TINYINT`/`BOOLEAN`）与**自增策略**影响，而不是受 SQL 写法影响。真正不可移植的只有少量自定义 SQL（详见 1.3）。

### 1.3 PostgreSQL 专有特性清单

#### 1.3.1 向量与关键词检索（硬依赖）

| 特性 | 位置 | 说明 / 可移植性 |
|------|------|----------------|
| `CREATE EXTENSION vector` | [schema.sql#L8](../../rag-backend/sql/schema/01-schema.sql#L8)；[PgVectorConfig.java#L44-L47](../../rag-backend/rag-config/src/main/java/com/wgh/rag/config/PgVectorConfig.java#L44-L47) | 启动期强依赖：默认失败即阻断启动，`allow-fallback-to-in-memory=true` 时降级为告警；`skip-create-vector-extension` 只跳过建扩展，不改变依赖本身 |
| `vector(n)` 列、余弦距离 `<=>`、可选 IVFFlat/HNSW 索引 | 由 `PgVectorEmbeddingStore` 运行时建表（[VectorStoreService.java#L28-L42](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L28-L42)）；配置见 [application.yml#L86-L94](../../rag-backend/rag-app/src/main/resources/application.yml#L86-L94) | LangChain4j 内部生成 DDL 与距离算子；表名 `rag_vector_store`、`wgh_semantic_cache_vec` 均为运行时建表，不在 `schema.sql` 中 |
| `metadata` 为 JSONB，查询用 `metadata->>'fileId'` | [VectorStoreService.java#L79](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L79) | JSONB + `->>` 专用 |
| `embedding_id` 为 `uuid`，删除用 `?::uuid` | [VectorStoreService.java#L107](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L107) | PG 类型转换 |
| 直接读 `information_schema.tables`（限定 `table_schema='public'`） | [VectorStoreService.java#L68](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L68)、[#L96](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L96) | schema 概念与目录表均按 PG 假设 |
| `pg_trgm`：`similarity()`、`%` 运算符、`GIN (text gin_trgm_ops)` | [schema.sql#L10](../../rag-backend/sql/schema/01-schema.sql#L10)；[KeywordSearchService.java#L54-L55](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L54-L55)、[#L83-L85](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L83-L85) | 混合检索关键词路；失败时静默降级为纯向量（返回空列表） |
| 评测侧按 chunk 分页：`(metadata->>'chunkIndex')::int` + `LIMIT/OFFSET` | [RetrievalEvaluationService.java#L205](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RetrievalEvaluationService.java#L205)、[#L216-L217](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/RetrievalEvaluationService.java#L216-L217) | JSONB + 强转 |
| 语义缓存：向量表 + metadata 过滤 `IsEqualTo("windowId", ...)` | [SemanticCacheService.java#L74-L80](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/SemanticCacheService.java#L74-L80) | 依赖 LangChain4j metadata filter 与 pgvector 实现 |

#### 1.3.2 业务库结构（`sql/schema/01-schema.sql`，1222 行）

| 构造 | 数量 / 位置 | 跨库兼容性 |
|------|-------------|-----------|
| `BIGSERIAL PRIMARY KEY` | 35 处（如 [schema.sql#L15](../../rag-backend/sql/schema/01-schema.sql#L15)） | PG 专用；MySQL 需 `AUTO_INCREMENT`，Oracle 需 sequence/identity |
| `COMMENT ON TABLE/COLUMN` | 372 条 | PG 专用；MySQL 用 `COMMENT=`，Oracle 用独立语句 |
| `DO $$ ... $$` 兼容块 | [schema.sql#L34-L43](../../rag-backend/sql/schema/01-schema.sql#L34-L43) | PG 专用（PL/pgSQL 匿名块） |
| `ALTER COLUMN ... TYPE ... USING` | [schema.sql#L38-L41](../../rag-backend/sql/schema/01-schema.sql#L38-L41) | PG 专用重写语法 |
| `JSONB` 列 | `plan_json` [L427](../../rag-backend/sql/schema/01-schema.sql#L427)、`graph_json` [L516](../../rag-backend/sql/schema/01-schema.sql#L516)/[L552](../../rag-backend/sql/schema/01-schema.sql#L552)、`context_json`/`graph_snapshot` [L619-L620](../../rag-backend/sql/schema/01-schema.sql#L619-L620)、`input_json`/`output_json` [L669-L670](../../rag-backend/sql/schema/01-schema.sql#L669-L670)、多智能体 `crew_config_json`/`agents_json`/`input_context`/`tool_calls_json`/`rag_sources_json`/`group_chat_config_json` [L710-L711](../../rag-backend/sql/schema/01-schema.sql#L710-L711)、[L751-L754](../../rag-backend/sql/schema/01-schema.sql#L751-L754)、[L787-L790](../../rag-backend/sql/schema/01-schema.sql#L787-L790) | Java 侧均为 `String`（见 1.3.3），但列类型与写入校验为 PG 语义 |
| 部分索引 `WHERE feedback <> 0` | [schema.sql#L909](../../rag-backend/sql/schema/01-schema.sql#L909) | PG 支持；MySQL 不支持部分索引 |
| GIN 索引（运行时创建） | [KeywordSearchService.java#L84-L85](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L84-L85) | PG 专用 |
| `ON CONFLICT` | `sql/seed/02-data.sql` 5 处（[L75](../../rag-backend/sql/seed/02-data.sql#L75)、[L88](../../rag-backend/sql/seed/02-data.sql#L88)、[L96](../../rag-backend/sql/seed/02-data.sql#L96)、[L105](../../rag-backend/sql/seed/02-data.sql#L105)、[L113](../../rag-backend/sql/seed/02-data.sql#L113)） | 种子数据专用；H2 用 `MERGE` |
| `jsonb_build_object(...)::text` | [data.sql#L37-L44](../../rag-backend/sql/seed/02-data.sql#L37-L44) | PG 专用 JSON 函数 |
| `E'...'` 转义字符串 | [data.sql#L18](../../rag-backend/sql/seed/02-data.sql#L18) | PG 专用 |
| `DO $$` 类型修正 | [data.sql#L54-L62](../../rag-backend/sql/seed/02-data.sql#L54-L62) | PG 专用 |
| JDBC `stringtype=unspecified` | 4 份配置：[application.yml#L16](../../rag-backend/rag-app/src/main/resources/application.yml#L16)、`application-dev.yml`、`application-test.yml`、`application-prod.yml` | PG JDBC 专用参数（用于让 String 写入 JSONB 不报类型错） |

#### 1.3.3 JSON 字段在 Java 侧的表达

实体把 JSON 列一律映射为 `String`，没有自定义 JSON 类型处理器：

- [WorkflowRun.java#L36-L39](../../rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/entity/WorkflowRun.java#L36-L39)：`contextJson`、`graphSnapshot` 均为 `String`。
- [MultiAgentStep.java#L33](../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/entity/MultiAgentStep.java#L33)：注释明确"输入上下文（JSONB 字符串）"。
- [GraphCoordinator.java#L351](../../rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/graph/GraphCoordinator.java#L351)：注释"The HITL step.inputContext 存暂停节点 ID（JSONB 列要求合法 JSON）"——**业务代码已在心智上依赖"写库时 JSON 必须合法"** 这一 PG 行为。

这带来一个双面结论：

- 好消息：Java 侧没有绑定 PG 的 JSON 类型处理器，换成 `TEXT`/`VARCHAR(MAX)` 的业务改造成本低。
- 风险：当前合法 JSON 的保障来自数据库（JSONB 校验），换成文本列后需在应用层补校验，否则脏数据可写入。

#### 1.3.4 反证：哪些 PG 专有能力"没有使用"

以下能力在代码与 SQL 中均**未出现**（检索无命中），说明当前 SQL 复杂度不高，这也是"换库成本没有想象中大"的依据：

- `RETURNING`
- CTE / `WITH RECURSIVE`
- 窗口函数 / `OVER (...)` / `PARTITION BY`
- `LATERAL`、`array_agg`、`string_agg`、`DISTINCT ON`

**唯一一处 PG 专属查询语义**是成本统计：

- `to_char(create_time, 'YYYY-MM-DD')`、`CAST(#{to} AS DATE) + INTERVAL '1 day'`（[ChatMessageMapper.java#L18-L30](../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/mapper/ChatMessageMapper.java#L18-L30)）。
- `LIMIT #{size} OFFSET #{offset}`（[ChatMessageMapper.java#L59](../../rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/mapper/ChatMessageMapper.java#L59)）——该写法在 PG/MySQL/SQLite/H2 均可用，不算方言。

### 1.4 测试基座

**现状：已有 H2，但不是"多数据库支持"，而是"第二套 DDL"。**

- H2 依赖仅在 `rag-app` 且为 `test` scope（[rag-app/pom.xml#L74-L79](../../rag-backend/rag-app/pom.xml#L74-L79)）。
- 测试数据源：`jdbc:h2:mem:wgh_rag_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`（[src/test/resources/application.yml#L12](../../rag-backend/rag-app/src/test/resources/application.yml#L12)）。
- 测试 schema 由 `src/test/resources/schema.sql` 单独维护（278 行），**不是**生产 `schema.sql` 的派生物。

#### 1.4.1 漂移事实（关键风险）

| 维度 | 生产 `rag-backend/sql/schema/01-schema.sql` | 测试 `rag-app/src/test/resources/schema.sql` |
|------|-----------------------------------|----------------------------------------------|
| 表数量 | 37 张 | 15 张 |
| 会话表列 | `chat_window_id`、`user_identifier`、`persistent`、`message_count`、`total_tokens`、`status` 等 | `window_id`、`user_id`，缺上述列（[schema.sql#L136-L157](../../rag-backend/rag-app/src/test/resources/schema.sql#L136-L157)） |
| 知识文件列 | `knowledge_base_id`、`file_name`、`split_config`、`error_message` | `kb_id`、`original_name`、`split_params`、`error_msg`（[schema.sql#L194-L211](../../rag-backend/rag-app/src/test/resources/schema.sql#L194-L211)） |
| LLM 模型列 | `status SMALLINT`、`compression_model SMALLINT` | `enabled BOOLEAN`、`compression_model BOOLEAN`（[schema.sql#L213-L229](../../rag-backend/rag-app/src/test/resources/schema.sql#L213-L229)） |
| 种子策略 | `INSERT ... ON CONFLICT` | `MERGE INTO ... KEY(...)`（[schema.sql#L60-L93](../../rag-backend/rag-app/src/test/resources/schema.sql#L60-L93)） |
| 缺失表 | — | Agent（`wgh_agent_plan/step`）、Workflow（4 张）、多智能体（3 张）、`wgh_prompt_template`、`wgh_audit_log`、`wgh_semantic_cache`、标签与文件版本、分享/API Key 等 |

**结论**：H2 只覆盖"用户/角色/配额/窗口/会话/知识库基础 CRUD + Web 层"；Agent、Workflow、Prompt 模板、语义缓存、分享/开放 API 等模块**没有数据库级集成测试**。更糟的是，已有表的列名/列类型与生产不一致，一旦业务代码按生产 schema 写 SQL，H2 测试会给出**假阴性或假阳性**。

依赖 H2 的集成测试示例：

- [RagApplicationIntegrationTest.java#L16-L19](../../rag-backend/rag-app/src/test/java/com/wgh/rag/RagApplicationIntegrationTest.java#L16-L19)（上下文、鉴权、分享）；
- `QuotaApiTest`、`QuotaConcurrencyTest`、`KnowledgeBaseEndpointTest`、`SkillManagementApiTest`、`LocalAuthApiTest`、`PublicAccessRedisSecurityTest`。
- 其余大量测试是 Mockito 纯单元测试（如 `TextSplitterTest`），不接触数据库。

#### 1.4.2 `rag-quota` 的"PG/H2 可移植原子 upsert"是怎么做的

实现位于 [QuotaUsageMapper.java#L44-L55](../../rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/QuotaUsageMapper.java#L44-L55)：

1. 先执行 `UPDATE ... SET chat_count = chat_count + ? ...`（[L20-L28](../../rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/QuotaUsageMapper.java#L20-L28)）；
2. 未命中（返回 0）则 `INSERT`（[L30-L35](../../rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/QuotaUsageMapper.java#L30-L35)）；
3. 插入撞唯一键 `(username, date_key)` 时捕获 `DuplicateKeyException`，回退再执行一次 `UPDATE`。

正确性由 `QuotaConcurrencyTest` 在 H2 上以 16 线程 × 10 次真实并发提交验证（[QuotaConcurrencyTest.java#L73-L111](../../rag-backend/rag-app/src/test/java/com/wgh/rag/quota/QuotaConcurrencyTest.java#L73-L111)）。

**可推广边界**：

- 可推广：所有"单行幂等累加/幂等 upsert"，前提是**有唯一约束**、操作只涉及标准 `UPDATE`/`INSERT`。
- 不可推广：`RETURNING` 取值、JSONB 局部更新（`jsonb_set`）、数组操作、部分索引依赖、`ON CONFLICT DO NOTHING` 批量种子、`to_char/INTERVAL` 类日期格式化。

另一个可借鉴点（但方向相反）：`UserQuotaConfigMapper` 的注释明确写着"enabled 用 0/1，避免 PostgreSQL smallint 类型不匹配"（[UserQuotaConfigMapper.java#L23-L36](../../rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/UserQuotaConfigMapper.java#L23-L36)）。这说明布尔/smallint 的差异**已经通过约定被收敛到类型处理器**，是当前最成功的"方言隔离"实践。

#### 1.4.3 H2 `MODE=PostgreSQL` 与 PG 的真实差距

| 能力 | H2 (MODE=PostgreSQL) | PG 15+ |
|------|----------------------|--------|
| 基础 DDL（`CREATE TABLE IF NOT EXISTS`、`SERIAL`） | 支持 | 支持 |
| `BIGSERIAL` / `SMALLINT` / `DOUBLE PRECISION` | 部分支持（测试 schema 改用 `SERIAL`/`INT`） | 支持 |
| `vector` 类型、`<=>`、HNSW/IVFFlat | **不支持** | 支持（pgvector） |
| `pg_trgm`、`similarity`、`%`、GIN | **不支持** | 支持 |
| JSONB、`->>`、`::` 类型转换 | 不支持 JSONB（仅 JSON 基础函数） | 支持 |
| `COMMENT ON` / `DO $$` / `ALTER ... USING` | 不支持 | 支持 |
| 部分索引 | 不支持 | 支持 |
| `ON CONFLICT` | 不支持（用 `MERGE`） | 支持 |
| `stringtype=unspecified` | 无意义 | PG JDBC 参数 |
| 日期格式 `to_char` / `INTERVAL` | 语义不同/不支持部分格式 | 支持 |
| 并发唯一键冲突异常类型 | `DuplicateKeyException` 可触发 | 同左 |

因此 **H2 能验证 Spring 装配、鉴权、参数校验、简单 CRUD；不能验证检索、JSONB、迁移脚本、并发 SQL 的 PG 语义。**

### 1.5 真实现状：有没有 DataSource 抽象 / 多数据源 / 读写分离

- **没有**。全仓库检索 `AbstractRoutingDataSource`、`@DS`、`@Primary`、读写分离、多数据源均无命中。
- 只有单一 `spring.datasource`，被 4 个服务直接注入并手写 JDBC：
  - [VectorStoreService.java#L24](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java#L24)
  - [KeywordSearchService.java#L35](../../rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KeywordSearchService.java#L35)
  - `RetrievalEvaluationService`
  - [PgVectorConfig.java#L34](../../rag-backend/rag-config/src/main/java/com/wgh/rag/config/PgVectorConfig.java#L34)
- 这些类共 19 处 `getConnection()/prepareStatement/createStatement`，是**直连 PG 方言的主要载体**；除此之外，唯一的 PG 专属注解 SQL 是 `ChatMessageMapper` 的 `to_char` / `INTERVAL` 日期统计。
- 启动健康检查用 `SELECT extname FROM pg_extension`（[PgVectorConfig.java#L50-L51](../../rag-backend/rag-config/src/main/java/com/wgh/rag/config/PgVectorConfig.java#L50-L51)），属于 PG 目录表硬编码。

**结论**：现状不是"多库架构缺一层"，而是"单库架构里有一小簇 PG 直连代码 + 一套与生产漂移的 H2 测试库"。

---

## 2. 评估：什么场景才真的需要多库

### 2.1 逐场景判定

| 场景 | 当前是否需要 | 判断依据 |
|------|--------------|----------|
| H2/内存库做单测 | **部分需要，但方式错误** | 已有 H2，但用第二套 DDL 且只覆盖 15/37 表；继续扩 H2 只会放大漂移，不等于"支持多库" |
| SQLite / MySQL 部署 | **不需要** | README 明确"单库 PostgreSQL + pgvector"为设计原则（[README.md#L32](../../README.md#L32)、[#L167](../../README.md#L167)、[#L387](../../README.md#L387)、[#L414](../../README.md#L414)）；无任何 MySQL 配置/依赖/兼容代码 |
| 国产化信创（达梦 / 人大金仓 / OceanBase / 高斯） | **当前无需求，延后** | 无客户合同/招标约束证据；且 pgvector 在这些库上要么缺失、要么是另一套向量扩展（Kingbase/OceanBase 各自不同），迁移成本主要在向量层而非 CRUD |
| 分库分表 | **不需要** | 37 张表、单实例、`IdType.AUTO` 自增；无分片键、无分布式 ID 基建；当前压测/部署形态未见分片诉求 |
| 读写分离 | **不需要（现在）** | 无只读副本配置；评测/统计查询量级未证明需要；引入后要处理"写完立刻读"的一致性 |
| 独立向量库（Milvus / Qdrant / ES） | **不需要** | README 明确"不做独立向量数据库/搜索引擎"（[README.md#L32](../../README.md#L32)、[#L387](../../README.md#L387)、[#L414](../../README.md#L414)） |
| 多租户 | **不做** | README 明确排除 |

### 2.2 换库的真实阻碍（按成本排序）

1. **pgvector 是硬依赖，且不止一个入口。** 向量检索本身有 `EmbeddingStore<TextSegment>` 抽象，但项目还直接用了：
   - `PgVectorEmbeddingStore` 具体类型返回值（`VectorStoreService#createEmbeddingStore` 返回具体类，泄漏实现）；
   - 手写 SQL 删除/枚举（`metadata->>`、`::uuid`、`information_schema`）；
   - `pg_trgm` 关键词路（无跨库等价物）。
   
   换库时必须同时解决"向量存储 + 关键词检索 + 向量表 DDL"三件事，不是换 JDBC URL 能完成的。

2. **`schema.sql` 是 PG 方言文件。** 1222 行、37 张表、35 个 `BIGSERIAL`、372 条 `COMMENT ON`、PL/pgSQL 块、部分索引、JSONB。它不是"标准 SQL"，而是"PG 运维脚本"。

3. **实体/Wrapper 深度耦合 MyBatis-Plus 约定。**
   - 33 实体 + 33 Mapper + 158 处 Wrapper；分页方言硬编码 PG；
   - 28 处 `.last("LIMIT n")`（虽多数库兼容，但属于"绕过 Wrapper 语义"的写法）；
   - `IdType.AUTO` 依赖数据库自增；
   - 布尔列用 `SMALLINT` + 自定义 handler，换 MySQL（`TINYINT(1)` 原生布尔）或 Oracle（`NUMBER(1)`）需要重新确认双向映射。

4. **种子与兼容修复混在 `data.sql`。** `jsonb_build_object`、`E''`、`DO $$`、`ON CONFLICT`，且同时承担"老库修复"，任何跨库迁移框架都难以直接接管。

5. **测试基线漂移。** 见 1.4.1：15/37 表、列名不同、布尔类型不同。**这是当前最应该先修的问题**，因为它在损伤日常开发可信度，且与"是否多库"无关。

### 2.3 如果结论是"不需要多库"，仍值得做的最小动作

1. **把向量/关键词访问收敛成 2 个接口**（`VectorStorePort`、`KeywordSearchPort`），把 4 个文件里的 PG 直连 SQL 分别收进对应的 PG 适配器。
   - 收益：未来换向量后端只改实现；单测可用内存实现，不必依赖 H2/pgvector；pgvector 故障时行为可显式配置（当前是 try/catch 静默降级）。
2. **修测试基线漂移**：要么用 Testcontainers PostgreSQL（与生产同方言），要么让 H2 schema 由生产 schema 机械派生（禁止手改）。
   - 收益：消除"测试通过、生产炸"的假信心；新增表/列自动被覆盖。
3. **把 MyBatis-Plus 分页方言改为可配置**（`mybatis-plus.db-type` / 自定义属性），去掉 `DbType.POSTGRE_SQL` 硬编码。
   - 收益：1 个配置项即可切换分页语法，成本极低。
4. **JSON 字段明确"应用层负责合法性"**：为 JSON 列补 `JsonStringTypeHandler`（写入前校验/规范化），或在实体层统一注释约束。
   - 收益：未来换成 `TEXT`/MySQL `JSON` 时不会丢失数据质量保障。
5. **布尔/ID 约定写进设计文档与类型处理器注释**：`SMALLINT(0/1)` ↔ `Boolean`；`IdType.AUTO` ↔ 自增列；跨库时需要改的只有这两处全局配置。

---

## 3. 设计方案（分层给，标成本）

### 3.0 设计前提：把"换库"与"换向量后端"分开

- **换业务库（MySQL/国产库）**：高成本、低当前收益，不建议提前做。
- **换向量后端（pgvector → 其他）**：中等成本、高扩展收益，建议现在做接口边界。

下面的层次按"收益/成本比"从高到低排列。

### 层次一：Repository / Port 边界

**不建议**为 33 张表各建一个 Repository——MyBatis-Plus 的 `BaseMapper` 已经是通用数据网关，再包一层只会增加 33 个空壳文件和一层无意义转发（典型"为架构而架构"）。

**建议**只抽 4 类"语义型端口"：

| 端口 | 覆盖现有实现 | 为什么值得抽 |
|------|--------------|--------------|
| `QuotaUsageRepository` | `QuotaUsageMapper#upsertIncrement/resetUsage` | 原子 upsert 是跨库语义差异点；接口可表达"幂等累加"，实现可换 PG `ON CONFLICT` 或 UPDATE-first |
| `ChatAnalyticsRepository` | `ChatMessageMapper` 的 3 个统计/分页查询 | 唯一含 PG 日期函数（`to_char`/`INTERVAL`）的业务 SQL |
| `VectorStorePort` | `VectorStoreService` + `SemanticCacheService` 的向量读写 | 见层次二 |
| `KeywordSearchPort` | `KeywordSearchService` | pg_trgm 无跨库等价物，需显式可替换（含 Noop 实现） |

不建议抽的：`UserMapper`、`RoleMapper`、`ChatWindowMapper` 等纯 CRUD —— 它们的"可移植性"由类型处理器与分页方言统一解决，无需逐个端口化。

**成本**：中（4 个接口 + 4 个实现适配，约 300–500 行改动，其中大部分是把现有逻辑平移）。**亏本边界**：一旦开始为每张表建仓储、为每个查询建 DTO 映射，收益迅速转负。

### 层次二：向量存储抽象（VectorStore SPI）

现状问题：

- `VectorStoreService` 返回具体 `PgVectorEmbeddingStore`，调用方直接依赖 LangChain4j 的 PG 实现；
- 删除/枚举/清表走裸 JDBC，且 SQL 内含 `metadata->>`、`::uuid`、`information_schema`；
- `PgVectorEmbeddingStore` 的 `EmbeddingStore` 接口**不覆盖**"按 fileId 删除、按 embeddingId 批量删除、按页枚举 chunk、drop table"这些项目实际需要的能力。

建议定义自己的薄 SPI（保留 LangChain4j 仅作为内部实现细节）：

```java
public interface VectorStorePort {
    void upsert(CollectionRef collection, List<TextSegment> segments, EmbeddingModel model, int dimension);
    List<VectorMatch> search(CollectionRef collection, Embedding embedding, int topK, double minScore);
    List<VectorMatch> searchByMetadata(CollectionRef collection, Embedding embedding,
                                       MetadataFilter filter, int topK, double minScore);
    int deleteByMetadata(CollectionRef collection, MetadataFilter filter);   // 如 fileId
    int deleteByIds(CollectionRef collection, Collection<String> ids);
    Page<VectorMatch> listChunks(CollectionRef collection, MetadataFilter filter, int page, int size);
    void dropCollection(CollectionRef collection);
    void ensureIndex(CollectionRef collection, IndexSpec spec);              // pgvector: IVFFlat/HNSW
    boolean isAvailable();
}
```

实现：

- `PgVectorStoreAdapter`：内部继续用 `PgVectorEmbeddingStore` + 现有 JDBC 删除/枚举逻辑，把 1.3.1 中的所有 PG 细节封在这里。
- `InMemoryVectorStoreAdapter`（`@ConditionalOnProperty` 或 test profile）：供单测/降级使用，替代"用 H2 假装向量库"。
- 未来 `Milvus/Qdrant/ES`：新增实现即可，不动检索业务。

`KeywordSearchPort` 同理：

```java
public interface KeywordSearchPort {
    List<VectorMatch> search(CollectionRef collection, String query, int topN);
    boolean isAvailable();
}
```

实现 `PgTrgmKeywordSearchAdapter` 与 `NoopKeywordSearchAdapter`（显式表达"当前无关键词能力"，而不是异常后静默返回空列表）。

**成本**：中（接口 + PG 实现 + 调用方改造，约 5–8 个文件、400–700 行）。**高收益**：这是唯一能"一次投入、长期可换后端"的层。**亏本边界**：不要把 RRF 融合、rerank、查询变换也塞进 SPI —— 那些是检索编排，与存储无关；抽象越往上，泄漏越多。

### 层次三：SQL 方言隔离

先把事实说清楚：**自定义 SQL 只有 8 个 Mapper，其中真正 PG 专属的只有 1 个文件**（`ChatMessageMapper` 的 `to_char` / `INTERVAL` 日期统计）；`QuotaUsageMapper` 已改为可移植的 UPDATE-first + 唯一键回退（其注释明确写明"H2/PostgreSQL 均可运行"），其余 6 个是标准 SQL。

建议布局：

```
rag-data/.../dialect/
  SqlDialect.java              // 能力枚举：supportsJsonb/supportsPartialIndex/...
  PostgresDialect.java
  H2Dialect.java（仅测试）
  DialectProperties.java       // wgh.db.dialect=postgresql（默认）
```

装配方式（与现有 `FileStorageService` 的模式一致）：

- `@ConditionalOnProperty(name = "wgh.db.dialect", havingValue = "postgresql", matchIfMissing = true)` 装配 PG 方言 Bean；
- 分页插件改为读取 `DialectProperties#getPaginationDbType()`，不再硬编码 `DbType.POSTGRE_SQL`；
- 若未来出现 2 套以上方言，再引入 MyBatis `databaseIdProvider` 按 vendor 选择 statement，**不要现在引入**（当前无第二个方言，属过度设计）。

**成本**：小（1 个属性类 + 1 个 Bean + 1 处分页改造，约 80–150 行）。**亏本边界**：引入 JSQLParser 之类"SQL 自动翻译层"——对 8 个 Mapper 而言完全不划算。

### 层次四：迁移与 schema

**现状问题**：

- `init.sql` 只是 `\ir schema/01-schema.sql; \ir seed/02-data.sql;`（[init.sql#L16-L17](../../rag-backend/sql/init.sql#L16-L17)），依赖 `psql`，应用内不能执行；
- 无版本表、无校验和、无回滚；"结构 + 种子 + 老库修复"混在一起；
- 生产部署依赖 compose 挂载初始化目录（README 记录"空卷首启执行 schema/01-schema.sql 与 seed/02-data.sql"），已建库只能手工重跑；
- 幂等靠 `IF NOT EXISTS` / `DO $$` / `ON CONFLICT` 手工保证。

**建议**：

| 方案 | 适用 | 成本 | 结论 |
|------|------|------|------|
| 维持现状 + 增加 `schema_version` 表 + 拆分 `compat-fix.sql` | 单库、部署简单 | 小 | **立即可做的最小改进** |
| Flyway（`db/migration/common` + `db/migration/<vendor>`） | 未来需要多环境/多方言 | 中 | 若开始做多库，这是首选；顺带解决版本审计与重复执行 |
| Liquibase | 需要 XML/YAML changelog、回滚、复杂变更集 | 中–大 | 当前无回滚/审计强诉求，**不推荐** |
| 自研迁移器 | — | 大 | 不建议 |

例如 Flyway 的多方言布局（仅在真正多库时才需要）：

```
db/migration/common/V1__baseline.sql          # 标准 SQL 部分
db/migration/postgresql/V2__pg_indexes.sql    # GIN/partial index/COMMENT ON
db/migration/mysql/V2__mysql_indexes.sql
```

**成本**：Flyway 接入本身小，但把 1222 行 `schema.sql` 拆成"基线 + 增量"并保证存量库 baseline 正确是**中–大**（涉及全部部署环境回归）。因此：**单库阶段建议延后，只做 `schema_version` + `compat-fix` 拆分**。

### 层次五：类型处理器与 JSON 字段的跨库表达

现状：

- 布尔：`BooleanToSmallintTypeHandler`（[BooleanToSmallintTypeHandler.java#L19-L42](../../rag-backend/rag-data/src/main/java/com/wgh/rag/data/handler/BooleanToSmallintTypeHandler.java#L19-L42)），把 `Boolean` ↔ `SMALLINT`。这是**已经收敛好的方言点**。
- JSON：全部为 `String`，依赖 PG `JSONB` 的写入校验（`stringtype=unspecified` 令 String 可写入 JSONB）。
- ID：`IdType.AUTO`，依赖 `BIGSERIAL`/`AUTO_INCREMENT`。

建议：

1. **保留布尔 handler**，但在注释中明确"MySQL 可原生处理 `TINYINT(1)`，Oracle 需 `NUMBER(1)`，切换时只需替换 handler 实现"。
2. **新增 `JsonStringTypeHandler`**（Jackson 校验 + 规范化输出），用于所有 JSON 列；在 PG 下退化为当前行为，在文本列下补上合法性保障。
3. **ID 策略文档化**：若未来上 Oracle 或无自增的分布式库，全局切换 `mybatis-plus.global-config.db-config.id-type=assign_id`（雪花），无需逐实体修改。
4. **日期/时间**：统一 `TIMESTAMP` + `LocalDateTime`，避免 `TIMESTAMPTZ`；当前已是如此，保持即可。

**成本**：小（1 个 handler + 11 处 `@TableField` 注释/绑定，约 100–200 行）。**亏本边界**：为每个实体写定制 TypeHandler，而不是统一按"列族（布尔/JSON/时间）"处理。

### 3.6 成本–收益曲线（做到哪一层超值 / 哪一层开始亏本）

| 层次 | 投入 | 收益 | 判断 |
|------|------|------|------|
| 层次二（VectorStore/Keyword SPI） | 中 | 高（唯一真正的"可换后端"入口，且改善测试与降级语义） | **超值** |
| 层次三（薄方言隔离 + 分页可配） | 小 | 中（去掉硬编码，成本极低） | **超值** |
| 层次五（JSON/布尔/ID 约定） | 小 | 中（未来换库的主要"隐性坑"被消除） | **超值** |
| 层次一（仅 4 个语义端口） | 中 | 中 | 超值 |
| 层次一（全表 Repository） | 大 | 低 | **亏本** |
| 层次四（Flyway，多库布局） | 中–大 | 多库时高；单库时主要是运维收益 | **持平（单库）/ 超值（多库）** |
| 完整 SQL 方言翻译层 / JOOQ 重写 | 大 | 低（自定义 SQL 仅 8 个 Mapper） | **亏本** |
| 每库一套 schema + CI 矩阵 | 大 | 仅在合同要求时成立 | **当前亏本** |

---

## 4. 三档清单

### 4.1 建议做（现在）

| # | 动作 | 理由（对应证据） | 工作量 |
|---|------|------------------|--------|
| 1 | 抽 `VectorStorePort` / `KeywordSearchPort`，PG 细节集中到 `PgVectorStoreAdapter` / `PgTrgmKeywordSearchAdapter` | 全部 PG 向量/关键词方言集中在 4 个文件（§1.5）；`VectorStoreService` 泄漏具体 `PgVectorEmbeddingStore`；关键词检索失败会静默降级 | 中（5–8 文件，400–700 行） |
| 2 | 修测试基线漂移：优先 Testcontainers PostgreSQL；否则由生产 schema 机械派生 H2 schema | 15/37 表且列名/列型与生产不一致（§1.4.1），已在产生假信心 | 中（测试基建 + 2–3 个基类，200–400 行） |
| 3 | 分页方言去硬编码；方言能力集中到 `wgh.db.dialect` 属性 | `DbType.POSTGRE_SQL` 硬编码（§1.2） | 小（80–150 行） |
| 4 | JSON 列统一 `JsonStringTypeHandler`，布尔/ID 约定写入文档 | JSON 合法性当前依赖 PG（§1.3.3）；布尔已收敛但无文档 | 小（100–200 行） |
| 5 | 拆出 `compat-fix.sql`，增加 `schema_version` 表 | `data.sql` 同时承担种子与老库修复（§1.3.2）；`init.sql` 不可被应用执行 | 小（50–100 行 SQL） |

### 4.2 建议不做（现在）

| # | 动作 | 理由 |
|---|------|------|
| 1 | 引入 MySQL / SQLite 作为可部署数据库 | 无需求；README 明确单库 PG + pgvector；向量/关键词/DDL 三重阻碍 |
| 2 | 为 33 张表建 Repository / DTO 映射层 | `BaseMapper` 已是通用网关；收益低于维护成本 |
| 3 | 独立向量库（Milvus/Qdrant/ES）迁移 | 明确排除项；且当前向量规模与运维诉求不支持 |
| 4 | 分库分表 / 读写分离 | 无规模证据；会破坏现有 `IdType.AUTO` 与事务内读写一致性假设 |
| 5 | SQL AST 翻译层 / JOOQ 全量重写 | 自定义 SQL 只有 8 个 Mapper，其中 PG 专属仅 1 个；投入产出比极低 |

### 4.3 建议延后（按触发条件）

| # | 动作 | 触发条件 | 前置条件 |
|---|------|----------|----------|
| 1 | 国产化信创库适配（达梦/金仓/OceanBase/高斯） | 出现明确招标/合同要求 | 先完成层次二 SPI；再评估目标库向量扩展能力（可能仍需外接向量库） |
| 2 | 全量 Flyway 接管（含多方言目录） | 部署环境 > 2 套，或需要版本审计/回滚 | 完成 §4.1-5 的 `schema_version` 过渡与存量库 baseline |
| 3 | 读写分离 / 只读副本 | 统计/评测查询对主库造成可观测压力 | 先完成端口化，再在 DataSource 层加路由 |
| 4 | 分库分表 | 单表数据量或写 QPS 触达单库瓶颈 | 需要分布式 ID + 分片键设计，当前无基础 |
| 5 | 独立向量数据库 | 向量规模超过 pgvector + IVFFlat 的检索性能目标（README 已记录需评估性能上限） | SPI 就绪后按实现替换，业务零改动 |

---

## 5. 最关键的 3 个设计决策

### 决策 1：把 PostgreSQL + pgvector 当作"明确的架构约束"，而不是"待修复的耦合"

**理由**：

- README 已把"单库 PostgreSQL + pgvector、不做独立向量库"写成设计原则（[README.md#L32](../../README.md#L32)、[#L387](../../README.md#L387)、[#L414](../../README.md#L414)）；
- 向量检索、关键词混合检索、向量表 DDL 三处都无跨库等价物；换库的真实成本由**向量层**决定，而不是 JDBC 驱动；
- 在无客户需求的前提下做多库适配，会把每一条新功能都变成"多方言维护"。

**落地含义**：不做 MySQL/国产库适配；但保留"可替换"的接缝（层次二），把"当前选择"与"未来可能"解耦。

### 决策 2：只抽象"向量/关键词访问"，不抽象全部数据访问

**理由**：

- 33 个 Mapper + 158 处 Wrapper 的 CRUD 已由 MyBatis-Plus 统一，额外仓储层不产生可移植性收益；
- 几乎所有 PG 专属 SQL 集中在 4 个直连 JDBC 文件（VectorStoreService、KeywordSearchService、RetrievalEvaluationService、PgVectorConfig）+ 1 个注解 Mapper（ChatMessageMapper）；
- 抽象 SPI 能同时解决三个现实问题：换后端、单测替身、pgvector 不可用时的显式降级。

**落地含义**：`Repository` 只做 4 个语义端口；其余保持 `BaseMapper`。

### 决策 3：测试库优先"同方言真实 PG"，而不是"扩大 H2 覆盖"

**理由**：

- 当前 H2 只有 15/37 表，且 `chat_session`、`knowledge_file`、`llm_model` 的列名/列型与生产不一致（§1.4.1）；
- H2 不支持 vector / pg_trgm / JSONB / 部分索引 / `COMMENT ON` / `DO $$`，这些恰好是本项目最核心的数据能力；
- 继续补 H2 表只会让"第二套 schema"更难维护，且无法验证迁移脚本。

**落地含义**：保留 H2 用于 Web 层/鉴权/参数校验测试；数据库语义（检索、JSONB、迁移、并发 SQL）用 Testcontainers PostgreSQL 或真实测试库。若 CI 无法起容器，则用"由生产 schema 机械派生"的 H2 schema，并明确标注"仅覆盖基础 CRUD"。

---

## 6. 若后续实施：最小验证清单（本次不执行）

1. 新增 `VectorStorePort` 后，`RagRetrieveService`、`SemanticCacheService`、`KnowledgeFileService`、`RetrievalEvaluationService` 应只依赖接口，不出现 `PgVectorEmbeddingStore` 类型。
2. 关键词检索关闭/扩展缺失时，行为必须是"显式 Noop + 指标/日志"，而不是异常后静默返回空列表。
3. 分页接口在 `wgh.db.dialect` 切换后仍返回正确 `total` 与页大小（防止 `LIMIT offset,size` 语法差异）。
4. JSON 列写入非法 JSON 时，应用层应拒绝而不是写入脏数据（替代当前 JSONB 的隐式校验）。
5. 测试库与生产 schema 的差异必须可自动检测（表/列/类型三层比对），避免再次漂移。

---

## 附录 A：本次盘点中发现的文档/实现不一致（建议顺手修正）

- [quota-design.md#L12](quota-design.md#L12)、[#L44](quota-design.md#L44)、[#L114](quota-design.md#L114) 仍写"使用 `ON CONFLICT` 原子累加"，但实现已改为"H2/PG 可移植的 UPDATE-first + 唯一键回退"（[QuotaUsageMapper.java#L37-L55](../../rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/QuotaUsageMapper.java#L37-L55)）。建议更新文档，否则后续读者会误判方言依赖。
- `application.yml` 仍配置 `mapper-locations`，但仓库无 XML Mapper；可保留（未来可能使用），但应在注释中说明"当前无 XML"。

## 附录 B：本次未覆盖的内容

- 未统计运行期实际 SQL 执行频次与慢查询分布（需运行环境采样，本次禁止启动/构建）。
- 未评估 pgvector 在目标数据规模下的索引参数（`index-list-size`、HNSW/IVFFlat 选择），该问题属于向量性能调优，不属于数据接入层可扩展性。
</assistant_message>