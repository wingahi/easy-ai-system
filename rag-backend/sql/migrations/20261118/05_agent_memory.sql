-- ========================================================
-- 迁移：检索式记忆（wgh_agent_memory）
-- 适用对象：2026-11-18 之前创建的存量库
-- 新库不要执行本脚本：rag-backend/sql/schema/01-schema.sql 已包含该表及其索引与注释
-- 执行顺序：先确认库已升级到基线（01-schema.sql 对应版本），再单独执行本脚本
--   1) 新库：psql -f sql/schema/01-schema.sql -f sql/seed/02-data.sql
--   2) 存量库：psql -f sql/migrations/20261118/05_agent_memory.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 覆盖同名注释，可重复执行；
--         不包含回填语句，重复执行不会产生或修改任何业务数据。
-- 影响面：只新增一张记忆业务表与三个索引，不改动既有表结构与数据。
-- 向量说明：向量单独存 wgh_agent_memory_vec（由 PgVectorEmbeddingStore 首次使用时自动建表，
--           维度取自记忆写入所用的 embedding 模型；metadata.scopeKey 做会话/任务隔离），
--           本脚本不预建向量表，避免维度与所选模型不一致。
-- ========================================================

BEGIN;

-- 记忆条目：一条摘要或一条事实，按 session/task 作用域隔离
CREATE TABLE IF NOT EXISTS wgh_agent_memory (
  id BIGSERIAL PRIMARY KEY,
  scope_type VARCHAR(16) NOT NULL,
  scope_id VARCHAR(64) NOT NULL,
  agent_id VARCHAR(64),
  memory_type VARCHAR(20) DEFAULT 'SUMMARY',
  content TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  embedding_id VARCHAR(64),
  source_message_id BIGINT,
  token_count INT DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE wgh_agent_memory IS '检索式记忆（会话/任务级；向量存 wgh_agent_memory_vec，按 scopeKey 隔离）';
COMMENT ON COLUMN wgh_agent_memory.scope_type IS '作用域类型：SESSION 会话级 / TASK 任务级';
COMMENT ON COLUMN wgh_agent_memory.scope_id IS '作用域 ID（sessionId 或 taskId）';
COMMENT ON COLUMN wgh_agent_memory.agent_id IS '产生该记忆的 Agent（单 Agent 链路为空）';
COMMENT ON COLUMN wgh_agent_memory.memory_type IS '记忆类型：SUMMARY 摘要 / FACT 事实';
COMMENT ON COLUMN wgh_agent_memory.content IS '记忆正文';
COMMENT ON COLUMN wgh_agent_memory.content_hash IS '正文 SHA-256，用于同作用域去重';
COMMENT ON COLUMN wgh_agent_memory.embedding_id IS 'wgh_agent_memory_vec 中的向量 ID（uuid）';
COMMENT ON COLUMN wgh_agent_memory.source_message_id IS '来源消息 ID（可选）';
COMMENT ON COLUMN wgh_agent_memory.token_count IS '正文估算 Token 数';
COMMENT ON COLUMN wgh_agent_memory.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 同作用域内正文唯一，避免重复写入同一条记忆
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_scope_hash
  ON wgh_agent_memory (scope_type, scope_id, content_hash)
  WHERE deleted = 0;

-- 按作用域按时间倒序取最近记忆
CREATE INDEX IF NOT EXISTS idx_agent_memory_scope_time
  ON wgh_agent_memory (scope_type, scope_id, create_time DESC)
  WHERE deleted = 0;

-- 按 Agent 维度读取记忆
CREATE INDEX IF NOT EXISTS idx_agent_memory_agent
  ON wgh_agent_memory (agent_id, create_time DESC)
  WHERE deleted = 0;

COMMIT;
