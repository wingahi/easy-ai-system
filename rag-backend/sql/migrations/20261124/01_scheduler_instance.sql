-- ========================================================
-- 迁移：调度中心实例注册表（wgh_scheduler_instance）与租约归属索引
-- 适用对象：2026-11-24 之前创建的存量库（wgh_rag_dev / wgh_rag_test 均适用）
-- 新库说明：sql/schema/01-schema.sql 已包含本表与全部索引，新建库无需再执行本脚本
-- 执行顺序：在 20261120/01_execution_job.sql、20261122/01_execution_job_trace.sql 之后单独执行
--   存量库：psql -f sql/migrations/20261124/01_scheduler_instance.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 覆盖同名注释，可重复执行；
--         不包含回填语句，重复执行不会产生或修改任何业务数据。
-- 影响面：只新增一张实例注册表、两个索引，不改动既有表结构与数据。
-- 说明：实例表只记录存活心跳与车道快照，不是待执行数据的存储；
--       多实例协调仍以 wgh_execution_job 的状态与租约为唯一事实源。
-- ========================================================

BEGIN;

-- 调度实例：每个进程/副本一行，心跳过期即判定失联
CREATE TABLE IF NOT EXISTS wgh_scheduler_instance (
  instance_id VARCHAR(64) PRIMARY KEY,
  started_at TIMESTAMP NOT NULL DEFAULT now(),
  last_heartbeat TIMESTAMP NOT NULL DEFAULT now(),
  stopped_at TIMESTAMP,
  lanes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  version INT NOT NULL DEFAULT 0,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE wgh_scheduler_instance IS '调度实例注册与心跳（存活判定；失联实例持有的作业会被提前回收）';
COMMENT ON COLUMN wgh_scheduler_instance.instance_id IS '实例标识（配置 instance-id，缺省 HOSTNAME-pid；与 wgh_execution_job.lease_owner 同口径）';
COMMENT ON COLUMN wgh_scheduler_instance.started_at IS '本次进程启动时间；同实例重启后覆盖';
COMMENT ON COLUMN wgh_scheduler_instance.last_heartbeat IS '最近一次心跳时间（数据库时间）；超过存活阈值即判定失联';
COMMENT ON COLUMN wgh_scheduler_instance.stopped_at IS '实例下线标记时间（失联检测或主动下线）；非空时其残留作业会被回收';
COMMENT ON COLUMN wgh_scheduler_instance.lanes_json IS '车道运行时快照（在途/排队/线程数），仅供管理端观测';
COMMENT ON COLUMN wgh_scheduler_instance.version IS '心跳/状态更新版本号，每次更新 +1';

-- 失联扫描：按最近心跳筛选
CREATE INDEX IF NOT EXISTS idx_sched_instance_heartbeat
  ON wgh_scheduler_instance(last_heartbeat);

-- 失联实例作业回收：按租约持有者 + 活跃状态筛选
CREATE INDEX IF NOT EXISTS idx_exec_job_lease_owner
  ON wgh_execution_job(lease_owner, status)
  WHERE deleted = 0 AND lease_owner IS NOT NULL;

-- 作业-业务对账：按类型 + 最近更新时间扫描
CREATE INDEX IF NOT EXISTS idx_exec_job_type_update
  ON wgh_execution_job(job_type, update_time DESC)
  WHERE deleted = 0;

COMMIT;
