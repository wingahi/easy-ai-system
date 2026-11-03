package com.wgh.rag.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_user")
public class WghUser extends BaseEntity {

    /**
     * 登录用户名
     */
    private String username;

    /**
     * 密码哈希（BCrypt / Argon2）
     */
    private String passwordHash;

    /**
     * 真实姓名
     */
    private String name;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 租户 ID（schema 定义为 VARCHAR(64)，如 "default"）
     */
    private String tenantId;

    /**
     * 状态：1-激活，0-禁用
     */
    private Integer status;

    /**
     * OIDC subject（第三方登录）
     */
    private String oidcSubject;

    /**
     * OIDC issuer（第三方登录颁发者）
     */
    private String oidcIssuer;
}
