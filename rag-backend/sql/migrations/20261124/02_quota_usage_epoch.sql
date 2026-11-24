-- ========================================================
-- 迁移：配额计数纪元（usage_epoch），让退款精确作用于「自己那一笔」
-- 适用对象：已执行过 20261120/02_quota_charge.sql 的存量库
-- 新库说明：sql/schema/01-schema.sql 已包含本列，新建库无需再执行本脚本
-- 执行顺序：先确认 wgh_quota_usage / wgh_quota_charge 已存在，再单独执行本脚本
--   存量库：psql -f sql/migrations/20261124/02_quota_usage_epoch.sql
-- 幂等性：ADD COLUMN IF NOT EXISTS；COMMENT 覆盖同名注释，可重复执行；
--         不包含回填更新语句，重复执行不会修改任何业务数据。
-- 影响面：只新增两列（可空/带默认值），不改动既有列与索引。
-- 语义：
--   wgh_quota_usage.usage_epoch   计数纪元；管理端「重置用量」时 +1，用于区分重置前后的扣费
--   wgh_quota_charge.usage_epoch  扣费时的计数纪元快照；退款减一必须与当期 usage_epoch 相等
--                                 存量流水为 NULL，表示不做纪元校验（保持既有退款行为）
-- ========================================================

BEGIN;

ALTER TABLE wgh_quota_usage
  ADD COLUMN IF NOT EXISTS usage_epoch BIGINT DEFAULT 0;
COMMENT ON COLUMN wgh_quota_usage.usage_epoch IS '计数纪元：管理端重置用量时 +1；退款按扣费时的纪元精确回退';

ALTER TABLE wgh_quota_charge
  ADD COLUMN IF NOT EXISTS usage_epoch BIGINT;
COMMENT ON COLUMN wgh_quota_charge.usage_epoch IS '扣费时的计数纪元快照；NULL 表示存量流水，不做纪元校验';

COMMIT;
