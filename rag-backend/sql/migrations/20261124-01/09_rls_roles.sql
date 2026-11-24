-- ========================================================
-- 迁移：多租户阶段 3（RLS 数据库角色与授权）
-- 状态：评审稿，**未执行**；本文件不登记到 kustomize / run-migrations.sh。
-- 执行前提：以表 owner（默认 postgres）执行；应用连接用户由部署侧创建并注入口令。
-- 幂等性：角色存在性检查 + ALTER ROLE 收敛属性 + GRANT 可重复执行。
--
-- 角色划分（已拍板）：
--   wgh_app       应用连接角色：非 owner、非 SUPERUSER、非 BYPASSRLS、NOINHERIT；
--                 租户隔离靠事务级 GUC（set_config('app.tenant_id', ?, true)）。
--   wgh_platform  平台运维：租户目录、审计/调用日志、平台内置 Prompt/Tool。
--   wgh_scheduler 调度作业 claim/心跳：跨租户访问调度 3 表。
--   wgh_preauth   免登录预认证：仅 share_link / api_key 只读。
--
-- 关键约定：
--   1. wgh_app 为 NOINHERIT，成员关系不会自动带出平台/调度/预认证权限；
--      需要时必须显式 `SET LOCAL ROLE ...`，事务结束自动还原，禁止会话级 SET ROLE。
--   2. 本脚本不创建可登录用户，也不保存口令。部署侧执行：
--        ALTER ROLE wgh_app LOGIN PASSWORD :'wgh_app_password';
--      并把 SPRING_DATASOURCE_USERNAME 切到 wgh_app。
--   3. 迁移/建表/回填仍用 owner（postgres）执行，不切 wgh_app。
-- ========================================================

BEGIN;

-- 1. 创建角色（集群级；重复执行安全）
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wgh_app') THEN
    CREATE ROLE wgh_app NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wgh_platform') THEN
    CREATE ROLE wgh_platform NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wgh_scheduler') THEN
    CREATE ROLE wgh_scheduler NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wgh_preauth') THEN
    CREATE ROLE wgh_preauth NOLOGIN;
  END IF;
END $$;

-- 2. 收敛角色属性（防止环境里已存在同名高权限角色）
ALTER ROLE wgh_app       NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE wgh_platform  NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE wgh_scheduler NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE wgh_preauth   NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- 3. 成员关系：wgh_app 可切换角色，但不自动继承权限
GRANT wgh_platform  TO wgh_app;
GRANT wgh_scheduler TO wgh_app;
GRANT wgh_preauth   TO wgh_app;

COMMENT ON ROLE wgh_app       IS '多租户应用连接角色（非 owner/非 superuser/非 bypassrls；事务级 app.tenant_id）';
COMMENT ON ROLE wgh_platform  IS '平台运维角色：租户目录、审计/调用日志、平台内置 Prompt/Tool';
COMMENT ON ROLE wgh_scheduler IS '调度角色：跨租户 claim/心跳调度 3 表';
COMMENT ON ROLE wgh_preauth   IS '免登录预认证角色：仅 share_link/api_key 只读';

-- 4. 连接与 schema 使用权限
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO wgh_app, wgh_platform, wgh_scheduler, wgh_preauth',
                 current_database());
END $$;
GRANT USAGE ON SCHEMA public TO wgh_app, wgh_platform, wgh_scheduler, wgh_preauth;

-- 5. wgh_app：业务 DML（RLS 策略另行控制可见行）
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wgh_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO wgh_app;

-- 后续由 owner 新建的表/序列保持同等权限（若 owner 非当前执行角色，需由 owner 执行本段）
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO wgh_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO wgh_app;

-- 6. wgh_platform：只读审计/日志/公开访问；租户与平台内置资源可写
GRANT SELECT ON wgh_tenant, wgh_audit_log, wgh_api_call_log, wgh_share_link, wgh_api_key TO wgh_platform;
GRANT SELECT, INSERT, UPDATE, DELETE ON wgh_tenant, wgh_prompt_template, wgh_tool TO wgh_platform;
GRANT USAGE, SELECT ON SEQUENCE
  wgh_tenant_id_seq,
  wgh_prompt_template_id_seq,
  wgh_tool_id_seq
TO wgh_platform;

-- 7. wgh_scheduler：仅调度 3 表 + 实例心跳，不授予业务表
GRANT SELECT, INSERT, UPDATE, DELETE ON
  wgh_execution_job,
  wgh_tenant_schedule_policy,
  wgh_tenant_runtime_slot,
  wgh_scheduler_instance
TO wgh_scheduler;
GRANT USAGE, SELECT ON SEQUENCE
  wgh_execution_job_id_seq,
  wgh_tenant_schedule_policy_id_seq
TO wgh_scheduler;

-- 8. wgh_preauth：免登录链路只读
GRANT SELECT ON wgh_share_link, wgh_api_key TO wgh_preauth;

COMMIT;

-- ========================================================
-- 部署侧手工步骤（不在本脚本内执行，避免口令入库）：
--   ALTER ROLE wgh_app LOGIN PASSWORD '<secret>';
--   # 若使用独立登录用户，则：
--   # CREATE ROLE wgh_app_login LOGIN PASSWORD '<secret>';
--   # GRANT wgh_app TO wgh_app_login;   -- 登录用户继承 wgh_app 的直接权限
--   # 不要把 wgh_platform/wgh_scheduler/wgh_preauth 授予登录用户。
--   # 应用侧通过 SET LOCAL ROLE 切换（见设计文档 §3.5.1.10）。
--
-- 回滚：角色与授权可保留；如需撤销，先执行 14_rls_rollback.sql，
--       再按 REVOKE ... / DROP ROLE 反向操作（需确认无连接使用）。
-- ========================================================
