-- ========================================================
-- 迁移：资源归属与可见性（visibility）—— 存量库专用
-- 适用对象：已存在 wgh_* 五张业务表的存量库（2026-11-12 之前创建）
-- 新库不要执行本脚本：sql/schema/01-schema.sql 的建表语句已包含 visibility 列与索引
-- 执行顺序：先确认库已升级到基线（01-schema.sql 对应版本），再单独执行本脚本
--   1) 新库：psql -f sql/schema/01-schema.sql -f sql/seed/02-data.sql
--   2) 存量库：psql -f sql/migrations/20261113/01_resource_visibility.sql
-- 幂等性：全部语句使用 IF NOT EXISTS，可重复执行；重复执行时回填 UPDATE 只影响仍未置为 PUBLIC 的存量行
-- 影响面：5 张表新增列（默认 PRIVATE，历史行全部补为 PRIVATE）、5 个联合索引、
--        工具示例（is_example = 1）与内置 Prompt 模板（builtin = 1）回填为 PUBLIC
-- 归属提醒：历史数据的 create_by 多为 'system'，升级后这些资源默认仅管理员可见；
--          需要放开时由管理员逐个改归属或可见性，本脚本不做归属改写
-- 说明：本脚本只补列与索引，不写列注释（列注释以 01-schema.sql 为准，需要时可手工 COMMENT）
-- ========================================================

BEGIN;

-- 1. 五张业务表补 visibility 列（类型/默认值/非空与基线一致）
ALTER TABLE wgh_knowledge_base ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE wgh_chat_window ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE wgh_tool ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE wgh_workflow ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE wgh_prompt_template ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';

-- 2. 列表/分页过滤使用 (create_by, visibility) 联合索引，列顺序与基线一致
CREATE INDEX IF NOT EXISTS idx_kb_owner_visibility ON wgh_knowledge_base(create_by, visibility);
CREATE INDEX IF NOT EXISTS idx_cw_owner_visibility ON wgh_chat_window(create_by, visibility);
CREATE INDEX IF NOT EXISTS idx_tool_owner_visibility ON wgh_tool(create_by, visibility);
CREATE INDEX IF NOT EXISTS idx_workflow_owner_visibility ON wgh_workflow(create_by, visibility);
CREATE INDEX IF NOT EXISTS idx_prompt_template_owner_visibility ON wgh_prompt_template(create_by, visibility);

-- 3. 回填：内置资源对所有用户可读（与 01-schema.sql 种子语义一致）
--    工具：内置示例工具
UPDATE wgh_tool SET visibility = 'PUBLIC' WHERE is_example = 1;
--    Prompt 模板：内置模板固定 PUBLIC 且只读
UPDATE wgh_prompt_template SET visibility = 'PUBLIC' WHERE builtin = 1;

COMMIT;
