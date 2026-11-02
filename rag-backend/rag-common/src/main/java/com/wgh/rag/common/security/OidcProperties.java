package com.wgh.rag.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OIDC 登录配置属性
 * <p>
 * 默认 enabled=false，未配置时 /api/auth/oidc/** 入口返回 503。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.auth.oidc")
public class OidcProperties {

    /** 是否启用 OIDC 登录（默认 false） */
    private boolean enabled = false;

    /** OIDC Issuer（例如 https://accounts.google.com） */
    private String issuer;

    /** OAuth2 Client ID */
    private String clientId;

    /** OAuth2 Client Secret */
    private String clientSecret;

    /** 请求 scopes（默认 openid email profile） */
    private List<String> scopes = new ArrayList<>(List.of("openid", "email", "profile"));

    /** 回调地址（必须与 IdP 配置完全一致） */
    private String redirectUri;

    /** state / nonce 有效期（秒） */
    private long stateTtlSeconds = 300;

    /**
     * IdP groups/roles → 本系统角色映射。key = IdP 声明值；value = admin / editor / viewer。
     */
    private Map<String, String> roleMapping = new HashMap<>();

    /** 未命中 roleMapping 时的默认角色 */
    private String defaultRole = "viewer";

    /** 是否启用 PKCE（推荐） */
    private boolean enablePkce = true;
}
