package com.wgh.rag.auth.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.wgh.rag.auth.entity.WghRole;
import com.wgh.rag.auth.mapper.RoleMapper;
import com.wgh.rag.auth.mapper.RolePermissionMapper;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.auth.mapper.UserRoleMapper;
import com.wgh.rag.common.security.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 外部 OIDC 资源服务器离线测试。
 * <p>
 * 运行时生成 RSA keypair 与本地 JWKS HTTP 端点（127.0.0.1 随机端口），不依赖外网 IdP。
 * 覆盖：有效签名 / kid 命中 / 未知 kid 触发刷新（含限频）/ 篡改签名 / 算法不符 /
 * 过期 / nbf / iss / aud / 无 token / roles 缺失回退 viewer / IdP 不可达 401 / 旧缓存兜底。
 */
@DisplayName("OIDC 资源服务器验签与过滤器")
class OidcResourceServerTest {

    private static final String ISSUER = "https://test-idp.example.com";
    private static final String AUDIENCE = "wgh-rag";
    private static final String KID_1 = "kid-1";
    private static final String KID_2 = "kid-2";
    private static final long TTL_SECONDS = 300;

    private static final KeyPair KEY_PAIR_1 = generateRsaKeyPair();
    private static final KeyPair KEY_PAIR_2 = generateRsaKeyPair();
    private static final KeyPair WRONG_KEY_PAIR = generateRsaKeyPair();

    static {
        // 单元测试不在 MyBatis 容器内，显式初始化实体表信息，供 LambdaQueryWrapper 生成 SQL 片段
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WghRole.class);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RoleMapper roleMapper;
    private RolePermissionMapper rolePermissionMapper;
    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private LocalTokenService localTokenService;
    private TestJwksServer jwksServer;
    private OidcProperties properties;
    private OidcTokenVerifier verifier;
    private OidcAuthenticationFilter filter;
    private LoginUser capturedLoginUser;

    @BeforeEach
    void setUp() throws Exception {
        roleMapper = mock(RoleMapper.class);
        rolePermissionMapper = mock(RolePermissionMapper.class);
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        LocalAuthProperties localAuthProperties = new LocalAuthProperties();
        localAuthProperties.setJwtSecret("test-local-secret-0123456789abcdef0123456789abcdef");
        localTokenService = new LocalTokenService(localAuthProperties);
        when(roleMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<WghRole> wrapper = invocation.getArgument(0);
            // 触发 SQL 片段生成，使 in 条件的参数进入 paramNameValuePairs
            wrapper.getSqlSegment();
            Set<String> requested = new HashSet<>();
            for (Object value : wrapper.getParamNameValuePairs().values()) {
                if (value instanceof Collection<?> collection) {
                    collection.forEach(item -> requested.add(String.valueOf(item)));
                } else if (value != null) {
                    requested.add(String.valueOf(value));
                }
            }
            return allRoles().stream()
                    .filter(role -> requested.contains(role.getRoleCode()))
                    .toList();
        });
        when(rolePermissionMapper.selectPermCodesByRoleCodes(anyCollection())).thenAnswer(invocation -> {
            Collection<String> roles = invocation.getArgument(0);
            List<String> permissions = new ArrayList<>();
            if (roles.contains("admin")) {
                permissions.addAll(List.of("audit:view", "quota:manage", "*:*"));
            } else if (roles.contains("editor")) {
                permissions.addAll(List.of("kb:write", "kb:read"));
            } else {
                permissions.add("kb:read");
            }
            return permissions;
        });

        jwksServer = new TestJwksServer(objectMapper);
        jwksServer.publish(Map.of(KID_1, publicKey(KEY_PAIR_1)));

