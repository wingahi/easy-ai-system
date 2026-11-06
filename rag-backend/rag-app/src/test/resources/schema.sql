-- H2 内存数据库 (MODE=PostgreSQL, H2 2.2+)
-- 使用 PostgreSQL SERIAL 方言（H2 MODE=PostgreSQL 支持）

-- ===== 用户 / RBAC =====

CREATE TABLE IF NOT EXISTS wgh_user (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(200),
    name VARCHAR(100),
    email VARCHAR(200),
    tenant_id VARCHAR(100),
    status INT DEFAULT 1,
    oidc_subject VARCHAR(200),
    oidc_issuer VARCHAR(500),
    source VARCHAR(16) DEFAULT 'local',
    trial INT DEFAULT 0,
    expire_at TIMESTAMP,
    remark VARCHAR(500),
    password_must_change INT DEFAULT 0,
    password_version INT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_role (
    id SERIAL PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL UNIQUE,
    role_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_permission (
    id SERIAL PRIMARY KEY,
    perm_code VARCHAR(100) NOT NULL UNIQUE,
    perm_name VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wgh_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS wgh_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id)
);

-- ===== 种子数据 =====

MERGE INTO wgh_user (id, username, password_hash, name, status) KEY(id) VALUES
    (1, 'admin', 'admin', 'Admin', 1),
    (2, 'editor', 'admin', 'Editor', 1),
    (3, 'viewer', 'admin', 'Viewer', 1);

MERGE INTO wgh_role (id, role_code, role_name, description) KEY(id) VALUES
    (1, 'admin', '管理员', '拥有全部权限'),
    (2, 'editor', '编辑者', '可读写知识库/工作流/工具'),
    (3, 'viewer', '浏览者', '只读访问');

MERGE INTO wgh_permission (id, perm_code, perm_name, resource_type) KEY(id) VALUES
    (1, 'kb:read', '知识库读取', 'KNOWLEDGE_BASE'),
    (2, 'kb:write', '知识库写入', 'KNOWLEDGE_BASE'),
    (3, 'kb:delete', '知识库删除', 'KNOWLEDGE_BASE'),
    (4, 'agent:use', 'Agent 使用', 'AGENT'),
    (5, 'workflow:use', '工作流执行', 'WORKFLOW'),
    (6, 'workflow:edit', '工作流编辑', 'WORKFLOW'),
    (7, 'tool:use', '工具调用', 'TOOL'),
    (8, 'prompt:edit', 'Prompt 编辑', 'PROMPT'),
    (9, 'model:manage', '模型管理', 'MODEL'),
    (10, 'audit:view', '审计查看', 'AUDIT'),
    (11, 'model:read', '查看模型配置', 'MODEL'),
    (12, 'chat:manage', '管理聊天窗口', 'CHAT'),
    (13, 'agent:manage', '管理多Agent编排', 'AGENT'),
    (14, 'apikey:manage', '管理开放APIKey', 'API_KEY'),
    (15, 'share:manage', '管理分享链接', 'SHARE_LINK'),
    (16, 'pricing:manage', '管理模型价格', 'MODEL_PRICE');

MERGE INTO wgh_user_role (user_id, role_id) KEY(user_id, role_id) VALUES
    (1, 1),  -- admin → admin
    (2, 2),  -- editor → editor
    (3, 3);  -- viewer → viewer

MERGE INTO wgh_role_permission (role_id, permission_id) KEY(role_id, permission_id) VALUES
    -- admin: 全部 16 项（与生产种子一致）
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10),
    (1, 11), (1, 12), (1, 13), (1, 14), (1, 15), (1, 16),
    -- editor: kb读写 + agent + workflow + tool + prompt + model:read
    (2, 1), (2, 2), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8), (2, 11),
    -- viewer: kb:read + agent:use + workflow:use + model:read
    (3, 1), (3, 4), (3, 5), (3, 11);

-- ===== 原有业务表 =====

