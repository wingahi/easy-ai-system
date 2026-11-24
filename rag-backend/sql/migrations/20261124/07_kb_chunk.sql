-- 2026-11-24 / 06
-- 检索 V0：chunk 事实层 wgh_kb_chunk（幂等，仅新增，不修改既有对象）
-- 事实层默认关闭（wgh.vector.fact-layer-enabled=false），开启后由入库链路双写。
-- 适用库：wgh_rag_dev（本次仅在该库执行）；wgh_rag_prod / wgh_rag_test 不动。

CREATE TABLE IF NOT EXISTS wgh_kb_chunk (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  kb_id BIGINT NOT NULL,
  file_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  embedding_id VARCHAR(64),
  embedding_model_id BIGINT,
  dimension INT,
  vector_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  version INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);
COMMENT ON TABLE wgh_kb_chunk IS '知识库 chunk 事实层（原文+元数据唯一副本；向量/关键词索引可重建）';
COMMENT ON COLUMN wgh_kb_chunk.tenant_id IS '租户标识（单租户为 default，与调度侧 tenant_id 同口径）';
COMMENT ON COLUMN wgh_kb_chunk.metadata_json IS 'chunk 元数据 JSON（检索过滤与来源定位）';
COMMENT ON COLUMN wgh_kb_chunk.embedding_id IS '派生索引中的向量 ID；写入成功前为空';
COMMENT ON COLUMN wgh_kb_chunk.vector_status IS 'PENDING / SYNCED / STALE（双写一致性对账依据）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_chunk
  ON wgh_kb_chunk(kb_id, file_id, chunk_index) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_tenant
  ON wgh_kb_chunk(tenant_id, kb_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_status
  ON wgh_kb_chunk(vector_status) WHERE deleted = 0;
