package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 大文件分片上传会话（断点续传）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_upload_session")
public class UploadSession extends BaseEntity {

    /** 知识库 ID */
    private Long knowledgeBaseId;

    /** 租户编码（分片存储目录的隔离维度；存量数据为 default） */
    private String tenantId;

    /** 上传会话 UUID，同时作为分片存储目录名 */
    private String uploadUuid;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型（扩展名大写） */
    private String fileType;

    /** 文件总大小（字节） */
    private Long fileSize;

    /** 分片大小（字节） */
    private Integer chunkSize;

    /** 分片总数 */
    private Integer totalChunks;

    /** 已完成分片序号 JSON 数组 */
    private String uploadedChunks;

    /** 会话状态：UPLOADING / COMPLETED / ABORTED */
    private String status;

    /** 合并入库后生成的 wgh_knowledge_file.id */
    private Long fileId;

    /** 入库切分策略 */
    private String splitStrategy;

    /** 切分策略配置 JSON */
    private String splitConfig;
}
