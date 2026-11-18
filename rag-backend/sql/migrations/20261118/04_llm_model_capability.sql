-- ========================================================
-- 迁移：LLM 模型能力画像（wgh_llm_model 增加能力字段）
-- 适用对象：2026-11-18 之前创建的存量库
-- 新库不要执行本脚本：rag-backend/sql/schema/01-schema.sql 已包含这两个列、索引与注释
-- 执行顺序：先确认库已升级到基线（01-schema.sql 对应版本），再单独执行本脚本
--   1) 新库：psql -f sql/schema/01-schema.sql -f sql/seed/02-data.sql
--   2) 存量库：psql -f sql/migrations/20261118/04_llm_model_capability.sql
-- 幂等性：
--   - ADD COLUMN / CREATE INDEX 均使用 IF NOT EXISTS，可重复执行；
--   - COMMENT 覆盖同名列注释，可重复执行；
--   - 回填只处理仍为默认档位 3 的行，且按 max_context_tokens 计算，
--     重复执行结果一致，不会覆盖人工设定过的档位。
-- 影响面：只给既有表增加两个带默认值的列与一个索引，不改变既有列、不删除数据；
--         回填仅更新 wgh_llm_model 的能力档位，不触碰其他表。
-- 说明：capability_tier 越大代表能力越强（1~5），仅用于故障转移时的降级判定，
--       不参与计费与业务路由；supports_tools 表示是否支持 function calling。
-- ========================================================

BEGIN;

-- 能力档位：1 基础 / 2 轻量 / 3 标准（默认）/ 4 增强 / 5 旗舰
ALTER TABLE wgh_llm_model
  ADD COLUMN IF NOT EXISTS capability_tier SMALLINT NOT NULL DEFAULT 3;

-- 是否支持 function calling（0 否 / 1 是）
ALTER TABLE wgh_llm_model
  ADD COLUMN IF NOT EXISTS supports_tools SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN wgh_llm_model.capability_tier IS '能力档位（1~5，越大越强；故障转移只允许降级不升级）';
COMMENT ON COLUMN wgh_llm_model.supports_tools IS '是否支持 function calling（0 否 / 1 是）';

-- 按上下文窗口回填档位：仅处理仍为默认值 3 的行，重复执行结果一致
UPDATE wgh_llm_model
SET capability_tier = CASE
    WHEN max_context_tokens >= 128000 THEN 5
    WHEN max_context_tokens >= 32000  THEN 4
    WHEN max_context_tokens >= 8000   THEN 3
    ELSE 2
  END
WHERE deleted = 0
  AND capability_tier = 3
  AND max_context_tokens IS NOT NULL;

-- 故障转移链路查询：同组按优先级取候选，再按能力档位过滤降级
CREATE INDEX IF NOT EXISTS idx_llm_model_route_capability
  ON wgh_llm_model (route_group, route_priority, capability_tier)
  WHERE deleted = 0 AND status = 1;

COMMIT;
