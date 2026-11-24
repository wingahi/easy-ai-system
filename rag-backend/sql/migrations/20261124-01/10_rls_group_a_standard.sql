-- ========================================================
-- 迁移：多租户阶段 3（RLS A 组：32 张标准租户表）
-- 状态：评审稿，**未执行**；不登记到 kustomize / run-migrations.sh。
-- 前置：09_rls_roles.sql 已执行；表 owner 为执行角色（默认 postgres）。
-- 幂等性：ALTER TABLE ENABLE/FORCE 可重复；DROP POLICY IF EXISTS + CREATE POLICY 可重复。
-- 策略：USING / WITH CHECK 均为 tenant_id = current_setting('app.tenant_id', true)。
--       未设置 GUC 时 current_setting(..., true) 返回 NULL，比较结果为 NULL，查询 0 行、写入被拒。
-- 说明：wgh_app NOINHERIT；平台/调度/预认证角色未在此组授权，默认拒绝。
-- 回滚：单表 ALTER TABLE <t> DISABLE ROW LEVEL SECURITY;
--       或执行 14_rls_rollback.sql（保留策略定义，便于再次开启）。
-- ========================================================

BEGIN;

DO $$
DECLARE
  t text;
  tables text[] := ARRAY[
    -- 知识库域
    'wgh_knowledge_base',
    'wgh_knowledge_file',
    'wgh_knowledge_file_version',
    'wgh_kb_chunk',
    'wgh_kb_connector',
    'wgh_kb_connector_run',
    'wgh_kb_ground_truth',
    'wgh_upload_session',
    'wgh_tag',
    'wgh_kb_tag_rel',
    'wgh_file_tag_rel',
    -- 对话域
    'wgh_chat_window',
    'wgh_chat_session',
    'wgh_chat_message',
    'wgh_semantic_cache',
    -- 评测域
    'wgh_eval_dataset',
    'wgh_eval_case',
    'wgh_eval_result',
    -- Agent 域
    'wgh_agent_memory',
    'wgh_agent_plan',
    'wgh_agent_step',
    'wgh_m_agent_crew',
    'wgh_m_agent_run',
    'wgh_m_agent_step',
    -- 工作流域
    'wgh_workflow',
    'wgh_workflow_version',
    'wgh_workflow_run',
    'wgh_workflow_node_run',
    'wgh_workflow_trigger',
    -- 配额域
    'wgh_user_quota_config',
    'wgh_quota_usage',
    'wgh_quota_charge'
  ];
BEGIN
  FOREACH t IN ARRAY tables LOOP
    IF to_regclass(t) IS NULL THEN
      RAISE EXCEPTION 'RLS A 组表不存在：%，请先执行 schema / 06_tenant_data_plane.sql', t;
    END IF;

    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);

    EXECUTE format('DROP POLICY IF EXISTS p_tenant_isolation ON %I', t);
    EXECUTE format(
      'CREATE POLICY p_tenant_isolation ON %I FOR ALL TO wgh_app '
      'USING (tenant_id = current_setting(%L, true)) '
      'WITH CHECK (tenant_id = current_setting(%L, true))',
      t, 'app.tenant_id', 'app.tenant_id');
  END LOOP;
END $$;

COMMIT;
