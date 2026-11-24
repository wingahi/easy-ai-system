-- ========================================================
-- 迁移：多租户阶段 3（平台级向量表数量计数函数）
-- 状态：**已编写，未在 dev 执行**；执行前需回到调用方确认窗口。
-- 不登记到 kustomize / run-migrations.sh，不随迁移 Job 自动执行。
--
-- 背景：RetrievalMetricsGauge 需要跨租户统计 wgh_knowledge_base 的向量表数量。
--   - 应用切到 wgh_app 后，无 GUC 的直查会被 RLS 过滤为 0 行（指标静默归零）；
--   - 用租户事务只会统计默认租户；
--   - 决策：不改动 wgh_platform 的行级只读授权，改为 SECURITY DEFINER 函数只暴露 count。
--
-- 安全边界：
--   1. 函数无参数，只返回 BIGINT，不返回任何行、不接受租户输入；
--   2. SECURITY DEFINER 所有者必须是超级用户（默认 postgres），否则 FORCE RLS 对 owner
--      同样生效，函数会静默返回 0；脚本开头有执行角色守卫；
--   3. search_path 固定为 pg_catalog, public，且表名 schema 限定，避免调用方注入；
--   4. 默认 EXECUTE 授权给 PUBLIC 已撤销，仅授予 wgh_app；
--   5. wgh_app 对业务表的直接 SELECT 仍受 RLS 约束，本函数不改变该边界。
--
-- 口径：与改造前 MyBatis-Plus selectCount 一致——
--   collection_name IS NOT NULL 且 deleted = 0（@TableLogic 自动过滤已删除行）。
--
-- 回滚：
--   REVOKE EXECUTE ON FUNCTION public.wgh_kb_vector_table_count() FROM wgh_app;
--   DROP FUNCTION IF EXISTS public.wgh_kb_vector_table_count();
--
-- 执行后核对：
--   SELECT prosecdef, proowner::regrole, proconfig
--   FROM pg_proc WHERE proname = 'wgh_kb_vector_table_count';
--   -- 期望: prosecdef = t, proowner = postgres, proconfig = {search_path=pg_catalog, public}
--   BEGIN;
--   SET LOCAL ROLE wgh_app;
--   SELECT public.wgh_kb_vector_table_count();
--   ROLLBACK;
-- ========================================================

BEGIN;

-- 0. 执行角色守卫：SECURITY DEFINER 所有者必须是超级用户
DO $$
DECLARE
  is_super boolean;
BEGIN
  SELECT r.rolsuper INTO is_super FROM pg_roles r WHERE r.rolname = current_user;
  IF is_super IS NOT TRUE THEN
    RAISE EXCEPTION '必须以超级用户（默认 postgres）执行本迁移：当前角色 % 不是超级用户，'
      'SECURITY DEFINER 函数会受 FORCE RLS 约束并静默返回 0', current_user;
  END IF;
END $$;

-- 1. 计数函数：只返回平台全租户的向量表数量
CREATE OR REPLACE FUNCTION public.wgh_kb_vector_table_count()
RETURNS bigint
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
  SELECT count(*)::bigint
  FROM public.wgh_knowledge_base
  WHERE collection_name IS NOT NULL
    AND deleted = 0
$$;

-- 2. 所有者固定为 postgres（执行守卫已保证当前用户是超级用户；如环境 owner 不同，
--    请改为实际超级用户，禁止改成普通业务角色）
ALTER FUNCTION public.wgh_kb_vector_table_count() OWNER TO postgres;

-- 3. 授权：撤销 PUBLIC 默认 EXECUTE，仅应用角色可调用
REVOKE ALL ON FUNCTION public.wgh_kb_vector_table_count() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.wgh_kb_vector_table_count() TO wgh_app;

COMMENT ON FUNCTION public.wgh_kb_vector_table_count() IS
  '平台级向量表数量（跨租户 count，仅返回数字；SECURITY DEFINER，禁止授予普通业务角色之外的调用方）';

COMMIT;
