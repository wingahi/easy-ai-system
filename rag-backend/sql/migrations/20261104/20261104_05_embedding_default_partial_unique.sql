-- ========================================================
-- 20261104 embedding 默认模型约束修正（面向存量库）
-- 旧约束 CONSTRAINT uk_embedding_default UNIQUE NULLS NOT DISTINCT (is_default)
-- 是对 is_default 整列唯一，实际只允许两条记录（一个 0、一个 1），
-- 导致第二个以上的非默认嵌入模型无法新增。
-- 新库基线 schema/01-schema.sql 已改为部分唯一索引，无需执行本文件。
-- 执行前提：当前库中 is_default = 1 的记录至多一条（旧约束已保证）。
-- 全文幂等，可重复执行：约束已删则跳过；索引已存在则跳过。
-- ========================================================

ALTER TABLE wgh_embedding_model DROP CONSTRAINT IF EXISTS uk_embedding_default;

CREATE UNIQUE INDEX IF NOT EXISTS uk_embedding_default ON wgh_embedding_model(is_default) WHERE is_default = 1;
