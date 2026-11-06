-- ========================================================
-- 20261104 schema 回填：面向存量库
-- 内容与 schema/01-schema.sql 中已有 DDL 保持字面一致（未新增、未改类型、未加 COMMENT）。
-- schema/01-schema.sql 已包含同内容：空库首次初始化（compose initdb 01→02）无需执行本文件。
-- 存量库若只跑 migrations，需执行本文件补齐下列列/索引。
-- 全部语句带 IF NOT EXISTS，可重复执行。
-- ========================================================

-- wgh_chat_message.status
ALTER TABLE wgh_chat_message ADD COLUMN IF NOT EXISTS status VARCHAR(20);

-- wgh_knowledge_file.content_hash + 索引
ALTER TABLE wgh_knowledge_file ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_kfile_kb_content_hash ON wgh_knowledge_file(knowledge_base_id, content_hash);

-- wgh_knowledge_base 重建状态与待切换嵌入模型
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS rebuild_status VARCHAR(20) DEFAULT '';
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS rebuild_total INT DEFAULT 0;
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS rebuild_processed INT DEFAULT 0;
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS pending_collection VARCHAR(100);
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS pending_embedding_model_id BIGINT;
