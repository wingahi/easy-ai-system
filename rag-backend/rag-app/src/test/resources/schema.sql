-- H2 内存数据库 (MODE=PostgreSQL, H2 2.2+)
-- 使用 PostgreSQL SERIAL 方言（H2 MODE=PostgreSQL 支持）

CREATE TABLE IF NOT EXISTS wgh_user (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(100),
    role VARCHAR(20) DEFAULT 'USER',
    status INT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(100),
    update_by VARCHAR(100),
    deleted INT DEFAULT 0
);

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
