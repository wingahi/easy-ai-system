package com.wgh.rag.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * JWT 工具类
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${wgh.jwt.secret:wgh-rag-system-secret-key-must-be-at-least-256-bits-long}")
    private String secret;

    @Value("${wgh.jwt.expiration:86400000}")
    private Long expiration;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Token（不带 roles，兼容旧调用）
     */
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, null);
    }

    /**
     * 生成 Token（携带 roles claim）
     */
    public String generateToken(Long userId, String username, Collection<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("roles", roles == null ? new ArrayList<>() : new ArrayList<>(roles));

        return Jwts.builder()
                .subject(username)
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 验证 Token
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (MalformedJwtException e) {
            log.warn("Token格式错误: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            log.warn("Token已过期: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的Token类型: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Token参数非法: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从Token中获取Claim
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("userId", Long.class);
    }

    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    /**
     * 从 Token 中获取 roles claim；缺失或类型异常时返回空集合（由调用方回退默认角色）
     */
    public Set<String> getRolesFromToken(String token) {
        Set<String> roles = new HashSet<>();
        try {
            Claims claims = parseToken(token);
            Object raw = claims.get("roles");
            if (raw instanceof Collection<?> values) {
                for (Object value : values) {
                    if (value != null) {
                        roles.add(String.valueOf(value));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析Token roles失败: {}", e.getMessage());
        }
        return roles;
    }
}
