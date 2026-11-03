-- 本地账号/体验账号测试补充（不改动共享 schema.sql）
ALTER TABLE wgh_user ADD COLUMN IF NOT EXISTS source VARCHAR(16) DEFAULT 'local';
ALTER TABLE wgh_user ADD COLUMN IF NOT EXISTS trial INT DEFAULT 0;
ALTER TABLE wgh_user ADD COLUMN IF NOT EXISTS expire_at TIMESTAMP;
ALTER TABLE wgh_user ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE wgh_user ADD COLUMN IF NOT EXISTS password_must_change INT DEFAULT 0;
ALTER TABLE wgh_user ADD COLUMN IF NOT EXISTS password_version INT DEFAULT 1;

CREATE TABLE IF NOT EXISTS wgh_audit_log (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64),
    action VARCHAR(64),
    resource_type VARCHAR(64),
    resource_id VARCHAR(64),
    detail VARCHAR(500),
    ip VARCHAR(64),
    status VARCHAR(20),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    deleted INT DEFAULT 0
);
