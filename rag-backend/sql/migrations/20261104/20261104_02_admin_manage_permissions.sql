-- ========================================================
-- 增量迁移：管理类接口权限码补齐（P0-3 授权覆盖）
-- 适用环境：已上线存量库（不会重跑 data.sql）
-- 特性：幂等，可重复执行
-- 说明：新增的 5 个权限码默认只授予 admin（admin 为全量授权），
--       editor/viewer 维持原有权限不变，避免误放开管理能力。
-- ========================================================

INSERT INTO wgh_permission (perm_code, perm_name, resource_type) VALUES
('chat:manage',    '管理聊天窗口',     'CHAT'),
('agent:manage',   '管理多Agent编排',  'AGENT'),
('apikey:manage',  '管理开放APIKey',   'API_KEY'),
('share:manage',   '管理分享链接',     'SHARE_LINK'),
('pricing:manage', '管理模型价格',     'MODEL_PRICE')
ON CONFLICT (perm_code) DO NOTHING;

-- admin 角色补齐全部权限（含本次新增的 5 个）
INSERT INTO wgh_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM wgh_role r
CROSS JOIN wgh_permission p
WHERE r.role_code = 'admin'
ON CONFLICT DO NOTHING;
