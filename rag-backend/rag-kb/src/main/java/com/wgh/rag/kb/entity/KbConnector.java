package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * URL / HTTP 数据源连接器配置
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_kb_connector")
public class KbConnector extends BaseEntity {

    /**
     * 所属知识库 ID
     */
    private Long knowledgeBaseId;

    /**
     * 连接器名称
     */
    private String name;

    /**
     * 抓取地址（仅 http/https）
     */
    private String url;

    /**
     * 入库切分策略
     */
    private String splitStrategy;

    /**
     * 切分策略配置（JSON）
     */
    private String splitConfig;

    /**
     * 自动同步间隔（分钟），0 表示仅手动同步
     */
    private Integer syncIntervalMinutes;

    /**
     * 是否启用自动同步：1-启用，0-停用
     */
    private Integer enabled;

    /**
     * 最近一次同步生成/命中的知识库文件 ID
     */
    private Long lastFileId;

    /**
     * 最近一次抓取正文的 SHA-256
     */
    private String lastContentHash;

    /**
     * 最近一次同步时间
     */
    private LocalDateTime lastSyncTime;

    /**
     * 最近一次同步状态：SUCCESS / UNCHANGED / REMOVED / FAILED
     */
    private String lastStatus;

    /**
     * 最近一次失败原因
     */
    private String lastError;
}
