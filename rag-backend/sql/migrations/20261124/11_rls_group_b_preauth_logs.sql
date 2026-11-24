-- ========================================================
-- 迁移：多租户阶段 3（RLS B 组：免登录/预认证/日志 4 表）
-- 状态：评审稿，**未执行**；不登记到 kustomize / run-migrations.sh。
-- 前置：09_rls_roles.sql 已执行。
--
-- 表与策略：
--   wgh_share_link  : wgh_app 租户隔离 + wgh_preauth 只读 + wgh_platform 只读
--   wgh_api_key     : wgh_app 租户隔离 + wgh_preauth 只读 + wgh_platform 只读
--   wgh_api_call_log: wgh_app 租户隔离（写）+ wgh_platform 只读
--   wgh_audit_log   : wgh_app 租户隔离（写）+ wgh_platform 只读
--
-- 应用侧硬前置（未改代码前不得开启本组）：
--   1. wgh_api_call_log：ApiKeyAuthenticationFilter 必须在 TenantContext.clear() 之前写日志，
--      或在写日志事务内显式 set_config('app.tenant_id', key.tenantId, true)。
--   2. wgh_audit_log：登录成功/失败审计在认证上下文建立前发生，写库前必须显式按目标用户租户
--      设置 GUC；异步审计依赖 AuthContextPropagation 恢复的 TenantContext。
--   3. 免登录链路先用 SET LOCAL ROLE wgh_preauth 查 token/key，解析出 tenant_id 后，
--      在同一或后续事务里 SET LOCAL ROLE wgh_app 并设置对应 GUC 再做业务读写；
--      禁止把 wgh_preauth 用于业务表，也禁止给 wgh_app 开通配 GUC 例外。
--
-- 回滚：单表 ALTER TABLE <t> DISABLE ROW LEVEL SECURITY; 或执行 14_rls_rollback.sql。
-- ========================================================

BEGIN;

-- --------------------------------------------------------
-- wgh_share_link
-- --------------------------------------------------------
ALTER TABLE wgh_share_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_share_link FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_tenant_isolation ON wgh_share_link;
CREATE POLICY p_tenant_isolation ON wgh_share_link
  FOR ALL TO wgh_app
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS p_preauth_read ON wgh_share_link;
CREATE POLICY p_preauth_read ON wgh_share_link
  FOR SELECT TO wgh_preauth
  USING (true);

DROP POLICY IF EXISTS p_platform_read ON wgh_share_link;
CREATE POLICY p_platform_read ON wgh_share_link
  FOR SELECT TO wgh_platform
  USING (true);

-- --------------------------------------------------------
-- wgh_api_key
-- --------------------------------------------------------
ALTER TABLE wgh_api_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_api_key FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_tenant_isolation ON wgh_api_key;
CREATE POLICY p_tenant_isolation ON wgh_api_key
  FOR ALL TO wgh_app
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS p_preauth_read ON wgh_api_key;
CREATE POLICY p_preauth_read ON wgh_api_key
  FOR SELECT TO wgh_preauth
  USING (true);

DROP POLICY IF EXISTS p_platform_read ON wgh_api_key;
CREATE POLICY p_platform_read ON wgh_api_key
  FOR SELECT TO wgh_platform
  USING (true);

-- --------------------------------------------------------
-- wgh_api_call_log
-- --------------------------------------------------------
ALTER TABLE wgh_api_call_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_api_call_log FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_tenant_isolation ON wgh_api_call_log;
CREATE POLICY p_tenant_isolation ON wgh_api_call_log
  FOR ALL TO wgh_app
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS p_platform_read ON wgh_api_call_log;
CREATE POLICY p_platform_read ON wgh_api_call_log
  FOR SELECT TO wgh_platform
  USING (true);

-- --------------------------------------------------------
-- wgh_audit_log
-- --------------------------------------------------------
ALTER TABLE wgh_audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_audit_log FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_tenant_isolation ON wgh_audit_log;
CREATE POLICY p_tenant_isolation ON wgh_audit_log
  FOR ALL TO wgh_app
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS p_platform_read ON wgh_audit_log;
CREATE POLICY p_platform_read ON wgh_audit_log
  FOR SELECT TO wgh_platform
  USING (true);

COMMIT;
