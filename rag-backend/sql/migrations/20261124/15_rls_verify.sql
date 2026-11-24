-- ========================================================
-- 验收：多租户阶段 3（RLS）只读核对脚本
-- 状态：评审稿，**未执行**；不修改任何数据/角色/策略。
-- 用法：psql -v ON_ERROR_STOP=1 -f 15_rls_verify.sql
-- 前置：09~13 已按灰度顺序执行；应用已切到 wgh_app。
-- 说明：SET LOCAL ROLE 只在事务内生效，ROLLBACK 后自动还原。
-- ========================================================

\echo '== 1. 角色属性（期望 wgh_app: super=f bypass=f inherit=f login=f）'
SELECT rolname, rolsuper, rolbypassrls, rolinherit, rolcanlogin
FROM pg_roles
WHERE rolname IN ('wgh_app', 'wgh_platform', 'wgh_scheduler', 'wgh_preauth')
ORDER BY rolname;

\echo '== 2. 角色成员关系（期望 3 条：wgh_platform/scheduler/preauth -> wgh_app）'
SELECT r.rolname AS member, m.rolname AS granted_role
FROM pg_auth_members am
JOIN pg_roles r ON r.oid = am.member
JOIN pg_roles m ON m.oid = am.roleid
WHERE r.rolname = 'wgh_app'
ORDER BY m.rolname;

\echo '== 3. RLS 开关（期望 A/B/D/平台资源表 rowsecurity=t, forcerowsecurity=t）'
SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relname IN (
    'wgh_knowledge_base', 'wgh_knowledge_file', 'wgh_knowledge_file_version', 'wgh_kb_chunk',
    'wgh_kb_connector', 'wgh_kb_connector_run', 'wgh_kb_ground_truth', 'wgh_upload_session',
    'wgh_tag', 'wgh_kb_tag_rel', 'wgh_file_tag_rel',
    'wgh_chat_window', 'wgh_chat_session', 'wgh_chat_message', 'wgh_semantic_cache',
    'wgh_eval_dataset', 'wgh_eval_case', 'wgh_eval_result',
    'wgh_agent_memory', 'wgh_agent_plan', 'wgh_agent_step',
    'wgh_m_agent_crew', 'wgh_m_agent_run', 'wgh_m_agent_step',
    'wgh_workflow', 'wgh_workflow_version', 'wgh_workflow_run',
    'wgh_workflow_node_run', 'wgh_workflow_trigger',
    'wgh_user_quota_config', 'wgh_quota_usage', 'wgh_quota_charge',
    'wgh_share_link', 'wgh_api_key', 'wgh_api_call_log', 'wgh_audit_log',
    'wgh_prompt_template', 'wgh_tool',
    'wgh_execution_job', 'wgh_tenant_schedule_policy', 'wgh_tenant_runtime_slot'
  )
ORDER BY c.relname;

\echo '== 4. 策略清单'
SELECT tablename, policyname, roles, cmd, qual, with_check
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename LIKE 'wgh\_%'
ORDER BY tablename, policyname;

\echo '== 5. tenant_id 空值核对（应为 0 行告警）'
DO $$
DECLARE
  t text;
  n bigint;
  tables text[] := ARRAY[
    'wgh_knowledge_base', 'wgh_knowledge_file', 'wgh_knowledge_file_version', 'wgh_kb_chunk',
    'wgh_kb_connector', 'wgh_kb_connector_run', 'wgh_kb_ground_truth', 'wgh_upload_session',
    'wgh_tag', 'wgh_kb_tag_rel', 'wgh_file_tag_rel',
    'wgh_chat_window', 'wgh_chat_session', 'wgh_chat_message', 'wgh_semantic_cache',
    'wgh_eval_dataset', 'wgh_eval_case', 'wgh_eval_result',
    'wgh_agent_memory', 'wgh_agent_plan', 'wgh_agent_step',
    'wgh_m_agent_crew', 'wgh_m_agent_run', 'wgh_m_agent_step',
    'wgh_workflow', 'wgh_workflow_version', 'wgh_workflow_run',
    'wgh_workflow_node_run', 'wgh_workflow_trigger',
    'wgh_user_quota_config', 'wgh_quota_usage', 'wgh_quota_charge',
    'wgh_share_link', 'wgh_api_key', 'wgh_api_call_log', 'wgh_audit_log',
    'wgh_prompt_template', 'wgh_tool'
  ];
BEGIN
  FOREACH t IN ARRAY tables LOOP
    EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NULL OR tenant_id = %L', t, '') INTO n;
    IF n > 0 THEN
      RAISE WARNING '% 存在 % 行 tenant_id 为空', t, n;
    END IF;
  END LOOP;
  RAISE NOTICE 'tenant_id 空值核对完成';
END $$;

\echo '== 6. 无 GUC：wgh_app 读标准表（期望 0）'
BEGIN;
SET LOCAL ROLE wgh_app;
SELECT count(*) AS no_guc_rows FROM wgh_knowledge_base;
ROLLBACK;

\echo '== 7. 设置 GUC=default：wgh_app 读标准表（期望 >=0，且只含 default）'
BEGIN;
SET LOCAL ROLE wgh_app;
SELECT set_config('app.tenant_id', 'default', true);
SELECT count(*) AS default_rows FROM wgh_knowledge_base;
SELECT count(*) AS other_rows FROM wgh_knowledge_base WHERE tenant_id <> 'default';
ROLLBACK;

\echo '== 8. 事务回滚后连接不残留 GUC（期望 0）'
BEGIN;
SET LOCAL ROLE wgh_app;
SELECT set_config('app.tenant_id', 'default', true);
ROLLBACK;
BEGIN;
SET LOCAL ROLE wgh_app;
SELECT count(*) AS after_rollback_rows FROM wgh_knowledge_base;
ROLLBACK;

\echo '== 9. 平台内置 Prompt 对普通租户可见（期望 3 条种子模板）'
BEGIN;
SET LOCAL ROLE wgh_app;
SELECT set_config('app.tenant_id', 'default', true);
SELECT count(*) AS platform_prompts FROM wgh_prompt_template WHERE scope = 'platform';
ROLLBACK;

\echo '== 10. wgh_preauth：可读 share_link/api_key，不可读业务表'
BEGIN;
SET LOCAL ROLE wgh_preauth;
SELECT count(*) AS share_links FROM wgh_share_link;
SELECT count(*) AS api_keys FROM wgh_api_key;
DO $$
BEGIN
  PERFORM count(*) FROM wgh_knowledge_base;
  RAISE NOTICE 'FAIL: wgh_preauth 不应能读 wgh_knowledge_base';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'PASS: wgh_preauth 无业务表权限';
END $$;
ROLLBACK;

\echo '== 11. wgh_scheduler：可读调度表，不可读业务表'
BEGIN;
SET LOCAL ROLE wgh_scheduler;
SELECT count(*) AS jobs FROM wgh_execution_job;
DO $$
BEGIN
  PERFORM count(*) FROM wgh_knowledge_base;
  RAISE NOTICE 'FAIL: wgh_scheduler 不应能读 wgh_knowledge_base';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'PASS: wgh_scheduler 无业务表权限';
END $$;
ROLLBACK;

\echo '== 12. 性能冒烟：RLS 条件应走 tenant_id 前导索引'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM wgh_chat_message
WHERE tenant_id = 'default'
ORDER BY session_id, id
LIMIT 50;

\echo '== 验收脚本结束（所有事务已回滚，无数据变更）'
