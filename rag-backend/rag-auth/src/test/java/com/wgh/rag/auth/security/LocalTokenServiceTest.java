package com.wgh.rag.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地会话凭证与外部 OIDC 凭证的隔离测试（离线，不依赖 IdP）。
 */
@DisplayName("本地会话凭证签发/校验")
class LocalTokenServiceTest {

    private LocalTokenService localTokenService;
    private OidcTokenVerifier oidcTokenVerifier;

    @BeforeEach
    void setUp() {
        LocalAuthProperties local = new LocalAuthProperties();
        local.setJwtSecret("unit-test-local-secret-0123456789abcdef0123456789");
        local.setIssuer("wgh-local");
        local.setAudience("wgh-local");
        localTokenService = new LocalTokenService(local);

        OidcProperties oidc = new OidcProperties();
        oidc.setIssuer("https://idp.example.com");
        oidc.setAudience("wgh-rag");
        oidc.setJwksUri("https://idp.example.com/jwks");
        oidcTokenVerifier = new OidcTokenVerifier(oidc, new JwksProvider(oidc));
    }

    @Test
    @DisplayName("签发后可校验，claims 含 userId/roles/tokenType=local")
    void issueThenVerify() {
        LocalTokenService.IssuedToken issued =
                localTokenService.issue("admin", 1L, Set.of("admin"), 1);

        Claims claims = localTokenService.verify(issued.token());

        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.get("userId", Long.class)).isEqualTo(1L);
        assertThat(claims.get("roles", List.class)).containsExactly("admin");
        assertThat(claims.get("tokenType", String.class)).isEqualTo("local");
        assertThat(localTokenService.isLocalToken(issued.token())).isTrue();
    }

    @Test
    @DisplayName("篡改本地凭证签名后被拒绝")
    void tamperedLocalTokenRejected() {
        String token = localTokenService.issue("admin", 1L, Set.of("admin"), 1).token();
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThatThrownBy(() -> localTokenService.verify(tampered))
                .isInstanceOf(TokenVerificationException.class);
    }

    @Test
    @DisplayName("本地凭证不能当作外部 OIDC token 使用（外部算法白名单拒绝 HS256）")
    void localTokenRejectedByOidcVerifier() {
        String token = localTokenService.issue("admin", 1L, Set.of("admin"), 1).token();

        assertThatThrownBy(() -> oidcTokenVerifier.verify(token))
                .isInstanceOf(TokenVerificationException.class)
                .hasMessageContaining("算法");
    }

    @Test
    @DisplayName("外部形态的非对称凭证不能当作本地会话凭证使用")
    void externalTokenRejectedByLocalVerifier() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String externalToken = Jwts.builder()
                .issuer("https://idp.example.com")
                .subject("alice")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThat(localTokenService.isLocalToken(externalToken)).isFalse();
        assertThatThrownBy(() -> localTokenService.verify(externalToken))
                .isInstanceOf(TokenVerificationException.class)
                .hasMessageContaining("算法");
    }

    @Test
    @DisplayName("生产 profile 未配置 jwt-secret 时启动校验失败（fail-closed）")
    void productionProfileWithoutSecretFailsFast() {
        LocalAuthProperties prod = new LocalAuthProperties();
        prod.setJwtSecret("");
        LocalTokenService service = new LocalTokenService(prod);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "activeProfiles", "prod");

        assertThatThrownBy(service::validateSecretConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wgh.auth.local.jwt-secret");
    }

    @Test
    @DisplayName("开发 profile 未配置 jwt-secret 时仅告警不阻断启动")
    void devProfileWithoutSecretOnlyWarns() {
        LocalAuthProperties dev = new LocalAuthProperties();
        dev.setJwtSecret("");
        LocalTokenService service = new LocalTokenService(dev);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "activeProfiles", "dev");

        service.validateSecretConfiguration();
        assertThat(service.isLocalToken("dummy")).isFalse();
    }

    @Test
    @DisplayName("使用相同密钥但 issuer 不匹配的凭证被拒绝")
    void wrongIssuerRejected() {
        LocalAuthProperties other = new LocalAuthProperties();
        other.setJwtSecret("unit-test-local-secret-0123456789abcdef0123456789");
        other.setIssuer("other-issuer");
        other.setAudience("wgh-local");
        LocalTokenService otherService = new LocalTokenService(other);

        String token = otherService.issue("admin", 1L, Set.of("admin"), 1).token();

        assertThatThrownBy(() -> localTokenService.verify(token))
                .isInstanceOf(TokenVerificationException.class);
    }
}
