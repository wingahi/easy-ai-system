-- ===== 租户管理端能力（幂等；只动当前库，不跨库） =====
-- 1) 新增租户相关权限码，并授予内置 admin
-- 2) 租户级配额配置表（平台配置表，不纳入租户数据面 / 不做 RLS）
-- 执行用户：迁移账号（当前 run-migrations.sh 仍使用 postgres），需具备 DDL 权限。

BEGIN;

INSERT INTO wgh_permission (perm_code, perm_name, resource_type) VALUES
('tenant:update',   '更新租户信息',   'TENANT'),
('tenant:disable',  '租户下线',       'TENANT'),
('tenant:quota',    '管理租户配额',   'TENANT'),
('tenant:schedule', '管理租户调度策略', 'TENANT')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO wgh_role_permission (role_id, permission_id)
SELECT r.id, p.id
  FROM wgh_role r
  JOIN wgh_permission p ON p.perm_code IN
       ('tenant:update', 'tenant:disable', 'tenant:quota', 'tenant:schedule')
 WHERE r.role_code = 'admin'
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS wgh_tenant_quota_config (
  id           BIGSERIAL PRIMARY KEY,
  tenant_id    VARCHAR(64) NOT NULL,
  period       VARCHAR(16) NOT NULL DEFAULT 'DAY',
  chat_limit   BIGINT      NOT NULL DEFAULT 0,
  token_limit  BIGINT      NOT NULL DEFAULT 0,
  enabled      SMALLINT    NOT NULL DEFAULT 1,
  remark       VARCHAR(255),
  create_time  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  create_by    VARCHAR(64),
  update_by    VARCHAR(64),
  deleted      SMALLINT    NOT NULL DEFAULT 0
);

COMMENT ON TABLE wgh_tenant_quota_config IS '租户级配额配置（平台配置表，仅管理端读写）';
COMMENT ON COLUMN wgh_tenant_quota_config.chat_limit IS '请求数上限，<=0 表示不限制';
COMMENT ON COLUMN wgh_tenant_quota_config.token_limit IS 'token 上限，<=0 表示不限制';

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_quota_config_tenant
    ON wgh_tenant_quota_config (tenant_id)
    WHERE deleted = 0;

COMMIT;
