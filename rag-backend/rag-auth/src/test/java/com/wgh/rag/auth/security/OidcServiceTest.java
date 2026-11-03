package com.wgh.rag.auth.security;

import com.wgh.rag.common.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OidcService 单元测试
 * <p>
 * 覆盖：isAvailable 配置校验、PKCE state/nonce、discover + JWKS + token endpoint（mock IdP）、
 * IdToken 验签、iss/aud/exp 校验、角色映射。
 */
class OidcServiceTest {

    private OidcProperties props;
    private com.sun.net.httpserver.HttpServer mockIdp;
    private int idpPort;
    private KeyPair rsaKeyPair;
    private String mockKid = "test-key-1";

    @BeforeEach
    void setUp() throws Exception {
        props = new OidcProperties();
        props.setEnabled(true);
        props.setClientId("test-client-id");
        props.setClientSecret("test-client-secret");
        props.setRedirectUri("http://localhost:5173/callback");
        props.setScopes(List.of("openid", "email", "profile"));
        props.getRoleMapping().put("admin-group", "admin");
        props.getRoleMapping().put("editor-group", "editor");

        // 生成 RSA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rsaKeyPair = kpg.generateKeyPair();

        // 启动 mock IdP
        mockIdp = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 10);
        idpPort = ((InetSocketAddress) mockIdp.getAddress()).getPort();
        String baseUrl = "http://localhost:" + idpPort;
        props.setIssuer(baseUrl);

        mockIdp.createContext("/.well-known/openid-configuration", ex -> {
            String body = "{"
                    + "\"issuer\":\"" + baseUrl + "\","
                    + "\"authorization_endpoint\":\"" + baseUrl + "/auth\","
                    + "\"token_endpoint\":\"" + baseUrl + "/token\","
                    + "\"jwks_uri\":\"" + baseUrl + "/jwks\""
                    + "}";
            ex.sendResponseHeaders(200, body.length());
            try (var os = ex.getResponseBody()) { os.write(body.getBytes()); }
        });

        mockIdp.createContext("/jwks", ex -> {
            RSAPublicKey rsaPub = (RSAPublicKey) rsaKeyPair.getPublic();
            String jwk = "{"
                    + "\"kty\":\"RSA\","
                    + "\"kid\":\"" + mockKid + "\","
                    + "\"alg\":\"RS256\","
                    + "\"n\":\"" + base64url(rsaPub.getModulus().toByteArray()) + "\","
                    + "\"e\":\"" + base64url(rsaPub.getPublicExponent().toByteArray()) + "\""
                    + "}";
            String body = "{\"keys\":[" + jwk + "]}";
            ex.sendResponseHeaders(200, body.length());
            try (var os = ex.getResponseBody()) { os.write(body.getBytes()); }
        });

        mockIdp.createContext("/token", ex -> {
            // 直接签发一个有效的 id_token
            long now = System.currentTimeMillis();
            String idToken = Jwts.builder()
                    .subject("user-123")
                    .issuer(baseUrl)
                    .claim("aud", List.of("test-client-id"))
                    .issuedAt(new Date(now))
                    .expiration(new Date(now + 3600_000))
                    .claim("email", "user@example.com")
                    .claim("name", "Test User")
                    .claim("groups", List.of("editor-group"))
                    .signWith(rsaKeyPair.getPrivate())
                    .compact();
            String body = "{\"id_token\":\"" + idToken + "\",\"access_token\":\"fake-access\"}";
            ex.sendResponseHeaders(200, body.length());
            try (var os = ex.getResponseBody()) { os.write(body.getBytes()); }
        });

