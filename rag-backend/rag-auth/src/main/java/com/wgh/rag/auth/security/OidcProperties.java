package com.wgh.rag.auth.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 外部 OIDC 资源服务器配置。
 * <p>
 * 本系统只验证外部 IdP 签发的 access/id token，不签发任何 token。
 * 必填：{@code issuer} + {@code audience}；{@code jwks-uri} 可留空，留空时按
 * {@code issuer}/.well-known/openid-configuration 自动发现。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.auth.oidc")
public class OidcProperties {

    /** 期望的 issuer（token 的 iss 必须与之一致） */
    private String issuer;

    /** 期望的 audience（token 的 aud 必须包含该值） */
    private String audience;

    /** JWKS 端点；留空时通过 issuer 自动发现 */
    private String jwksUri;

    /** token 中承载角色列表的 claim 名（默认 roles） */
    private String rolesClaim = "roles";

    /** token 中承载用户名的 claim 名（默认 sub） */
    private String usernameClaim = "sub";

    /** token 未携带角色或角色在数据库不存在时的默认角色编码 */
    private String defaultRole = "viewer";

    /**
     * claim 角色值 → 数据库 role_code 映射。
     * 未命中映射的 claim 值按原值直接匹配 role_code。
     */
    private Map<String, String> roleMapping = new HashMap<>();

    /** JWKS 本地缓存有效期（秒） */
    private long jwksCacheTtlSeconds = 300;

    /** 未知 kid / 网络失败触发刷新时的最小间隔（秒），避免被伪造 kid 打爆 */
    private long jwksRefreshMinIntervalSeconds = 30;

    /** exp / nbf 校验的时钟偏差容忍（秒） */
    private long clockSkewSeconds = 30;
}
