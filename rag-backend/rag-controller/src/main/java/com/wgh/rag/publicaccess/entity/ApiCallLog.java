package com.wgh.rag.publicaccess.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 开放 API 调用日志（谁、什么接口、状态码、耗时）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_api_call_log")
public class ApiCallLog extends BaseEntity {

    private Long apiKeyId;

    private String apiKeyName;

    /** 记录调用主体（api_{keyId}） */
    private String username;

    private String method;

    private String path;

    private Integer statusCode;

    private Long durationMs;

    private String ip;
}
