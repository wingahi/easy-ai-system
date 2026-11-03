package com.wgh.rag.auth.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 本地账号密码登录配置。
 * <p>
 * 本地登录仅向 {@code wgh_user.source = local} 的本地管理员/体验账号开放；
 * 登录成功后签发内部会话凭证（HS256），与外部 IdP 的 OIDC token（非对称验签）严格隔离。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.auth.local")
public class LocalAuthProperties {

    /** 是否启用本地账号密码登录 */
    private boolean enabled = true;

    /**
     * 内部会话凭证签名密钥（至少 32 字节）。
     * 留空时启动用随机密钥（仅开发方便，重启后本地会话失效），生产必须配置。
     */
    private String jwtSecret;

    /** 本地会话凭证有效期（秒），默认 2 小时 */
    private long tokenTtlSeconds = 7200;

    /** 本地会话凭证 issuer（与外部 IdP 的 issuer 区分） */
    private String issuer = "wgh-local";

    /** 本地会话凭证 audience */
    private String audience = "wgh-local";

    /** exp / nbf 校验的时钟偏差容忍（秒） */
    private long clockSkewSeconds = 30;
}
