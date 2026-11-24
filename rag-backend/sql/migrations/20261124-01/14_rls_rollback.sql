-- ========================================================
-- 回滚：多租户阶段 3（关闭 RLS）
-- 状态：评审稿，**未执行**；不登记到 kustomize / run-migrations.sh。
--
-- 原则：
--   1. 单表 DISABLE 只改元数据，不重写表，秒级生效；
--   2. 保留策略定义（p_*），便于修复后再次 ENABLE/FORCE；
--   3. 不删除 scope 列、不删角色、不删授权，避免不可逆操作；
--   4. 生产执行前记录操作人、时间、表清单、观察指标，写入审计。
--
-- 使用方式：
--   - 单表回滚：只执行对应 ALTER TABLE ... DISABLE ROW LEVEL SECURITY;
--   - 分组回滚：按 D → B → A 的逆序执行本脚本对应段落；
--   - 全局回滚：执行本脚本全部段落。
-- ========================================================

BEGIN;

-- D 组（调度）
ALTER TABLE wgh_tenant_runtime_slot DISABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_tenant_schedule_policy DISABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_execution_job DISABLE ROW LEVEL SECURITY;

-- 平台内置资源
ALTER TABLE wgh_tool DISABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_prompt_template DISABLE ROW LEVEL SECURITY;

-- B 组（免登录/日志）
ALTER TABLE wgh_audit_log DISABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_api_call_log DISABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_api_key DISABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_share_link DISABLE ROW LEVEL SECURITY;

-- A 组（标准租户表）
DO $$
DECLARE
  t text;
  tables text[] := ARRAY[
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
    'wgh_chat_window',
    'wgh_chat_session',
    'wgh_chat_message',
    'wgh_semantic_cache',
    'wgh_eval_dataset',
    'wgh_eval_case',
    'wgh_eval_result',
    'wgh_agent_memory',
    'wgh_agent_plan',
    'wgh_agent_step',
    'wgh_m_agent_crew',
    'wgh_m_agent_run',
    'wgh_m_agent_step',
    'wgh_workflow',
    'wgh_workflow_version',
    'wgh_workflow_run',
    'wgh_workflow_node_run',
    'wgh_workflow_trigger',
    'wgh_user_quota_config',
    'wgh_quota_usage',
    'wgh_quota_charge'
  ];
BEGIN
  FOREACH t IN ARRAY tables LOOP
    IF to_regclass(t) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE %I DISABLE ROW LEVEL SECURITY', t);
    END IF;
  END LOOP;
END $$;

COMMIT;

-- ========================================================
-- 可选：彻底清理（仅在确认不再启用 RLS 时执行，需单独评审）
--   DROP POLICY IF EXISTS p_tenant_isolation ON <t>;
--   DROP POLICY IF EXISTS p_platform_read ON <t>;
--   DROP POLICY IF EXISTS p_preauth_read ON <t>;
--   DROP POLICY IF EXISTS p_tenant_scope ON <t>;
--   DROP POLICY IF EXISTS p_platform_manage ON <t>;
--   DROP POLICY IF EXISTS p_scheduler_all ON <t>;
--   ALTER TABLE wgh_prompt_template DROP CONSTRAINT IF EXISTS ck_prompt_template_scope;
--   ALTER TABLE wgh_tool DROP CONSTRAINT IF EXISTS ck_tool_scope;
--   ALTER TABLE wgh_prompt_template DROP COLUMN IF EXISTS scope;
--   ALTER TABLE wgh_tool DROP COLUMN IF EXISTS scope;
--
-- 最后手段：把应用连接角色临时切回 owner。等于放弃数据库层兜底，必须审计留痕。
-- ========================================================
