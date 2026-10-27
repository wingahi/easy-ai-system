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
     * 是否使用 IVFFlat 索引加速查询（大数据量 > 10 万条时建议开启）
     */
    private Boolean useIndex = false;

    /**
     * IVFFlat nlist 数量（useIndex=true 时生效，建议 sqrt(行数) ~ 100-10000）
     */
    private Integer indexListSize = 100;

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
