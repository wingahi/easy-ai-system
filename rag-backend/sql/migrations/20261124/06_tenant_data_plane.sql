-- ========================================================
-- 迁移：多租户阶段 2（数据面租户列）
-- 适用对象：2026-11-24 之前创建的存量库（dev / test / prod 均适用）
-- 新库说明：sql/schema/01-schema.sql 末尾已包含等价 ALTER 块，新建库无需执行本脚本
-- 执行顺序：在 20261124/05_tenant.sql 之后、启用 wgh.tenant.sql-isolation-enabled 之前执行
-- 幂等性：ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS；回填只处理空值；重复执行安全
-- 回填说明：存量业务数据全部归入默认租户 default，不删除/不改写既有业务字段
-- 影响面：34 张业务表新增 tenant_id 列 + 34 个索引；平台共享表（用户/角色/权限/模型/价格/
--         租户目录/调度实例）不加密，保持全局可见
-- 对应代码：TenantTableRegistry（白名单必须与本脚本一致，新增表需同步两处）
-- ========================================================

BEGIN;

-- 知识库域
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_knowledge_file ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_knowledge_file_version ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_kb_connector ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_kb_connector_run ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_kb_ground_truth ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_upload_session ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_tag ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_kb_tag_rel ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_file_tag_rel ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- 对话域
ALTER TABLE wgh_chat_window ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_chat_session ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_chat_message ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_semantic_cache ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- 评测域
ALTER TABLE wgh_eval_dataset ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_eval_case ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_eval_result ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- 单 Agent / 多 Agent
ALTER TABLE wgh_agent_memory ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_agent_plan ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_agent_step ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_m_agent_crew ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_m_agent_run ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_m_agent_step ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- 工作流
ALTER TABLE wgh_workflow ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_workflow_version ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_workflow_run ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_workflow_node_run ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_workflow_trigger ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- 公开访问 / 配额 / 审计
ALTER TABLE wgh_share_link ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_user_quota_config ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_quota_usage ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_quota_charge ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- 提示词 / 工具
ALTER TABLE wgh_prompt_template ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE wgh_tool ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- 回填存量数据（ADD COLUMN ... DEFAULT 已覆盖，此处仅兜底历史脏数据）
UPDATE wgh_knowledge_base SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_knowledge_file SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_knowledge_file_version SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_kb_connector SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_kb_connector_run SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_kb_ground_truth SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_upload_session SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_tag SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_kb_tag_rel SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_file_tag_rel SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_chat_window SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_chat_session SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_chat_message SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_semantic_cache SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_eval_dataset SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_eval_case SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_eval_result SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_agent_memory SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_agent_plan SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_agent_step SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_m_agent_crew SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_m_agent_run SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_m_agent_step SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_workflow SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_workflow_version SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_workflow_run SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_workflow_node_run SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_workflow_trigger SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_share_link SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_user_quota_config SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_quota_usage SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_quota_charge SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_prompt_template SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE wgh_tool SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';

-- 索引（前缀索引已足够；高写入表只建单列 tenant_id 索引）
CREATE INDEX IF NOT EXISTS idx_knowledge_base_tenant ON wgh_knowledge_base(tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_file_tenant ON wgh_knowledge_file(tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_file_version_tenant ON wgh_knowledge_file_version(tenant_id);
CREATE INDEX IF NOT EXISTS idx_kb_connector_tenant ON wgh_kb_connector(tenant_id);
CREATE INDEX IF NOT EXISTS idx_kb_connector_run_tenant ON wgh_kb_connector_run(tenant_id);
CREATE INDEX IF NOT EXISTS idx_kb_ground_truth_tenant ON wgh_kb_ground_truth(tenant_id);
CREATE INDEX IF NOT EXISTS idx_upload_session_tenant ON wgh_upload_session(tenant_id);
CREATE INDEX IF NOT EXISTS idx_tag_tenant ON wgh_tag(tenant_id);
CREATE INDEX IF NOT EXISTS idx_kb_tag_rel_tenant ON wgh_kb_tag_rel(tenant_id);
CREATE INDEX IF NOT EXISTS idx_file_tag_rel_tenant ON wgh_file_tag_rel(tenant_id);
CREATE INDEX IF NOT EXISTS idx_chat_window_tenant ON wgh_chat_window(tenant_id);
CREATE INDEX IF NOT EXISTS idx_chat_session_tenant ON wgh_chat_session(tenant_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_tenant ON wgh_chat_message(tenant_id, session_id);
CREATE INDEX IF NOT EXISTS idx_semantic_cache_tenant ON wgh_semantic_cache(tenant_id);
CREATE INDEX IF NOT EXISTS idx_eval_dataset_tenant ON wgh_eval_dataset(tenant_id);
CREATE INDEX IF NOT EXISTS idx_eval_case_tenant ON wgh_eval_case(tenant_id);
CREATE INDEX IF NOT EXISTS idx_eval_result_tenant ON wgh_eval_result(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_memory_tenant ON wgh_agent_memory(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_plan_tenant ON wgh_agent_plan(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_step_tenant ON wgh_agent_step(tenant_id);
CREATE INDEX IF NOT EXISTS idx_m_agent_crew_tenant ON wgh_m_agent_crew(tenant_id);
CREATE INDEX IF NOT EXISTS idx_m_agent_run_tenant ON wgh_m_agent_run(tenant_id);
CREATE INDEX IF NOT EXISTS idx_m_agent_step_tenant ON wgh_m_agent_step(tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_tenant ON wgh_workflow(tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_version_tenant ON wgh_workflow_version(tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_run_tenant ON wgh_workflow_run(tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_node_run_tenant ON wgh_workflow_node_run(tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_trigger_tenant ON wgh_workflow_trigger(tenant_id);
CREATE INDEX IF NOT EXISTS idx_share_link_tenant ON wgh_share_link(tenant_id);
CREATE INDEX IF NOT EXISTS idx_user_quota_config_tenant ON wgh_user_quota_config(tenant_id);
CREATE INDEX IF NOT EXISTS idx_quota_usage_tenant ON wgh_quota_usage(tenant_id);
CREATE INDEX IF NOT EXISTS idx_quota_charge_tenant ON wgh_quota_charge(tenant_id);
CREATE INDEX IF NOT EXISTS idx_prompt_template_tenant ON wgh_prompt_template(tenant_id);
CREATE INDEX IF NOT EXISTS idx_tool_tenant ON wgh_tool(tenant_id);

COMMIT;
