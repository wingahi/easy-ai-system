-- ========================================================
-- 迁移：配额扣费流水表（wgh_quota_charge）
-- 适用对象：2026-11-20 之前创建的存量库
-- 新库说明：sql/schema/01-schema.sql 已包含本表与全部索引，新建库无需再执行本脚本
-- 执行顺序：先确认库已升级到既有基线，再单独执行本脚本
--   存量库：psql -f sql/migrations/20261120/02_quota_charge.sql
-- 幂等性：建表/建索引均使用 IF NOT EXISTS；COMMENT 覆盖同名注释，可重复执行；
--         不包含回填语句，重复执行不会产生或修改任何业务数据。
-- 影响面：只新增一张配额扣费流水表与三个索引，不改动既有表结构与数据。
-- 状态机：CHARGED（已扣未决）→ SETTLED（执行已开始，扣费生效）
--                         └→ REFUNDED（已退款）
--          CHARGED → REFUND_FAILED（退款失败，等待退避重试）→ REFUNDED / DEAD（人工处理）
-- 说明：本脚本只创建对象，不执行；需在两个库（wgh_rag / wgh_rag_fresh）分别由我方批准后执行。
-- ========================================================

BEGIN;

-- 配额扣费流水：一次对话扣费一行，charge_id 是退款幂等键
CREATE TABLE IF NOT EXISTS wgh_quota_charge (
  id BIGSERIAL PRIMARY KEY,
  charge_id VARCHAR(64) NOT NULL,
  username VARCHAR(50) NOT NULL,
  period_key VARCHAR(10) NOT NULL,
  amount INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'CHARGED',
  window_id BIGINT,
  session_id BIGINT,
  task_id VARCHAR(64),
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP,
  last_error VARCHAR(500),
  finish_time TIMESTAMP,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(50) DEFAULT 'system',
  update_by VARCHAR(50) DEFAULT 'system',
  deleted SMALLINT DEFAULT 0
);

COMMENT ON TABLE wgh_quota_charge IS '配额扣费流水（退款幂等与补偿的唯一事实源）';
COMMENT ON COLUMN wgh_quota_charge.charge_id IS '扣费标识（UUID）；退款幂等键，全局唯一';
COMMENT ON COLUMN wgh_quota_charge.username IS '用户名';
COMMENT ON COLUMN wgh_quota_charge.period_key IS '扣费时的周期键（日=yyyy-MM-dd，月=yyyy-MM）；退款按此键回退，避免跨周期退错行';
COMMENT ON COLUMN wgh_quota_charge.amount IS '本次扣减的对话条数（当前固定为 1）';
COMMENT ON COLUMN wgh_quota_charge.status IS '状态：CHARGED 已扣未决 / SETTLED 执行已开始 / REFUNDED 已退款 / REFUND_FAILED 退款失败待重试 / DEAD 补偿耗尽待人工';
COMMENT ON COLUMN wgh_quota_charge.window_id IS '关联聊天窗口';
COMMENT ON COLUMN wgh_quota_charge.session_id IS '关联会话（会话创建后回填）';
COMMENT ON COLUMN wgh_quota_charge.task_id IS '关联任务标识（预留：后台作业/异步任务接入时使用）';
COMMENT ON COLUMN wgh_quota_charge.retry_count IS '退款补偿已重试次数';
COMMENT ON COLUMN wgh_quota_charge.next_retry_at IS '下次可补偿时间（退避）';
COMMENT ON COLUMN wgh_quota_charge.last_error IS '最近一次退款失败原因（截断）';
COMMENT ON COLUMN wgh_quota_charge.finish_time IS '终态时间（REFUNDED / DEAD）';
COMMENT ON COLUMN wgh_quota_charge.deleted IS '逻辑删除（0 未删除 / 1 已删除）';

-- 扣费标识唯一：退款幂等的数据库兜底
CREATE UNIQUE INDEX IF NOT EXISTS uk_quota_charge_id
  ON wgh_quota_charge(charge_id);

-- 补偿扫描：已扣未决 / 退款失败
CREATE INDEX IF NOT EXISTS idx_quota_charge_scan
  ON wgh_quota_charge(status, create_time)
  WHERE deleted = 0;

-- 退避重试：按 next_retry_at 取到期流水
CREATE INDEX IF NOT EXISTS idx_quota_charge_retry
  ON wgh_quota_charge(status, next_retry_at)
  WHERE deleted = 0;

-- 对账：按用户与周期键核对扣费/退款
CREATE INDEX IF NOT EXISTS idx_quota_charge_user
  ON wgh_quota_charge(username, period_key)
  WHERE deleted = 0;

COMMIT;
