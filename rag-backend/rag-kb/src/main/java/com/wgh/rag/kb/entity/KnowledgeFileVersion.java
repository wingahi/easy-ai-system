package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文件版本快照（元数据留存，不回滚向量）
 */
@Data
@TableName("wgh_knowledge_file_version")
public class KnowledgeFileVersion implements Serializable {

    private Long id;

    /**
     * 所属文件
     */
    private Long fileId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 该版本切分后的 chunk 数
     */
    private Integer chunkCount;

    /**
     * 切分策略
     */
    private String splitStrategy;

    /**
     * 切分策略配置 JSON
     */
    private String splitConfig;

    /**
     * 状态：SUCCESS / FAILED / PROCESSING
     */
    private String status;

    /**
     * 变更说明
     */
    private String remark;

    private LocalDateTime createTime;
}
