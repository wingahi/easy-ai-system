-- ========================================================
-- 增量迁移：存量 "default_user" 会话归属到 admin（P0-1）
-- 适用环境：已上线存量库
-- 特性：幂等，可重复执行（首次执行后不再存在 default_user 记录）
-- 说明：执行前请确认 admin 为实际管理员账号用户名；如需改为其他账号请替换下方字面量。
-- ========================================================

UPDATE wgh_chat_session
SET user_identifier = 'admin'
WHERE user_identifier = 'default_user';
