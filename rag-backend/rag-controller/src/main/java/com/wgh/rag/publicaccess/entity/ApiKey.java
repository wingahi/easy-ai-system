package com.wgh.rag.publicaccess.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import com.wgh.rag.data.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 开放 API Key（明文只在创建时返回一次，库里只存 SHA-256）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_api_key")
public class ApiKey extends BaseEntity {

    /** 明文前缀（便于后台识别，如 wgh_sk_ab12） */
    private String keyPrefix;

    /** SHA-256(明文) */
    private String keyHash;

    /** 名称 */
    private String name;

    /** 权限范围，逗号分隔：chat:write,kb:read,usage:read；* 表示全部 */
    private String scopes;

    /** 过期时间（为空表示长期有效） */
    private LocalDateTime expiresAt;

    /** 是否已吊销 */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean revoked;

    /** 创建人 */
    private String createdBy;

    /** 最近调用时间 */
    private LocalDateTime lastUsedTime;

    /** IP 白名单（逗号分隔 CIDR，为空表示不限制来源 IP） */
    private String ipWhitelist;
}
