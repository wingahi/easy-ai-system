package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * URL 连接器单次同步运行记录
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_kb_connector_run")
public class KbConnectorRun extends BaseEntity {

    /**
     * 所属连接器 ID
     */
    private Long connectorId;

    /**
     * 运行状态：SUCCESS / UNCHANGED / REMOVED / FAILED
     */
    private String status;

    /**
     * 抓取返回的 HTTP 状态码
     */
    private Integer httpStatus;

    /**
     * 本次同步产生/命中的知识库文件 ID
     */
    private Long fileId;

    /**
     * 本次正文 SHA-256
     */
    private String contentHash;

    /**
     * 文件切分 chunk 数（异步处理完成前可能为 0）
     */
    private Integer chunkCount;

    /**
     * 结果说明 / 失败原因
     */
    private String message;

    /**
     * 耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间
     */
    private LocalDateTime finishedAt;
}
