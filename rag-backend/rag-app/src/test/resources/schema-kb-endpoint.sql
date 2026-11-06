-- 知识库接口回归测试补齐共享 schema.sql 缺失的列 / 表（仅测试库，不影响生产脚本）
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS dimension INT;
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS file_count INT DEFAULT 0;
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS chunk_count INT DEFAULT 0;

-- 共享 schema.sql 的 wgh_knowledge_file 列名已与实体漂移（kb_id/original_name/split_params），
-- 按生产结构重建，供知识库接口回归测试使用
DROP TABLE IF EXISTS wgh_knowledge_file;
CREATE TABLE wgh_knowledge_file (
    id BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    file_name VARCHAR(500),
    file_type VARCHAR(20),
    file_size BIGINT,
    file_path VARCHAR(500),
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
    deleted SMALLINT DEFAULT 0
);

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
    UNIQUE(kb_id, tag_id)
);

CREATE TABLE IF NOT EXISTS wgh_file_tag_rel (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(file_id, tag_id)
);
