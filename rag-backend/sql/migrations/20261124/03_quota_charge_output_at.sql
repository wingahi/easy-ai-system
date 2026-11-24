-- 扣费流水「已产出」持久化标记
-- 背景：产出事实原先只存在于进程内（outputDelivered），进程在结算落库前崩溃会留下
--       CHARGED 流水，被 QUOTA_REFUND 补偿作业按「未产出」退款，造成已交付内容欠计费。
-- 口径：output_at 非空的流水一律不自动退款；settle 时与 status 同一条 UPDATE 写入。
ALTER TABLE wgh_quota_charge ADD COLUMN IF NOT EXISTS output_at TIMESTAMP;
COMMENT ON COLUMN wgh_quota_charge.output_at IS '首个有效产出落库时间（UTC）；非空表示已产出，补偿作业不得退款';
