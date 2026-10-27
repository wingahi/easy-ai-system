-- ========================================================
-- WGH RAG System 数据库一键初始化入口
-- PostgreSQL 15+ with pgvector
--
-- 本文件 = schema.sql（完整 DDL + 索引 + COMMENT）
--        + data.sql（种子数据 + 老库兼容修复） 的串联包装
-- 所有历史 migration 已合入，migration_*.sql 已删除
--
-- 用法：
--   1) 首次部署请先建库：CREATE DATABASE wgh_rag;
--   2) 一键初始化：psql -d wgh_rag -f init.sql
--   3) 只建结构：  psql -d wgh_rag -f schema.sql
--   4) 只灌种子：  psql -d wgh_rag -f data.sql
-- ========================================================

\ir schema.sql;
\ir data.sql;
