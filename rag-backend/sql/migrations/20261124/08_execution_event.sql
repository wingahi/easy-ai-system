-- ========================================================
-- 迁移：执行事件表（wgh_execution_event）
-- 适用对象：2026-11-24 之前创建的存量库（wgh_rag_dev / wgh_rag_test 均适用）
-- 新库说明：sql/schema/01-schema.sql 已包含该表与索引，新建库无需再执行本脚本
-- 执行顺序：在 20261124/04_tenant_schedule_policy.sql 之后、其它改造线的 05-07 脚本之后执行
--   存量库：psql -f sql/migrations/20261124/08_execution_event.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 覆盖同名注释，可重复执行；
--         不包含回填语句，重复执行不会产生或修改任何业务数据。
-- 影响面：只新增一张事件表与两个索引；调度中心未启用时不写入。
-- 说明：事件表是跨实例/断线重连（Last-Event-ID）的重放事实源，只增不改，
--       按 wgh.scheduler.event-table-retention（默认 7d）由调度层低频清理。
-- ========================================================

BEGIN;

CREATE TABLE IF NOT EXISTS wgh_execution_event (
  id BIGSERIAL PRIMARY KEY,
  execution_id VARCHAR(64) NOT NULL,
  seq BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE wgh_execution_event IS '执行事件（跨实例 SSE 重放事实源；只增不改，按保留期清理）';
COMMENT ON COLUMN wgh_execution_event.execution_id IS '执行标识（wgh_execution_job.execution_id / 交互式执行 ID）';
COMMENT ON COLUMN wgh_execution_event.seq IS '执行内单调递增序号，从 1 开始；重连时 Last-Event-ID';
COMMENT ON COLUMN wgh_execution_event.event_type IS '事件类型：STARTED/PROGRESS/DELTA/TOOL/COMPLETED/CANCELLED/FAILED 等';
COMMENT ON COLUMN wgh_execution_event.payload_json IS '事件负载 JSON';

CREATE UNIQUE INDEX IF NOT EXISTS uk_exec_event_execution_seq
  ON wgh_execution_event(execution_id, seq);

CREATE INDEX IF NOT EXISTS idx_exec_event_create_time
  ON wgh_execution_event(create_time);

COMMIT;
