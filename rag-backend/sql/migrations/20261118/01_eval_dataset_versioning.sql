-- ========================================================
-- 迁移：评测数据集版本化（wgh_eval_dataset / wgh_eval_case / wgh_eval_result）
-- 适用对象：2026-11-18 之前创建的存量库
-- 新库不要执行本脚本：rag-backend/sql/schema/01-schema.sql 已包含这三张表及其索引与注释
-- 执行顺序：先确认库已升级到基线（01-schema.sql 对应版本），再单独执行本脚本
--   1) 新库：psql -f sql/schema/01-schema.sql -f sql/seed/02-data.sql
--   2) 存量库：psql -f sql/migrations/20261118/01_eval_dataset_versioning.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 可重复执行（覆盖同名列注释）
-- 影响面：只新增三张表与三个索引，不改动既有表结构，不写入业务数据
-- ========================================================

BEGIN;

-- 数据集 = 某个知识库下的一组评测用例；current_version 为当前生效版本号（0 表示空数据集）
CREATE TABLE IF NOT EXISTS wgh_eval_dataset (
  id BIGSERIAL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500),
  current_version INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_kb ON wgh_eval_dataset(knowledge_base_id, deleted);
COMMENT ON TABLE wgh_eval_dataset IS '评测数据集（版本化：每次用例变更生成新版本快照）';
COMMENT ON COLUMN wgh_eval_dataset.id IS '主键';
COMMENT ON COLUMN wgh_eval_dataset.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_eval_dataset.name IS '数据集名称';
COMMENT ON COLUMN wgh_eval_dataset.description IS '数据集说明';
COMMENT ON COLUMN wgh_eval_dataset.current_version IS '当前版本号（0=空数据集；每次用例变更 +1）';
COMMENT ON COLUMN wgh_eval_dataset.create_time IS '创建时间';
COMMENT ON COLUMN wgh_eval_dataset.update_time IS '更新时间';
COMMENT ON COLUMN wgh_eval_dataset.create_by IS '创建人';
COMMENT ON COLUMN wgh_eval_dataset.update_by IS '更新人';
COMMENT ON COLUMN wgh_eval_dataset.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 用例快照：同一 (dataset_id, dataset_version) 下为一份不可变用例集合，旧版本只读
CREATE TABLE IF NOT EXISTS wgh_eval_case (
  id BIGSERIAL PRIMARY KEY,
  dataset_id BIGINT NOT NULL,
  dataset_version INT NOT NULL,
  query_text VARCHAR(500) NOT NULL,
  relevant_chunks TEXT NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_eval_case_ds_version ON wgh_eval_case(dataset_id, dataset_version, deleted);
COMMENT ON TABLE wgh_eval_case IS '评测用例版本快照（同一版本内不可变，用于历史结果对比）';
COMMENT ON COLUMN wgh_eval_case.id IS '主键';
COMMENT ON COLUMN wgh_eval_case.dataset_id IS '所属数据集 ID';
COMMENT ON COLUMN wgh_eval_case.dataset_version IS '所属数据集版本号';
COMMENT ON COLUMN wgh_eval_case.query_text IS '评测查询';
COMMENT ON COLUMN wgh_eval_case.relevant_chunks IS '相关 chunk 键集合 JSON 数组，元素形如 "fileId:chunkIndex"';
COMMENT ON COLUMN wgh_eval_case.create_time IS '创建时间';
COMMENT ON COLUMN wgh_eval_case.update_time IS '更新时间';
COMMENT ON COLUMN wgh_eval_case.create_by IS '创建人';
COMMENT ON COLUMN wgh_eval_case.update_by IS '更新人';
COMMENT ON COLUMN wgh_eval_case.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 评测结果归档：一条记录对应某数据集某版本的一次评测
CREATE TABLE IF NOT EXISTS wgh_eval_result (
  id BIGSERIAL PRIMARY KEY,
  dataset_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  dataset_version INT NOT NULL,
  top_k INT,
  hybrid SMALLINT DEFAULT 0,
  query_count INT DEFAULT 0,
  recall_at_k DOUBLE PRECISION,
  precision_at_k DOUBLE PRECISION,
  mrr DOUBLE PRECISION,
  ndcg DOUBLE PRECISION,
  query_hit_rate DOUBLE PRECISION,
  coverage_rate DOUBLE PRECISION,
  details_json TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_eval_result_ds_version ON wgh_eval_result(dataset_id, dataset_version, deleted);
COMMENT ON TABLE wgh_eval_result IS '评测结果归档（按数据集版本留存指标与逐查询明细，支持跨版本对比）';
COMMENT ON COLUMN wgh_eval_result.id IS '主键';
COMMENT ON COLUMN wgh_eval_result.dataset_id IS '所属数据集 ID';
COMMENT ON COLUMN wgh_eval_result.knowledge_base_id IS '评测知识库 ID';
COMMENT ON COLUMN wgh_eval_result.dataset_version IS '评测时使用的数据集版本号';
COMMENT ON COLUMN wgh_eval_result.top_k IS '评测使用的 topK';
COMMENT ON COLUMN wgh_eval_result.hybrid IS '是否启用混合检索（0 否 / 1 是）';
COMMENT ON COLUMN wgh_eval_result.query_count IS '实际参与计算的查询数';
COMMENT ON COLUMN wgh_eval_result.recall_at_k IS 'Recall@K（分母为标注相关集合）';
COMMENT ON COLUMN wgh_eval_result.precision_at_k IS 'Precision@K';
COMMENT ON COLUMN wgh_eval_result.mrr IS 'MRR@K';
COMMENT ON COLUMN wgh_eval_result.ndcg IS 'NDCG@K';
COMMENT ON COLUMN wgh_eval_result.query_hit_rate IS '查询命中率（topK 至少 1 条相关）';
COMMENT ON COLUMN wgh_eval_result.coverage_rate IS '召回覆盖率（实际返回条数 / topK）';
COMMENT ON COLUMN wgh_eval_result.details_json IS '逐查询明细 JSON';
COMMENT ON COLUMN wgh_eval_result.create_time IS '创建时间';
COMMENT ON COLUMN wgh_eval_result.update_time IS '更新时间';
COMMENT ON COLUMN wgh_eval_result.create_by IS '创建人';
COMMENT ON COLUMN wgh_eval_result.update_by IS '更新人';
COMMENT ON COLUMN wgh_eval_result.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

COMMIT;
