-- ========================================================
-- 迁移：URL / HTTP 数据源连接器（wgh_kb_connector / wgh_kb_connector_run）
-- 适用对象：2026-11-18 之前创建的存量库
-- 新库不要执行本脚本：rag-backend/sql/schema/01-schema.sql 已包含这两张表及其索引与注释
-- 执行顺序：先确认库已升级到基线（01-schema.sql 对应版本），再单独执行本脚本
--   1) 新库：psql -f sql/schema/01-schema.sql -f sql/seed/02-data.sql
--   2) 存量库：psql -f sql/migrations/20261118/02_kb_connector.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 可重复执行（覆盖同名列注释）
-- 影响面：只新增两张表与三个索引，不改动既有表结构，不写入业务数据
-- ========================================================

BEGIN;

-- 连接器配置：一个连接器对应知识库内的一个单页 URL，按间隔重抓，内容未变不重复入库
CREATE TABLE IF NOT EXISTS wgh_kb_connector (
  id                    BIGSERIAL PRIMARY KEY,
  knowledge_base_id     BIGINT NOT NULL,
  name                  VARCHAR(200) NOT NULL,
  url                   VARCHAR(1000) NOT NULL,
  split_strategy        VARCHAR(20) DEFAULT 'PARAGRAPH',
  split_config          TEXT,
  sync_interval_minutes INT DEFAULT 60,
  enabled               SMALLINT DEFAULT 0,
  last_file_id          BIGINT,
  last_content_hash     VARCHAR(64),
  last_sync_time        TIMESTAMP,
  last_status           VARCHAR(20),
  last_error            TEXT,
  create_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by             VARCHAR(50) DEFAULT 'system',
  update_by             VARCHAR(50) DEFAULT 'system',
  deleted               SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kb_connector_kb ON wgh_kb_connector(knowledge_base_id, deleted);
CREATE INDEX IF NOT EXISTS idx_kb_connector_due ON wgh_kb_connector(enabled, last_sync_time);
COMMENT ON TABLE wgh_kb_connector IS 'URL/HTTP 数据源连接器配置（单页抓取 + 增量同步）';
COMMENT ON COLUMN wgh_kb_connector.id IS '主键';
COMMENT ON COLUMN wgh_kb_connector.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_kb_connector.name IS '连接器名称';
COMMENT ON COLUMN wgh_kb_connector.url IS '抓取地址（仅 http/https，经 SSRF 防护校验）';
COMMENT ON COLUMN wgh_kb_connector.split_strategy IS '入库切分策略';
COMMENT ON COLUMN wgh_kb_connector.split_config IS '切分策略配置 JSON';
COMMENT ON COLUMN wgh_kb_connector.sync_interval_minutes IS '自动同步间隔（分钟），0 表示仅手动同步';
COMMENT ON COLUMN wgh_kb_connector.enabled IS '是否启用自动同步（1 启用 / 0 停用）';
COMMENT ON COLUMN wgh_kb_connector.last_file_id IS '最近一次同步生成/命中的知识库文件 ID';
COMMENT ON COLUMN wgh_kb_connector.last_content_hash IS '最近一次抓取正文 SHA-256';
COMMENT ON COLUMN wgh_kb_connector.last_sync_time IS '最近一次同步时间';
COMMENT ON COLUMN wgh_kb_connector.last_status IS '最近一次同步状态：SUCCESS / UNCHANGED / REMOVED / FAILED';
COMMENT ON COLUMN wgh_kb_connector.last_error IS '最近一次失败原因';
COMMENT ON COLUMN wgh_kb_connector.create_time IS '创建时间';
COMMENT ON COLUMN wgh_kb_connector.update_time IS '更新时间';
COMMENT ON COLUMN wgh_kb_connector.create_by IS '创建人';
COMMENT ON COLUMN wgh_kb_connector.update_by IS '更新人';
COMMENT ON COLUMN wgh_kb_connector.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 运行记录：每次同步一行，包含 HTTP 状态、内容哈希、命中的文件与耗时
CREATE TABLE IF NOT EXISTS wgh_kb_connector_run (
  id            BIGSERIAL PRIMARY KEY,
  connector_id  BIGINT NOT NULL,
  status        VARCHAR(20) NOT NULL,
  http_status   INT,
  file_id       BIGINT,
  content_hash  VARCHAR(64),
  chunk_count   INT DEFAULT 0,
  message       TEXT,
  duration_ms   BIGINT,
  started_at    TIMESTAMP,
  finished_at   TIMESTAMP,
  create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by     VARCHAR(50) DEFAULT 'system',
  update_by     VARCHAR(50) DEFAULT 'system',
  deleted       SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_kb_connector_run ON wgh_kb_connector_run(connector_id, id);
COMMENT ON TABLE wgh_kb_connector_run IS 'URL 连接器单次同步运行记录';
COMMENT ON COLUMN wgh_kb_connector_run.connector_id IS '所属连接器 ID';
COMMENT ON COLUMN wgh_kb_connector_run.status IS '运行状态：SUCCESS / UNCHANGED / REMOVED / FAILED';
COMMENT ON COLUMN wgh_kb_connector_run.http_status IS '抓取返回的 HTTP 状态码';
COMMENT ON COLUMN wgh_kb_connector_run.file_id IS '本次同步产生/命中的知识库文件 ID';
COMMENT ON COLUMN wgh_kb_connector_run.content_hash IS '本次正文 SHA-256';
COMMENT ON COLUMN wgh_kb_connector_run.chunk_count IS '文件 chunk 数（异步处理完成前可能为 0）';
COMMENT ON COLUMN wgh_kb_connector_run.message IS '结果说明 / 失败原因';
COMMENT ON COLUMN wgh_kb_connector_run.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN wgh_kb_connector_run.started_at IS '开始时间';
COMMENT ON COLUMN wgh_kb_connector_run.finished_at IS '结束时间';
COMMENT ON COLUMN wgh_kb_connector_run.create_time IS '创建时间';
COMMENT ON COLUMN wgh_kb_connector_run.update_time IS '更新时间';
COMMENT ON COLUMN wgh_kb_connector_run.create_by IS '创建人';
COMMENT ON COLUMN wgh_kb_connector_run.update_by IS '更新人';
COMMENT ON COLUMN wgh_kb_connector_run.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

COMMIT;
