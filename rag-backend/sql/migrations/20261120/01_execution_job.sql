-- ========================================================
-- 迁移：后台作业表（wgh_execution_job）
-- 适用对象：2026-11-20 之前创建的存量库
-- 新库说明：sql/schema/01-schema.sql 已包含本表与全部索引，新建库无需再执行本脚本
-- 执行顺序：先确认库已升级到既有基线，再单独执行本脚本
--   存量库：psql -f sql/migrations/20261120/01_execution_job.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 覆盖同名注释，可重复执行；
--         不包含回填语句，重复执行不会产生或修改任何业务数据。
-- 影响面：只新增一张调度表与五个索引，不改动既有表结构与数据。
-- 说明：P3 只落作业表；租户策略表（wgh_tenant_schedule_policy）与集群运行槽位
--       （wgh_tenant_runtime_slot）属多租户阶段（S2），本轮不建。
-- ========================================================

BEGIN;

-- 后台作业：PENDING → LEASED → RUNNING → SUCCEEDED / FAILED / DEAD / CANCELLED
CREATE TABLE IF NOT EXISTS wgh_execution_job (
  id BIGSERIAL PRIMARY KEY,
  execution_id VARCHAR(64) NOT NULL,
  job_type VARCHAR(64) NOT NULL,
  lane VARCHAR(16) NOT NULL DEFAULT 'BACKGROUND',
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  owner_user_id VARCHAR(64),
  biz_key VARCHAR(200),
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload_schema_version INT NOT NULL DEFAULT 1,
  priority INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  scheduled_at TIMESTAMP NOT NULL DEFAULT now(),
  not_before TIMESTAMP,
  lease_owner VARCHAR(64),
  start_time TIMESTAMP,
  lease_expire_time TIMESTAMP,
  heartbeat_time TIMESTAMP,
  attempt INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  timeout_seconds INT,
  cancel_requested SMALLINT NOT NULL DEFAULT 0,
  cancel_reason VARCHAR(200),
  error_code VARCHAR(64),
  error_message TEXT,
  result_json JSONB,
  finish_time TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE wgh_execution_job IS '后台作业（持久化 + 租约；核对与重试的唯一事实源）';
COMMENT ON COLUMN wgh_execution_job.execution_id IS '对外执行标识（UUID），门面查询/取消/订阅使用';
COMMENT ON COLUMN wgh_execution_job.job_type IS '作业类型，对应 JobHandler#jobType()';
COMMENT ON COLUMN wgh_execution_job.lane IS '执行车道：BACKGROUND 后台 / MAINTENANCE 维护';
COMMENT ON COLUMN wgh_execution_job.tenant_id IS '租户标识（单租户为 default）';
COMMENT ON COLUMN wgh_execution_job.owner_user_id IS '发起人用户标识（系统任务为空）';
COMMENT ON COLUMN wgh_execution_job.biz_key IS '业务幂等键；同 job_type + biz_key 只保留一条';
COMMENT ON COLUMN wgh_execution_job.payload_json IS '作业负载 JSON';
COMMENT ON COLUMN wgh_execution_job.payload_schema_version IS '负载结构版本；不兼容置 DEAD(INCOMPATIBLE_PAYLOAD)';
COMMENT ON COLUMN wgh_execution_job.priority IS '优先级，数值越大越优先';
COMMENT ON COLUMN wgh_execution_job.status IS '状态：PENDING/LEASED/RUNNING/SUCCEEDED/FAILED/DEAD/CANCELLED';
COMMENT ON COLUMN wgh_execution_job.scheduled_at IS '期望开始时间';
COMMENT ON COLUMN wgh_execution_job.not_before IS '最早可领取时间（重试退避 / 单飞锁等待）';
COMMENT ON COLUMN wgh_execution_job.lease_owner IS '租约持有者实例标识';
COMMENT ON COLUMN wgh_execution_job.start_time IS '首次进入 RUNNING 的时间';
COMMENT ON COLUMN wgh_execution_job.lease_expire_time IS '租约过期时间；过期后可被其它实例回收';
COMMENT ON COLUMN wgh_execution_job.heartbeat_time IS '最近一次续租时间';
COMMENT ON COLUMN wgh_execution_job.attempt IS '已尝试次数（领取时 +1）';
COMMENT ON COLUMN wgh_execution_job.max_attempts IS '最大尝试次数（含首次）';
COMMENT ON COLUMN wgh_execution_job.timeout_seconds IS '单次执行超时秒数（空=车道默认值）';
COMMENT ON COLUMN wgh_execution_job.cancel_requested IS '是否已请求取消（0 否 / 1 是）';
COMMENT ON COLUMN wgh_execution_job.cancel_reason IS '取消原因';
COMMENT ON COLUMN wgh_execution_job.error_code IS '失败错误码（ExecutionErrorCodes）';
COMMENT ON COLUMN wgh_execution_job.error_message IS '失败原因';
COMMENT ON COLUMN wgh_execution_job.result_json IS '执行结果 JSON（如工作流 runId）';
COMMENT ON COLUMN wgh_execution_job.finish_time IS '终态时间';
COMMENT ON COLUMN wgh_execution_job.version IS '状态迁移版本号，每次迁移 +1';
COMMENT ON COLUMN wgh_execution_job.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 执行标识唯一：门面按 execution_id 查询/取消
CREATE UNIQUE INDEX IF NOT EXISTS uk_exec_job_execution_id
  ON wgh_execution_job(execution_id);

-- 业务幂等：同类型同幂等键只允许一条（未删除）
CREATE UNIQUE INDEX IF NOT EXISTS uk_exec_job_biz
  ON wgh_execution_job(job_type, biz_key)
  WHERE biz_key IS NOT NULL AND deleted = 0;

-- 领取：按车道 + 状态 + 计划时间取最早作业（配合 ORDER BY priority, scheduled_at）
CREATE INDEX IF NOT EXISTS idx_exec_job_claim
  ON wgh_execution_job(lane, status, scheduled_at)
  WHERE deleted = 0;

-- 租户维度聚合（准入/管理端）
CREATE INDEX IF NOT EXISTS idx_exec_job_tenant
  ON wgh_execution_job(tenant_id, status)
  WHERE deleted = 0;

-- 租约回收：扫描已过期租约
CREATE INDEX IF NOT EXISTS idx_exec_job_lease
  ON wgh_execution_job(status, lease_expire_time)
  WHERE deleted = 0;

COMMIT;