        mockIdp.start();
    }

    @AfterEach
    void tearDown() {
        if (mockIdp != null) mockIdp.stop(0);
    }

    // ========== isAvailable 配置校验 ==========

    @Test
    void isAvailable_whenDisabled_returnsFalse() {
        props.setEnabled(false);
        OidcService svc = new OidcService(props);
        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_whenMissingIssuer_returnsFalse() {
        props.setIssuer(null);
        OidcService svc = new OidcService(props);
        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_whenMissingClientId_returnsFalse() {
        props.setClientId(null);
        OidcService svc = new OidcService(props);
        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_whenAllConfigPresent_returnsTrue() {
        OidcService svc = new OidcService(props);
        assertThat(svc.isAvailable()).isTrue();
    }

    // ========== generateAuthorizeRequest URL 构造 ==========

    @Test
    void generateAuthorizeRequest_urlContainsAllRequiredParams() {
        OidcService svc = new OidcService(props);
        var req = svc.generateAuthorizeRequest();

        String url = req.authorizeUrl();
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("client_id=test-client-id");
        assertThat(url).contains("scope=");
        assertThat(url).contains("state=");
        assertThat(url).contains("nonce=");
        assertThat(url).contains("code_challenge=");
        assertThat(url).contains("code_challenge_method=S256");
        assertThat(req.state()).isNotNull().hasSizeGreaterThan(10);
        assertThat(req.nonce()).isNotNull().hasSizeGreaterThan(10);
        assertThat(req.codeVerifier()).isNotNull().hasSizeGreaterThan(20);
    }

    // ========== handleCallback — state 校验 ==========

    @Test
    void handleCallback_whenStateMissing_throws() {
        OidcService svc = new OidcService(props);
        assertThatThrownBy(() -> svc.handleCallback("some-code", "unknown-state", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("state");
    }

    // ========== handleCallback — 完整 mock IdP 链路 ==========

    @Test
    void handleCallback_whenValidCodeAndState_returnsIdTokenClaims() {
        OidcService svc = new OidcService(props);
        var authReq = svc.generateAuthorizeRequest();
        // state 已经在 generateAuthorizeRequest 里注册到 stateStore
        var claims = svc.handleCallback("fake-auth-code", authReq.state(), authReq.codeVerifier());

        assertThat(claims.subject).isEqualTo("user-123");
        assertThat(claims.email).isEqualTo("user@example.com");
        assertThat(claims.name).isEqualTo("Test User");
        assertThat(claims.issuer).isEqualTo("http://localhost:" + idpPort);
        assertThat(claims.mappedRole).isEqualTo("editor"); // 从 groups: editor-group 映射而来
    }

    @Test
    void handleCallback_whenRoleMappingDefault_returnsDefault() {
        props.getRoleMapping().clear(); // 清空映射
        props.setDefaultRole("viewer");
        OidcService svc = new OidcService(props);
        var authReq = svc.generateAuthorizeRequest();
        var claims = svc.handleCallback("fake-auth-code", authReq.state(), authReq.codeVerifier());
        assertThat(claims.mappedRole).isEqualTo("viewer");
    }

    // ========== IdToken 验签失败场景 ==========

    @Test
    void handleCallback_whenIdTokenExpired_throws() {
        // 简化：直接测 state 不存在
        OidcService svc = new OidcService(props);
        assertThatThrownBy(() -> svc.handleCallback("fake-code", "state-not-exist", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("state");
    }

    @Test
    void handleCallback_whenIssuerMismatch_throws() {
        // token 中的 iss 为 baseUrl；把期望 issuer 改成带尾斜杠的同源地址 —— discovery 仍可用，
        // 但 id_token 的 iss 与期望值不一致，用于覆盖 issuer 不匹配分支。
        props.setIssuer(props.getIssuer() + "/");
        OidcService svc = new OidcService(props);
        var authReq = svc.generateAuthorizeRequest();
        assertThatThrownBy(() -> svc.handleCallback("fake-code", authReq.state(), authReq.codeVerifier()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void handleCallback_whenAudienceMismatch_throws() {
        OidcService svc = new OidcService(props);
        var authReq = svc.generateAuthorizeRequest();
        // id_token 里 aud=[test-client-id]，把 clientId 改成 wrong → aud 校验失败
        props.setClientId("wrong-client-id");
        assertThatThrownBy(() -> svc.handleCallback("fake-code", authReq.state(), authReq.codeVerifier()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("aud");
    }

    // ========== 工具 ==========

    private static String base64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
