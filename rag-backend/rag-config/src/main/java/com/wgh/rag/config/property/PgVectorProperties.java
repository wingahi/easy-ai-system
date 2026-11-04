package com.wgh.rag.config.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PostgreSQL PGVector 向量存储配置
 * <p>
 * 注意：连接参数（host/port/user/password）由 Spring DataSource（application.yml）统一管理，
 * 本配置只覆盖向量相关的建表/索引/维度等业务参数。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.pgvector")
public class PgVectorProperties {

    /**
     * 默认向量维度（嵌入模型不同维度不同，知识库创建时可覆盖）
     */
    private Integer defaultDimension = 1536;

    /**
     * 是否自动创建 pgvector 表（首次启动建表）
     */
    private Boolean createTable = true;

    /**
     * 是否跳过 CREATE EXTENSION vector（若 DB 侧已装好 pgvector 扩展可设为 true）
     * <p>
     * LangChain4j 会在启动时尝试 CREATE EXTENSION IF NOT EXISTS vector，
     * 如果 DB 没有 superuser 权限或扩展未安装，可设为 true 跳过（需要 DBA 预先执行）
     */
    private Boolean skipCreateVectorExtension = false;

    /**
     * LangChain4j 内置 IVFFlat 索引开关（useIndex=true 时其建表逻辑会创建 ivfflat 索引）。
     * <p>
     * 本项目统一使用 HNSW（见 {@link #useHnswIndex}），保持 false 以避免同一向量列上出现两套 ANN 索引。
     */
    private Boolean useIndex = false;

    /**
     * IVFFlat nlist 数量（useIndex=true 时生效，建议 sqrt(行数) ~ 100-10000）
     */
    private Integer indexListSize = 100;

    /**
     * 是否在向量表上创建 HNSW 索引（cosine 距离）。
     * <p>
     * 知识库建表/入库时按 {@code CREATE INDEX IF NOT EXISTS ... USING hnsw (embedding vector_cosine_ops)}
     * 幂等创建；已存在的历史表可通过 schema.sql 末尾的补建脚本或人工 SQL 一次性补齐。
     */
    private Boolean useHnswIndex = true;

    /**
     * HNSW 每个节点的最大邻居数（pgvector 默认 16）
     */
    private Integer hnswM = 16;

    /**
     * HNSW 构建时的候选邻居列表大小（越大召回越好、建索引越慢，pgvector 默认 64）
     */
    private Integer hnswEfConstruction = 64;

    /**
     * 进程内 EmbeddingStore 句柄缓存上限（按 表名+维度 维度隔离，LRU 淘汰）。
     * 句柄本身只持有 DataSource 与表名，内存占用很小；64 足够覆盖常见在建知识库数量。
     */
    private Integer storeCacheMaxSize = 64;

    /**
     * 默认向量表名（知识库维度不同时会按 kbId 分表）
     */
    private String defaultTableName = "rag_vector_store";

    /**
     * 启动时是否验证 PostgreSQL + pgvector 可用
     */
    private Boolean connectOnStartup = true;

    /**
     * 是否允许在 pgvector 不可用时降级为内存存储（仅开发/测试环境）
     */
    private Boolean allowFallbackToInMemory = false;
}
