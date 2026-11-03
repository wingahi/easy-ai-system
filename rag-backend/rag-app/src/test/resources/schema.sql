-- H2 内存数据库 (MODE=PostgreSQL, H2 2.2+)
-- 使用 PostgreSQL SERIAL 方言（H2 MODE=PostgreSQL 支持）

-- ===== 用户 / RBAC =====

CREATE TABLE IF NOT EXISTS wgh_user (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(200),
    name VARCHAR(100),
    email VARCHAR(200),
    tenant_id BIGINT,
    status INT DEFAULT 1,
    oidc_subject VARCHAR(200),
    oidc_issuer VARCHAR(500),
    source VARCHAR(16) DEFAULT 'local',
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
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS wgh_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
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
    (10, 'audit:view', '审计查看', 'AUDIT');

MERGE INTO wgh_user_role (user_id, role_id) KEY(user_id, role_id) VALUES
    (1, 1),  -- admin → admin
    (2, 2),  -- editor → editor
    (3, 3);  -- viewer → viewer

MERGE INTO wgh_role_permission (role_id, permission_id) KEY(role_id, permission_id) VALUES
    -- admin: all 10
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10),
    -- editor: kb读写 + agent + workflow + tool + prompt (7)
    (2, 1), (2, 2), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8),
    -- viewer: 只读 (3)
    (3, 1);

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
    user_id BIGINT NOT NULL,
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
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_chat_session (
    id SERIAL PRIMARY KEY,
    window_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(200),
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
    content TEXT NOT NULL,
    sources TEXT,
    hit_rate DOUBLE,
    accuracy_rate DOUBLE,
    mrr DOUBLE,
    ndcg DOUBLE,
    faithfulness DOUBLE,
    answer_relevancy DOUBLE,
    judge_agreement DOUBLE,
    judge_details TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_knowledge_base (
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    collection_name VARCHAR(100),
    embedding_model_id BIGINT,
    status INT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wgh_knowledge_file (
    id SERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    original_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(500),
    file_size BIGINT,
    file_type VARCHAR(20),
    split_strategy VARCHAR(20),
    split_params TEXT,
    chunk_count INT DEFAULT 0,
    status INT DEFAULT 0,
    error_msg TEXT,
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
    compression_model BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
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
    model_type VARCHAR(30),
    dimension INT,
    is_default BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
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
