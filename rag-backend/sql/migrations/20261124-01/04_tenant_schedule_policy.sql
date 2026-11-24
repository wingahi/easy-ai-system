-- ========================================================
-- 迁移：租户调度策略（wgh_tenant_schedule_policy）与集群运行槽（wgh_tenant_runtime_slot）
-- 适用对象：2026-11-24 之前创建的存量库（wgh_rag_dev / wgh_rag_test 均适用）
-- 新库说明：sql/schema/01-schema.sql 已包含两张表与全部索引，新建库无需再执行本脚本
-- 执行顺序：在 20261124/01_scheduler_instance.sql 之后单独执行
--   存量库：psql -f sql/migrations/20261124-01/04_tenant_schedule_policy.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 覆盖同名注释，可重复执行；
--         不包含回填语句，重复执行不会产生或修改任何业务数据。
-- 影响面：只新增两张调度表与一个唯一约束，不改动既有表结构与数据。
-- 说明：tenant_id 沿用 wgh_execution_job 的 VARCHAR(64) 口径（单租户为 default），
--       与设计稿的 BIGINT 不同，避免同一套租户标识在调度域内出现两种类型。
--       策略表不可用时调度器按代码默认值运行，不影响作业领取；槽位表不可用时失败开放。
-- ========================================================

BEGIN;

-- 租户调度策略：按 (tenant_id, lane) 唯一；只约束准入，不配置线程池大小
CREATE TABLE IF NOT EXISTS wgh_tenant_schedule_policy (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  lane VARCHAR(16) NOT NULL,
  enabled SMALLINT NOT NULL DEFAULT 1,
  max_concurrent INT NOT NULL DEFAULT 2,
  max_queue INT NOT NULL DEFAULT 20,
  weight INT NOT NULL DEFAULT 10,
  priority_boost INT NOT NULL DEFAULT 0,
  reject_policy VARCHAR(16) NOT NULL DEFAULT 'REJECT',
  queue_timeout_seconds INT NOT NULL DEFAULT 300,
  daily_job_limit INT NOT NULL DEFAULT 0,
  rate_per_minute INT NOT NULL DEFAULT 0,
  time_window_json JSONB,
  remark VARCHAR(500),
  version INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE wgh_tenant_schedule_policy IS '租户车道调度策略（并发/排队/权重/拒绝方式；不追溯已提交作业）';
COMMENT ON COLUMN wgh_tenant_schedule_policy.tenant_id IS '租户标识（与 wgh_execution_job.tenant_id 同口径，单租户为 default）';
COMMENT ON COLUMN wgh_tenant_schedule_policy.lane IS '执行车道：INTERACTIVE / BACKGROUND / MAINTENANCE';
COMMENT ON COLUMN wgh_tenant_schedule_policy.enabled IS '是否启用；0 时回退 wgh.scheduler.default-policy';
COMMENT ON COLUMN wgh_tenant_schedule_policy.max_concurrent IS '运行中并发上限（不含排队）';
COMMENT ON COLUMN wgh_tenant_schedule_policy.max_queue IS '等待中排队上限';
COMMENT ON COLUMN wgh_tenant_schedule_policy.weight IS '公平调度权重（1-100），只影响排队顺序，不抢占';
COMMENT ON COLUMN wgh_tenant_schedule_policy.priority_boost IS '作业基础优先级加成';
COMMENT ON COLUMN wgh_tenant_schedule_policy.reject_policy IS '拒绝策略：REJECT 立即拒绝 / QUEUE 允许排队';
COMMENT ON COLUMN wgh_tenant_schedule_policy.queue_timeout_seconds IS '排队超时秒数，超时转失败';
COMMENT ON COLUMN wgh_tenant_schedule_policy.daily_job_limit IS '每日作业数上限（0=不限）';
COMMENT ON COLUMN wgh_tenant_schedule_policy.rate_per_minute IS '每分钟提交上限（0=不限）';
COMMENT ON COLUMN wgh_tenant_schedule_policy.time_window_json IS '允许运行时间窗 JSON；为空表示全天';
COMMENT ON COLUMN wgh_tenant_schedule_policy.version IS '乐观锁版本，管理端更新必须携带';

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_schedule_policy
  ON wgh_tenant_schedule_policy(tenant_id, lane)
  WHERE deleted = 0;

-- 集群运行槽：执行前原子 +1，结束后 -1，周期性用作业表实际在跑数校正
CREATE TABLE IF NOT EXISTS wgh_tenant_runtime_slot (
  tenant_id VARCHAR(64) NOT NULL,
  lane VARCHAR(16) NOT NULL,
  running INT NOT NULL DEFAULT 0,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, lane)
);

COMMENT ON TABLE wgh_tenant_runtime_slot IS '租户集群运行槽（多副本租户并发约束；与作业表实际在跑数的偏差即 slot.drift）';
COMMENT ON COLUMN wgh_tenant_runtime_slot.tenant_id IS '租户标识';
COMMENT ON COLUMN wgh_tenant_runtime_slot.lane IS '执行车道';
COMMENT ON COLUMN wgh_tenant_runtime_slot.running IS '当前占用槽位数；进程异常退出造成的泄漏由周期对账校正';

COMMIT;
