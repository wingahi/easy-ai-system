-- ========================================================
-- 迁移：多租户阶段 3（平台内置 Prompt / Tool 显式范围标记 + RLS）
-- 状态：评审稿，**未执行**；不登记到 kustomize / run-migrations.sh。
-- 决策：内置/平台资源共享用显式 scope 标记（platform / tenant），
--       不再依赖 tenant_id = 'default' 隐式表达平台归属。
--
-- 回填规则：
--   wgh_prompt_template：builtin = 1 → scope = 'platform'，其余 'tenant'
--   wgh_tool           ：is_example = 1 → scope = 'platform'，其余 'tenant'
--   存量行由 ADD COLUMN ... DEFAULT 'tenant' 先落到 tenant，再按上述规则回填。
--
-- 策略：
--   wgh_app      ：可读 platform + 本租户；只能写 scope='tenant' 且 tenant_id 匹配 GUC
--   wgh_platform ：平台内置资源全量读写
--
-- 应用侧硬前置（未改代码前不得开启本组）：
--   1. MyBatis 租户插件当前会给这两张表追加 tenant_id 条件，会挡住 scope='platform' 行；
--      必须在插件层对“platform 范围”做显式放行（或这两张表改由 scope 条件驱动）。
--   2. 实体/服务写入需显式设置 scope；普通租户接口禁止创建 scope='platform'。
--   3. wgh_prompt_template.name 仍为全局唯一，平台模板与租户模板同名会冲突（已知限制）。
--
-- 回滚：单表 ALTER TABLE <t> DISABLE ROW LEVEL SECURITY;
--       scope 列保留（向后兼容），如需删除见 14_rls_rollback.sql 注释。
-- ========================================================

BEGIN;

-- 1. 增加 scope 列（幂等）
ALTER TABLE wgh_prompt_template
  ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'tenant';
ALTER TABLE wgh_tool
  ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'tenant';

-- 2. 范围约束（幂等）
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_prompt_template_scope'
      AND conrelid = 'wgh_prompt_template'::regclass
  ) THEN
    ALTER TABLE wgh_prompt_template
      ADD CONSTRAINT ck_prompt_template_scope CHECK (scope IN ('platform', 'tenant'));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_tool_scope'
      AND conrelid = 'wgh_tool'::regclass
  ) THEN
    ALTER TABLE wgh_tool
      ADD CONSTRAINT ck_tool_scope CHECK (scope IN ('platform', 'tenant'));
  END IF;
END $$;

COMMENT ON COLUMN wgh_prompt_template.scope IS '范围：platform 平台内置共享 / tenant 租户自建';
COMMENT ON COLUMN wgh_tool.scope IS '范围：platform 平台内置共享 / tenant 租户自建';

-- 3. 存量回填（只处理平台标记来源，重复执行安全）
UPDATE wgh_prompt_template SET scope = 'platform' WHERE builtin = 1 AND scope <> 'platform';
UPDATE wgh_tool SET scope = 'platform' WHERE is_example = 1 AND scope <> 'platform';

-- 4. RLS
ALTER TABLE wgh_prompt_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_prompt_template FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_tenant_scope ON wgh_prompt_template;
CREATE POLICY p_tenant_scope ON wgh_prompt_template
  FOR ALL TO wgh_app
  USING (scope = 'platform' OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (scope = 'tenant' AND tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS p_platform_manage ON wgh_prompt_template;
CREATE POLICY p_platform_manage ON wgh_prompt_template
  FOR ALL TO wgh_platform
  USING (true)
  WITH CHECK (true);

ALTER TABLE wgh_tool ENABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_tool FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_tenant_scope ON wgh_tool;
CREATE POLICY p_tenant_scope ON wgh_tool
  FOR ALL TO wgh_app
  USING (scope = 'platform' OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (scope = 'tenant' AND tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS p_platform_manage ON wgh_tool;
CREATE POLICY p_platform_manage ON wgh_tool
  FOR ALL TO wgh_platform
  USING (true)
  WITH CHECK (true);

COMMIT;
