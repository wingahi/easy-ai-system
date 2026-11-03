package com.wgh.rag.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

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

    /**
     * 账号来源：local=本地账号（本地管理员/体验账号，可密码登录），oidc=外部 OIDC（仅 token 进入）
     */
    private String source;

    /**
     * 是否体验账号：1-是，0-否
     */
    private Integer trial;

    /**
     * 账号有效期，null 表示长期有效；到期后不可登录
     */
    private LocalDateTime expireAt;

    /**
     * 备注
     */
    private String remark;

    /**
     * 是否必须先改密：1-是（初始化创建/管理员重置的账号），0-否
     */
    private Integer passwordMustChange;
}
