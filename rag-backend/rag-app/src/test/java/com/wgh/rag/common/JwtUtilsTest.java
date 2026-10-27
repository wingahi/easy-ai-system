package com.wgh.rag.common;

import com.wgh.rag.common.security.JwtUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtils 单元测试（不启动 Spring 容器，手动构造实例）
 */
@DisplayName("JWT 工具类测试")
class JwtUtilsTest {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        // 通过反射设置私有字段
        injectField(jwtUtils, "secret", "wgh-rag-system-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm");
        injectField(jwtUtils, "expiration", 86400000L);
    }

    private void injectField(Object obj, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("生成 Token - 不为空")
    void generateToken_notNull() {
        String token = jwtUtils.generateToken(1L, "admin");
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // JWT 三段式
    }

    @Test
    @DisplayName("Token 验证 - 有效 Token")
    void validateToken_valid() {
        String token = jwtUtils.generateToken(42L, "testuser");
        assertThat(jwtUtils.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("Token 验证 - 无效 Token 串")
    void validateToken_invalidFormat() {
        assertThat(jwtUtils.validateToken("not-a-jwt-token")).isFalse();
    }

    @Test
    @DisplayName("Token 验证 - 空串")
    void validateToken_empty() {
        assertThat(jwtUtils.validateToken("")).isFalse();
        assertThat(jwtUtils.validateToken(null)).isFalse();
    }

    @Test
    @DisplayName("Token 解析 - userId 和 username 正确")
    void parseToken_extractClaims() {
        Long userId = 88L;
        String username = "wgh-test";
        String token = jwtUtils.generateToken(userId, username);

        Claims claims = jwtUtils.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(username);
        assertThat(claims.get("userId", Long.class)).isEqualTo(userId);
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    @DisplayName("getUserIdFromToken / getUsernameFromToken")
    void extractMethods_work() {
        String token = jwtUtils.generateToken(99L, "extract-user");
        assertThat(jwtUtils.getUserIdFromToken(token)).isEqualTo(99L);
        assertThat(jwtUtils.getUsernameFromToken(token)).isEqualTo("extract-user");
    }

    @Test
    @DisplayName("不同用户生成的 Token 不同")
    void differentUser_differentToken() {
        String t1 = jwtUtils.generateToken(1L, "user1");
        String t2 = jwtUtils.generateToken(2L, "user2");
        assertThat(t1).isNotEqualTo(t2);
    }
}
