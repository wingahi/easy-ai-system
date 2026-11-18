-- ========================================================
-- 迁移：大文件分片上传会话（wgh_upload_session）
-- 适用对象：2026-11-18 之前创建的存量库
-- 新库不要执行本脚本：rag-backend/sql/schema/01-schema.sql 已包含该表及其索引与注释
-- 执行顺序：先确认库已升级到基线（01-schema.sql 对应版本），再单独执行本脚本
--   1) 新库：psql -f sql/schema/01-schema.sql -f sql/seed/02-data.sql
--   2) 存量库：psql -f sql/migrations/20261118/03_upload_session.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 可重复执行（覆盖同名列注释）
-- 影响面：只新增一张表与两个索引，不改动既有表结构，不写入业务数据
-- ========================================================

BEGIN;

-- 分片上传会话：分片先按对象存储，complete 时合并为正式文件并复用入库链路
CREATE TABLE IF NOT EXISTS wgh_upload_session (
  id BIGSERIAL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL,
  upload_uuid VARCHAR(64) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(20) NOT NULL,
  file_size BIGINT NOT NULL,
  chunk_size INT NOT NULL,
  total_chunks INT NOT NULL,
  uploaded_chunks TEXT,
  status VARCHAR(20) DEFAULT 'UPLOADING',
  file_id BIGINT,
  split_strategy VARCHAR(20) DEFAULT 'PARAGRAPH',
  split_config TEXT,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_upload_session_uuid ON wgh_upload_session(upload_uuid);
CREATE INDEX IF NOT EXISTS idx_upload_session_status ON wgh_upload_session(status, update_time);
COMMENT ON TABLE wgh_upload_session IS '大文件分片上传会话（断点续传）';
COMMENT ON COLUMN wgh_upload_session.id IS '主键';
COMMENT ON COLUMN wgh_upload_session.knowledge_base_id IS '所属知识库 ID';
COMMENT ON COLUMN wgh_upload_session.upload_uuid IS '上传会话 UUID（同时作为分片存储目录名）';
COMMENT ON COLUMN wgh_upload_session.file_name IS '原始文件名';
COMMENT ON COLUMN wgh_upload_session.file_type IS '文件类型（扩展名大写）';
COMMENT ON COLUMN wgh_upload_session.file_size IS '文件总大小（字节）';
COMMENT ON COLUMN wgh_upload_session.chunk_size IS '分片大小（字节）';
COMMENT ON COLUMN wgh_upload_session.total_chunks IS '分片总数';
COMMENT ON COLUMN wgh_upload_session.uploaded_chunks IS '已完成分片序号 JSON 数组（断点续传依据）';
COMMENT ON COLUMN wgh_upload_session.status IS '会话状态：UPLOADING / COMPLETED / ABORTED';
COMMENT ON COLUMN wgh_upload_session.file_id IS '合并入库后生成的 wgh_knowledge_file.id';
COMMENT ON COLUMN wgh_upload_session.split_strategy IS '入库切分策略';
COMMENT ON COLUMN wgh_upload_session.split_config IS '切分策略配置 JSON';
COMMENT ON COLUMN wgh_upload_session.create_time IS '创建时间';
COMMENT ON COLUMN wgh_upload_session.update_time IS '更新时间';
COMMENT ON COLUMN wgh_upload_session.create_by IS '创建人';
COMMENT ON COLUMN wgh_upload_session.update_by IS '更新人';
COMMENT ON COLUMN wgh_upload_session.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

COMMIT;
