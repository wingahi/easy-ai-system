-- ========================================================
-- 迁移：后台作业表补充链路 traceId（wgh_execution_job.trace_id）
-- 适用对象：2026-11-22 之前创建的存量库
-- 新库说明：sql/schema/01-schema.sql 已包含本列，新建库无需再执行本脚本
-- 执行顺序：在 20261120/01_execution_job.sql 之后单独执行
--   存量库：psql -f sql/migrations/20261122/01_execution_job_trace.sql
-- 幂等性：ADD COLUMN IF NOT EXISTS / COMMENT 覆盖同名注释，可重复执行；
--         不包含回填语句，不修改任何业务数据。
-- 影响面：只新增一个可空列，不改动既有列、索引与约束。
-- 说明：列值来自作业提交线程的 MDC traceId（Micrometer Tracing）；
--       系统周期作业无提交请求时为空，执行实例按 execution_id 关联。
-- ========================================================

BEGIN;

ALTER TABLE wgh_execution_job
  ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

COMMENT ON COLUMN wgh_execution_job.trace_id IS '提交时链路 traceId，用于跨实例按 traceId 关联提交与执行日志';

COMMIT;