CREATE TABLE IF NOT EXISTS wgh_tool (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tool_type VARCHAR(30) NOT NULL,
    description VARCHAR(500),
    definition TEXT,
    executor_config TEXT,
    status INT DEFAULT 1,
    is_example BOOLEAN DEFAULT FALSE,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_chat_window (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    knowledge_base_ids TEXT,
    llm_model_id BIGINT,
    system_prompt TEXT,
    temperature DOUBLE DEFAULT 0.7,
    max_history_messages INT DEFAULT 20,
    tool_ids TEXT,
    evaluation_scheme VARCHAR(20) DEFAULT 'NONE',
    judge_model_ids TEXT,
    share_enabled BOOLEAN DEFAULT FALSE,
    share_token VARCHAR(100),
    status INT DEFAULT 1,
    agent_enabled INT DEFAULT 0,
    agent_max_steps INT DEFAULT 15,
    agent_planner_model_id BIGINT,
    agent_executor_model_id BIGINT,
    agent_critic_model_ids TEXT,
    agent_reflection_threshold DOUBLE,
    agent_hitl_enabled INT DEFAULT 0,
    workflow_id BIGINT,
    rerank_enabled BOOLEAN DEFAULT FALSE,
    rerank_model_id BIGINT,
    hybrid_enabled BOOLEAN DEFAULT FALSE,
    query_transform_mode VARCHAR(20),
    rewrite_model_id BIGINT,
    semantic_cache_enabled BOOLEAN DEFAULT FALSE,
    model_route_group VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_chat_session (
    id SERIAL PRIMARY KEY,
    chat_window_id BIGINT NOT NULL,
    title VARCHAR(200),
    user_identifier VARCHAR(100) NOT NULL,
    persistent BOOLEAN DEFAULT TRUE,
    message_count INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    mid_summary TEXT,
    mid_summary_round_start INT,
    mid_summary_round_end INT,
    mid_summary_model VARCHAR(100),
    mid_summary_prompt_version VARCHAR(50),
    mid_summary_time TIMESTAMP,
    long_term_facts TEXT,
    long_term_facts_round_end INT,
    long_term_facts_model VARCHAR(100),
    long_term_facts_prompt_version VARCHAR(50),
    long_term_facts_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_chat_message (
    id SERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    message_type VARCHAR(20) DEFAULT 'TEXT',
    status VARCHAR(20),
    token_count INT DEFAULT 0,
    recall_rate DOUBLE,
    coverage_rate DOUBLE,
    sources TEXT,
    hit_rate DOUBLE,
    accuracy_rate DOUBLE,
    mrr DOUBLE,
    ndcg DOUBLE,
    faithfulness DOUBLE,
    answer_relevancy DOUBLE,
    judge_agreement DOUBLE,
    judge_details TEXT,
    attachments TEXT,
    feedback INT DEFAULT 0,
    feedback_comment VARCHAR(500),
    cost DOUBLE,
    model_name VARCHAR(100),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_knowledge_base (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    collection_name VARCHAR(100),
    embedding_model_id BIGINT,
    dimension INT,
    file_count INT DEFAULT 0,
    chunk_count INT DEFAULT 0,
    status INT DEFAULT 1,
    rebuild_status VARCHAR(20) DEFAULT '',
    rebuild_total INT DEFAULT 0,
    rebuild_processed INT DEFAULT 0,
    pending_collection VARCHAR(100),
    pending_embedding_model_id BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_knowledge_file (
    id SERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    file_name VARCHAR(500),
    file_path VARCHAR(500),
    file_size BIGINT,
    file_type VARCHAR(20),
    split_strategy VARCHAR(30),
    split_config TEXT,
    chunk_count INT DEFAULT 0,
    status VARCHAR(20),
    error_message TEXT,
    content_hash VARCHAR(64),
    version INT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_llm_model (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    base_url VARCHAR(500),
    api_key VARCHAR(500),
    model_type VARCHAR(30),
    model_name VARCHAR(100),
    max_context_tokens INT DEFAULT 4096,
    temperature DOUBLE DEFAULT 0.7,
    compression_model BOOLEAN DEFAULT FALSE,
    supports_vision BOOLEAN DEFAULT FALSE,
    route_group VARCHAR(100),
    route_priority INT DEFAULT 0,
    status INT DEFAULT 1,
    remark VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_embedding_model (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    base_url VARCHAR(500),
    api_key VARCHAR(500),
    model_name VARCHAR(100),
    dimension INT,
    status INT DEFAULT 1,
    is_default BOOLEAN DEFAULT FALSE,
    remark VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

-- ===== 配额（rag-quota） =====

CREATE TABLE IF NOT EXISTS wgh_quota_usage (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    date_key VARCHAR(10) NOT NULL,
    chat_count INT DEFAULT 0,
    token_used BIGINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0,
    CONSTRAINT uk_quota_user_date UNIQUE (username, date_key)
);

CREATE TABLE IF NOT EXISTS wgh_user_quota_config (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    period VARCHAR(10) NOT NULL DEFAULT 'DAY',
    chat_limit INT DEFAULT 0,
    token_limit BIGINT DEFAULT 0,
    enabled INT DEFAULT 1,
    remark VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0,
    CONSTRAINT uk_user_quota_username UNIQUE (username)
);

-- ===== 生产基线同步：标签 / 版本 / 缓存 =====

CREATE TABLE IF NOT EXISTS wgh_tag (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    color VARCHAR(20),
    remark VARCHAR(200),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_kb_tag_rel (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (kb_id, tag_id)
);

CREATE TABLE IF NOT EXISTS wgh_file_tag_rel (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (file_id, tag_id)
);

CREATE TABLE IF NOT EXISTS wgh_knowledge_file_version (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL,
    version INT NOT NULL,
    chunk_count INT,
    split_strategy VARCHAR(20),
    split_config TEXT,
    status VARCHAR(20),
    remark VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wgh_semantic_cache (
    id BIGSERIAL PRIMARY KEY,
    window_id BIGINT NOT NULL,
    query_text VARCHAR(2000) NOT NULL,
    answer_text TEXT NOT NULL,
    embedding_id VARCHAR(64),
    hit_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_kb_ground_truth (
    id BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    query_text VARCHAR(500) NOT NULL,
    relevant_chunks TEXT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

-- ===== 生产基线同步：工作流 =====

CREATE TABLE IF NOT EXISTS wgh_workflow (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status SMALLINT DEFAULT 1,
    version INT DEFAULT 1,
    graph_json TEXT NOT NULL,
    share_token VARCHAR(64),
    share_enabled SMALLINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_workflow_version (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    version INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    graph_json TEXT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_workflow_trigger (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    cron_expression VARCHAR(50),
    webhook_token VARCHAR(64),
    input_text VARCHAR(1000),
    status SMALLINT DEFAULT 1,
    last_fire_time TIMESTAMP,
    fire_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_workflow_run (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    session_id BIGINT,
    workflow_version INT,
    input_text TEXT,
    status VARCHAR(20) DEFAULT 'RUNNING',
    output_text TEXT,
    context_json TEXT,
    graph_snapshot TEXT,
    error_message TEXT,
    executed_nodes INT DEFAULT 0,
    total_nodes INT DEFAULT 0,
    start_time TIMESTAMP,
    finish_time TIMESTAMP,
    duration_ms BIGINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_workflow_node_run (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_type VARCHAR(30) NOT NULL,
    node_name VARCHAR(100),
    status VARCHAR(20) DEFAULT 'PENDING',
    iteration INT DEFAULT 0,
    input_json TEXT,
    output_json TEXT,
    error_message TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_ms BIGINT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

-- ===== 生产基线同步：Agent 规划 / 多 Agent =====

CREATE TABLE IF NOT EXISTS wgh_agent_plan (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    chat_window_id BIGINT NOT NULL,
    llm_model_id BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'PLANNING',
    user_goal TEXT NOT NULL,
    plan_json TEXT NOT NULL,
    current_step_index INT DEFAULT 0,
    max_steps INT DEFAULT 15,
    total_tokens INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finish_time TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_agent_step (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    step_index INT NOT NULL,
    step_id VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    depends_on TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',
    thought TEXT,
    action_tool VARCHAR(100),
    action_input TEXT,
    observation TEXT,
    reflection_score DOUBLE PRECISION,
    reflection_feedback TEXT,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    result_summary TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    token_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_m_agent_crew (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    execution_mode VARCHAR(30) NOT NULL,
    agents_json TEXT NOT NULL,
    graph_json TEXT,
    crew_config_json TEXT,
    group_chat_config_json TEXT,
    status SMALLINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_m_agent_run (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    session_id BIGINT NOT NULL,
    chat_window_id BIGINT NOT NULL,
    execution_mode VARCHAR(30) NOT NULL,
    crew_config_json TEXT NOT NULL,
    agents_json TEXT NOT NULL,
    user_goal TEXT NOT NULL,
    input_text TEXT,
    output_text TEXT,
    status VARCHAR(20) DEFAULT 'PLANNING',
    current_round INT DEFAULT 0,
    max_rounds INT DEFAULT 20,
    dynamic_router_model_id BIGINT,
    dynamic_router_prompt TEXT,
    termination_criteria TEXT,
    summarizer_agent_id VARCHAR(32),
    error_message TEXT,
    start_time TIMESTAMP,
    finish_time TIMESTAMP,
    duration_ms BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_m_agent_step (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(32) NOT NULL,
    step_index INT NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    role VARCHAR(50),
    input_context TEXT,
    output_text TEXT,
    tool_calls_json TEXT,
    rag_sources_json TEXT,
    reflection_score DOUBLE PRECISION,
    reflection_feedback TEXT,
    thought TEXT,
    hitl_expected_input TEXT,
    result_summary TEXT,
    round INT DEFAULT 0,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    token_count INT DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

-- ===== 生产基线同步：价格 / 分享 / 开放 API =====

CREATE TABLE IF NOT EXISTS wgh_model_price (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    input_price_per_1k DOUBLE PRECISION NOT NULL DEFAULT 0,
    output_price_per_1k DOUBLE PRECISION NOT NULL DEFAULT 0,
    currency VARCHAR(10) DEFAULT 'CNY',
    remark VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0,
    CONSTRAINT uk_model_price UNIQUE (provider, model_name)
);

CREATE TABLE IF NOT EXISTS wgh_prompt_template (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(50),
    content TEXT NOT NULL,
    variables VARCHAR(500),
    version INT DEFAULT 1,
    builtin SMALLINT DEFAULT 0,
    remark VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(50) DEFAULT 'system',
    update_by VARCHAR(50) DEFAULT 'system',
    deleted SMALLINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_share_link (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100),
    scope VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    expires_at TIMESTAMP,
    password_hash VARCHAR(128),
    max_uses INT DEFAULT 0,
    use_count INT DEFAULT 0,
    revoked SMALLINT NOT NULL DEFAULT 0,
    embed_enabled SMALLINT NOT NULL DEFAULT 0,
    embed_origin_whitelist TEXT,
    embed_ip_whitelist TEXT,
    created_by VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_api_key (
    id BIGSERIAL PRIMARY KEY,
    key_prefix VARCHAR(16),
    key_hash VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(100),
    scopes VARCHAR(200),
    expires_at TIMESTAMP,
    revoked SMALLINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64),
    last_used_time TIMESTAMP,
    ip_whitelist TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_api_call_log (
    id BIGSERIAL PRIMARY KEY,
    api_key_id BIGINT,
    api_key_name VARCHAR(100),
    username VARCHAR(64),
    method VARCHAR(10),
    path VARCHAR(300),
    status_code INT,
    duration_ms BIGINT,
    ip VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    deleted SMALLINT NOT NULL DEFAULT 0
);
