package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库 chunk 事实层（wgh_kb_chunk）。
 *
 * <p>原文与元数据的唯一事实来源；向量与关键词索引都是可重建的派生数据。
 * 双写由 {@code wgh.vector.fact-layer-enabled} 控制，默认关闭。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_kb_chunk")
public class KbChunk extends BaseEntity {

    /** 租户标识（单租户为 default）。 */
    private String tenantId;

    private Long kbId;

    private Long fileId;

    private Integer chunkIndex;

    /** chunk 原文。 */
    private String content;

    /** 原文 SHA-256，用于去重与对账。 */
    private String contentHash;

    /** chunk 元数据（JSON 文本，检索过滤与来源定位）。 */
    private String metadataJson;

    /** 派生索引中的向量 ID；写入成功前为空。 */
    private String embeddingId;

    private Long embeddingModelId;

    private Integer dimension;

    /** PENDING / SYNCED / STALE。 */
    private String vectorStatus;

    private Integer version;
}