        properties = new OidcProperties();
        properties.setIssuer(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setJwksUri(jwksServer.uri());
        properties.setJwksCacheTtlSeconds(TTL_SECONDS);
        properties.setJwksRefreshMinIntervalSeconds(0);
        properties.setClockSkewSeconds(30);

        verifier = new OidcTokenVerifier(properties, new JwksProvider(properties));
        filter = new OidcAuthenticationFilter(verifier, localTokenService, userMapper, userRoleMapper,
                roleMapper, rolePermissionMapper, properties);
    }

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        jwksServer.close();
    }

    @Test
    @DisplayName("有效 RS256 token + roles=admin → 通过并加载数据库角色/权限")
    void validAdminToken() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE,
                "alice", List.of("admin"), null, null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser).isNotNull();
        assertThat(capturedLoginUser.getUsername()).isEqualTo("alice");
        assertThat(capturedLoginUser.getRoles()).containsExactly("admin");
        assertThat(capturedLoginUser.getPermissions()).contains("audit:view", "quota:manage");
    }

    @Test
    @DisplayName("roles claim 缺失 → 回退默认 viewer 角色，不拒绝请求")
    void missingRolesFallsBackToViewer() throws Exception {
        String tokenWithoutRoles = Jwts.builder()
                .header().keyId(KID_1).and()
                .subject("bob")
                .issuer(ISSUER)
                .setAudience(AUDIENCE)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(KEY_PAIR_1.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletResponse response = doFilter("Bearer " + tokenWithoutRoles);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser.getRoles()).containsExactly("viewer");
        assertThat(capturedLoginUser.getPermissions()).containsExactly("kb:read");
    }

    @Test
    @DisplayName("角色映射表命中：claim=wgh-admin → DB role_code=admin")
    void roleMappingApplied() throws Exception {
        properties.setRoleMapping(Map.of("wgh-admin", "admin"));

        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE,
                "carol", List.of("wgh-admin"), null, null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser.getRoles()).containsExactly("admin");
    }

    @Test
    @DisplayName("claim 角色在数据库不存在 → 回退 viewer")
    void unknownRoleFallsBackToViewer() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE,
                "dave", List.of("external-unknown-role"), null, null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(capturedLoginUser.getRoles()).containsExactly("viewer");
    }

    @Test
    @DisplayName("issuer/audience 未配置 → fail-closed 拒绝")
    void missingIssuerOrAudienceFailsClosed() {
        properties.setIssuer(null);

        assertThatThrownBy(() -> verifier.verify(
                token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE, "alice", List.of("admin"), null, null)))
                .isInstanceOf(TokenVerificationException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    @DisplayName("无 token → 401 / code 1001")
    void noToken() throws Exception {
        MockHttpServletResponse response = doFilter(null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":1001");
        assertThat(capturedLoginUser).isNull();
    }

    @Test
    @DisplayName("过期 token → 401 / code 1002")
    void expiredToken() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE,
                "alice", List.of("admin"), new Date(System.currentTimeMillis() - 3_600_000), null));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":1002");
    }

    @Test
    @DisplayName("时钟偏差容忍：过期 10s（skew=30s）内仍通过")
    void clockSkewAllowsSlightlyExpiredToken() {
        String token = token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE,
                "alice", List.of("admin"), new Date(System.currentTimeMillis() - 10_000), null);

        Claims claims = verifier.verify(token);
        assertThat(claims.getSubject()).isEqualTo("alice");
    }

    @Test
    @DisplayName("nbf 未到 → 401")
    void futureNotBeforeRejected() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE,
                "alice", List.of("admin"), null, new Date(System.currentTimeMillis() + 120_000)));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("iss 不匹配 → 401")
    void issuerMismatch() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, KEY_PAIR_1,
                "https://evil-idp.example.com", AUDIENCE, "alice", List.of("admin"), null, null));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("aud 不匹配 → 401")
    void audienceMismatch() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, KEY_PAIR_1, ISSUER,
                "another-client", "alice", List.of("admin"), null, null));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("签名被篡改（私钥不符）→ 401")
    void tamperedSignature() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer " + token(KID_1, WRONG_KEY_PAIR, ISSUER, AUDIENCE,
                "alice", List.of("admin"), null, null));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("HS256 对称算法 → 直接拒绝")
    void symmetricAlgorithmRejected() throws Exception {
        SecretKey secretKey = Keys.hmacShaKeyFor(
                "test-only-hs256-secret-key-32bytes!!".getBytes(StandardCharsets.UTF_8));
        String hsToken = Jwts.builder()
                .header().keyId(KID_1).and()
                .subject("alice")
                .issuer(ISSUER)
                .setAudience(AUDIENCE)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("roles", List.of("admin"))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        MockHttpServletResponse response = doFilter("Bearer " + hsToken);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("alg=none → 直接拒绝")
    void noneAlgorithmRejected() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"alice\",\"iss\":\"" + ISSUER + "\"}");
        String noneToken = header + "." + payload + ".";

        assertThatThrownBy(() -> verifier.verify(noneToken))
                .isInstanceOf(TokenVerificationException.class)
                .hasMessageContaining("签名算法");
    }

    @Test
    @DisplayName("未知 kid → 触发一次 JWKS 刷新后通过")
    void unknownKidTriggersRefresh() throws Exception {
        // 首次请求加载 kid-1
        verifier.verify(token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE, "alice", List.of("admin"), null, null));
        assertThat(jwksServer.requestCount()).isEqualTo(1);

        // IdP 新增 kid-2；使用 kid-2 的 token 触发刷新
        jwksServer.publish(Map.of(KID_1, publicKey(KEY_PAIR_1), KID_2, publicKey(KEY_PAIR_2)));
        Claims claims = verifier.verify(token(KID_2, KEY_PAIR_2, ISSUER, AUDIENCE,
                "alice", List.of("admin"), null, null));

        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(jwksServer.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("未知 kid 刷新受限频约束，不会每次请求都打 IdP")
    void unknownKidRefreshIsThrottled() throws Exception {
        properties.setJwksRefreshMinIntervalSeconds(30);

        verifier.verify(token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE, "alice", List.of("admin"), null, null));
        assertThat(jwksServer.requestCount()).isEqualTo(1);

        jwksServer.publish(Map.of(KID_1, publicKey(KEY_PAIR_1), KID_2, publicKey(KEY_PAIR_2)));
        assertThatThrownBy(() -> verifier.verify(
                token(KID_2, KEY_PAIR_2, ISSUER, AUDIENCE, "alice", List.of("admin"), null, null)))
                .isInstanceOf(TokenVerificationException.class);

        assertThat(jwksServer.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("IdP 不可达 → 过滤器返回 401（不是 500）")
    void idpUnreachableReturns401() throws Exception {
        jwksServer.close();

        OidcTokenVerifier unreachableVerifier = new OidcTokenVerifier(properties, new JwksProvider(properties));
        OidcAuthenticationFilter unreachableFilter =
                new OidcAuthenticationFilter(unreachableVerifier, localTokenService, userMapper, userRoleMapper,
                        roleMapper, rolePermissionMapper, properties);

        MockHttpServletResponse response = doFilter(unreachableFilter, "Bearer " + token(KID_1, KEY_PAIR_1,
                ISSUER, AUDIENCE, "alice", List.of("admin"), null, null));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(capturedLoginUser).isNull();
    }

    @Test
    @DisplayName("IdP 故障时旧缓存兜底：TTL 内继续验签成功")
    void staleCacheServesDuringOutage() throws Exception {
        verifier.verify(token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE, "alice", List.of("admin"), null, null));
        jwksServer.close();

        Claims claims = verifier.verify(token(KID_1, KEY_PAIR_1, ISSUER, AUDIENCE,
                "bob", List.of("viewer"), null, null));

        assertThat(claims.getSubject()).isEqualTo("bob");
    }

    // ==================== 测试辅助 ====================

    private MockHttpServletResponse doFilter(String authorization) throws Exception {
        return doFilter(filter, authorization);
    }

    private MockHttpServletResponse doFilter(OidcAuthenticationFilter targetFilter,
                                             String authorization) throws Exception {
        LoginUser.clear();
        capturedLoginUser = null;

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kb");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<LoginUser> loginUserRef = new AtomicReference<>();
        FilterChain chain = (req, res) -> loginUserRef.set(LoginUser.get());
        targetFilter.doFilter(request, response, chain);
        capturedLoginUser = loginUserRef.get();
        return response;
    }

    private String token(String kid, KeyPair keyPair, String issuer, String audience,
                         String subject, List<String> roles, Date expiration, Date notBefore) {
        var builder = Jwts.builder()
                .header().keyId(kid).and()
                .subject(subject)
                .issuer(issuer)
                .setAudience(audience)
                .issuedAt(new Date())
                .expiration(expiration == null
                        ? new Date(System.currentTimeMillis() + 3_600_000) : expiration)
                .claim("roles", roles);
        if (notBefore != null) {
            builder.notBefore(notBefore);
        }
        return builder.signWith(keyPair.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static List<WghRole> allRoles() {
        return List.of(role("admin"), role("editor"), role("viewer"));
    }

    private static WghRole role(String code) {
        WghRole role = new WghRole();
        role.setRoleCode(code);
        return role;
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成测试 RSA keypair 失败", e);
        }
    }

    private static RSAPublicKey publicKey(KeyPair keyPair) {
        return (RSAPublicKey) keyPair.getPublic();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unsignedBase64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 本地 JWKS HTTP 端点（离线运行） */
    private static final class TestJwksServer implements AutoCloseable {

        private final ObjectMapper objectMapper;
        private final HttpServer server;
        private final AtomicReference<String> responseBody = new AtomicReference<>("{\"keys\":[]}");
        private final AtomicInteger requests = new AtomicInteger();

        TestJwksServer(ObjectMapper objectMapper) throws Exception {
            this.objectMapper = objectMapper;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/jwks", exchange -> {
                requests.incrementAndGet();
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(body);
                }
            });
            this.server.start();
        }

        void publish(Map<String, RSAPublicKey> keys) throws Exception {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode keyArray = root.putArray("keys");
            for (Map.Entry<String, RSAPublicKey> entry : keys.entrySet()) {
                ObjectNode keyNode = keyArray.addObject();
                keyNode.put("kty", "RSA");
                keyNode.put("kid", entry.getKey());
                keyNode.put("use", "sig");
                keyNode.put("alg", "RS256");
                keyNode.put("n", unsignedBase64Url(entry.getValue().getModulus()));
                keyNode.put("e", unsignedBase64Url(entry.getValue().getPublicExponent()));
            }
            responseBody.set(objectMapper.writeValueAsString(root));
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        }

        int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
