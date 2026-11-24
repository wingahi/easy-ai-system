-- ========================================================
-- 迁移：多租户阶段 1（租户目录、用户/审计/API Key 租户维度）
-- 适用对象：2026-11-24 之前创建的存量库（wgh_rag_dev / wgh_rag_test / wgh_rag_prod 均适用）
-- 新库说明：sql/schema/01-schema.sql 已包含本表与列，新建库无需再执行本脚本
-- 执行顺序：在 20261124-01/04_tenant_schedule_policy.sql 之后单独执行
--   存量库：psql -f sql/migrations/20261124-01/05_tenant.sql
-- 幂等性：建表/建列/建索引均使用 IF NOT EXISTS；回填 UPDATE 只处理 tenant_id 为空的行；
--         SET NOT NULL 在已回填后重复执行不会失败；权限/授权均使用 ON CONFLICT DO NOTHING。
-- 回填说明：存量用户、审计日志、API Key 全部归入默认租户 default（code='default'）；
--           不删除、不改写既有业务数据，仅补租户归属列。
-- 影响面：新增 1 张租户表 + 3 个租户列 + 4 个索引 + 1 个权限点。
-- 待拍板：wgh_user 唯一约束仍为全局 username；是否改为 (tenant_id, username) 需产品确认。
-- ========================================================

BEGIN;

-- 1. 租户目录（code 为全局唯一租户编码，业务表 tenant_id 存该值，与调度侧 VARCHAR(64) 口径一致）
CREATE TABLE IF NOT EXISTS wgh_tenant (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  plan VARCHAR(32) NOT NULL DEFAULT 'standard',
  isolation_mode VARCHAR(16) NOT NULL DEFAULT 'shared',
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  create_by VARCHAR(64),
  update_by VARCHAR(64),
  deleted SMALLINT NOT NULL DEFAULT 0,
  UNIQUE (code)
);

COMMENT ON TABLE wgh_tenant IS '租户目录（一个租户=一个企业/组织；业务表 tenant_id 存 code）';
COMMENT ON COLUMN wgh_tenant.code IS '租户编码（全局唯一；单租户部署为 default）';
COMMENT ON COLUMN wgh_tenant.status IS '状态：1-启用，0-停用（停用后其令牌与 API Key 一律拒绝）';
COMMENT ON COLUMN wgh_tenant.plan IS '套餐标识（standard / enterprise 等）';
COMMENT ON COLUMN wgh_tenant.isolation_mode IS '隔离模式：shared（默认）/ schema / database（阶段 4 预留）';

-- 默认租户：存量数据统一归入，重复执行安全
INSERT INTO wgh_tenant (code, name, status, plan, isolation_mode)
VALUES ('default', '默认租户', 1, 'standard', 'shared')
ON CONFLICT (code) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_tenant_status ON wgh_tenant(status);

-- 2. 用户租户维度：先回填默认租户，再收紧为 NOT NULL
ALTER TABLE wgh_user ALTER COLUMN tenant_id SET DEFAULT 'default';
UPDATE wgh_user SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
ALTER TABLE wgh_user ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_user_tenant ON wgh_user(tenant_id);

-- 3. 审计日志租户维度（阶段 1：审计切面从 TenantContext 取值）
ALTER TABLE wgh_audit_log ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
UPDATE wgh_audit_log SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
CREATE INDEX IF NOT EXISTS idx_audit_tenant_time ON wgh_audit_log(tenant_id, create_time DESC);

-- 4. 开放 API Key 租户维度（阶段 1 绑定 owner/租户，阶段 2 统一过滤）
ALTER TABLE wgh_api_key ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
UPDATE wgh_api_key SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
CREATE INDEX IF NOT EXISTS idx_api_key_tenant ON wgh_api_key(tenant_id);

-- 5. 开放 API 调用日志租户维度
ALTER TABLE wgh_api_call_log ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
UPDATE wgh_api_call_log SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
CREATE INDEX IF NOT EXISTS idx_api_call_tenant ON wgh_api_call_log(tenant_id, create_time DESC);

-- 6. 平台租户管理权限（仅 admin 授予；editor/viewer 不授予）
INSERT INTO wgh_permission (perm_code, perm_name, resource_type)
VALUES ('tenant:manage', '管理租户', 'TENANT')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO wgh_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM wgh_role r
JOIN wgh_permission p ON p.perm_code = 'tenant:manage'
WHERE r.role_code = 'admin'
ON CONFLICT DO NOTHING;

COMMIT;
