package com.wgh.rag.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本地会话凭证（内部 JWT）签发与校验。
 * <p>
 * 与外部 OIDC token 的隔离方式：
 * <ol>
 *   <li>本服务只签发 HS256 对称签名凭证；外部 {@link OidcTokenVerifier} 算法白名单拒绝 HS256。</li>
 *   <li>本服务只接受 HS256，并强制校验本地 issuer/audience/tokenType=local；
 *       外部 IdP 的 RS/PS/ES 凭证在此直接拒绝。</li>
 * </ol>
 * 因此本地密钥无法伪造外部 token，外部 token 也无法冒充本地会话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalTokenService {

    private static final String LOCAL_ALGORITHM = "HS256";
    private static final String TOKEN_TYPE = "local";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    /** 口令版本号 claim：与数据库 wgh_user.password_version 比对，不一致则凭证失效 */
    public static final String CLAIM_PASSWORD_VERSION = "pv";

    private final LocalAuthProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile SecretKey signingKey;

    /**
     * 签发本地会话凭证
     */
    public IssuedToken issue(String username, Long userId, Set<String> roles, Integer passwordVersion) {
        Date now = new Date();
        long ttlSeconds = Math.max(60L, properties.getTokenTtlSeconds());
        Date expiration = new Date(now.getTime() + ttlSeconds * 1000L);

        String token = Jwts.builder()
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .subject(username)
                .claim("userId", userId)
                .claim("roles", roles == null ? Set.of() : new LinkedHashSet<>(roles))
                .claim(CLAIM_PASSWORD_VERSION, passwordVersion == null ? 1 : passwordVersion)
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(currentKey(), Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, ttlSeconds);
    }

    /**
     * 校验本地会话凭证；任何不合法都抛 {@link TokenVerificationException}
     */
    public Claims verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw new TokenVerificationException("本地会话凭证为空");
        }
        String algorithm = readAlgorithm(token);
        if (!LOCAL_ALGORITHM.equals(algorithm)) {
            throw new TokenVerificationException("本地会话凭证算法非法: " + algorithm);
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(currentKey())
                    .requireIssuer(properties.getIssuer())
                    .require(CLAIM_TOKEN_TYPE, TOKEN_TYPE)
                    .clockSkewSeconds(Math.max(0L, properties.getClockSkewSeconds()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(properties.getAudience())) {
                throw new TokenVerificationException("本地会话凭证 audience 不匹配");
            }
            return claims;
        } catch (TokenVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenVerificationException("本地会话凭证无效: " + e.getMessage());
        }
    }

    /**
     * 是否为本地会话凭证（按 header alg 区分来源，避免把外部 token 送进本地验签）
     */
    public boolean isLocalToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            return LOCAL_ALGORITHM.equals(readAlgorithm(token));
        } catch (Exception e) {
            return false;
        }
    }

    private String readAlgorithm(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            throw new TokenVerificationException("凭证格式非法");
        }
        try {
            byte[] headerBytes = Base64.getUrlDecoder().decode(token.substring(0, dot));
            JsonNode header = objectMapper.readTree(new String(headerBytes, StandardCharsets.UTF_8));
            return header.path("alg").asText("");
        } catch (Exception e) {
            throw new TokenVerificationException("凭证 header 解析失败");
        }
    }

    private SecretKey currentKey() {
        SecretKey key = signingKey;
        if (key == null) {
            synchronized (this) {
                if (signingKey == null) {
                    signingKey = buildKey();
                }
                key = signingKey;
            }
        }
        return key;
    }

    private SecretKey buildKey() {
        String configured = properties.getJwtSecret();
        if (StringUtils.hasText(configured)) {
            byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("wgh.auth.local.jwt-secret 长度至少 32 字节");
            }
            return Keys.hmacShaKeyFor(bytes);
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        log.warn("wgh.auth.local.jwt-secret 未配置，已生成临时随机密钥；重启后本地会话全部失效，生产环境请配置固定密钥");
        return Keys.hmacShaKeyFor(random);
    }

    /**
     * 签发结果
     */
    public record IssuedToken(String token, long expiresInSeconds) {
    }
}
