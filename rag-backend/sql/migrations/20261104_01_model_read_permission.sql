-- ========================================================
-- 增量迁移：新增 model:read 权限码（P0-2 敏感字段泄露配套）
-- 适用环境：已上线存量库（不会重跑 data.sql）
-- 特性：幂等，可重复执行
-- ========================================================

-- 1) 权限码
INSERT INTO wgh_permission (perm_code, perm_name, resource_type) VALUES
('model:read', '查看模型配置', 'MODEL')
ON CONFLICT (perm_code) DO NOTHING;

-- 2) 授权：admin 全量、editor/viewer 只读模型配置
INSERT INTO wgh_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM wgh_role r
CROSS JOIN wgh_permission p
WHERE r.role_code = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO wgh_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM wgh_role r
JOIN wgh_permission p ON p.perm_code IN ('model:read')
WHERE r.role_code IN ('editor', 'viewer')
ON CONFLICT DO NOTHING;
