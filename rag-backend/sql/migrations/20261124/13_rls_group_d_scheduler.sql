-- ========================================================
-- 迁移：多租户阶段 3（RLS D 组：调度 3 表）
-- 状态：评审稿，**未执行**；不登记到 kustomize / run-migrations.sh。
-- 决策：调度表使用专用 wgh_scheduler 角色跨租户访问，不做按租户分片。
--
-- 覆盖：
--   wgh_execution_job          ：wgh_app 租户隔离 + wgh_scheduler 全量
--   wgh_tenant_schedule_policy ：wgh_app 租户隔离 + wgh_scheduler 全量
--   wgh_tenant_runtime_slot    ：wgh_app 租户隔离 + wgh_scheduler 全量
--   wgh_execution_event        ：**不开启 RLS**（无 tenant_id，按 execution_id 归属校验）
--
-- 应用侧前置：
--   1. claim/心跳/回收 SQL 在独立事务内先 `SET LOCAL ROLE wgh_scheduler`，事务结束自动还原；
--      禁止会话级 SET ROLE。
--   2. 提交/取消/查询作业仍走 wgh_app + app.tenant_id，保证租户内可见性。
--   3. wgh_scheduler 不授予任何业务表权限，越权访问应直接 permission denied。
--
-- 回滚：单表 ALTER TABLE <t> DISABLE ROW LEVEL SECURITY; 或执行 14_rls_rollback.sql。
-- ========================================================

BEGIN;

DO $$
DECLARE
  t text;
  tables text[] := ARRAY[
    'wgh_execution_job',
    'wgh_tenant_schedule_policy',
    'wgh_tenant_runtime_slot'
  ];
BEGIN
  FOREACH t IN ARRAY tables LOOP
    IF to_regclass(t) IS NULL THEN
      RAISE EXCEPTION 'RLS D 组表不存在：%，请先执行调度相关迁移', t;
    END IF;

    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);

    EXECUTE format('DROP POLICY IF EXISTS p_tenant_isolation ON %I', t);
    EXECUTE format(
      'CREATE POLICY p_tenant_isolation ON %I FOR ALL TO wgh_app '
      'USING (tenant_id = current_setting(%L, true)) '
      'WITH CHECK (tenant_id = current_setting(%L, true))',
      t, 'app.tenant_id', 'app.tenant_id');

    EXECUTE format('DROP POLICY IF EXISTS p_scheduler_all ON %I', t);
    EXECUTE format(
      'CREATE POLICY p_scheduler_all ON %I FOR ALL TO wgh_scheduler '
      'USING (true) WITH CHECK (true)',
      t);
  END LOOP;
END $$;

-- wgh_execution_event 明确不开启 RLS（保留注释，便于验收核对）
COMMENT ON TABLE wgh_execution_event IS
  '执行事件（跨实例 SSE 重放事实源；只增不改，按保留期清理；不启用 RLS，按 execution_id 归属校验）';

COMMIT;
