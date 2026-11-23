package com.wgh.rag.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多实例共享 JWKS 缓存行为：冷启动复用、轮换回写、故障降级与关闭开关。
 */
@DisplayName("JWKS 多实例共享缓存")
class JwksProviderSharedCacheTest {

    private static final String KID_1 = "kid-1";
    private static final String KID_2 = "kid-2";
    private static final KeyPair KEY_PAIR_1 = generateRsaKeyPair();
    private static final KeyPair KEY_PAIR_2 = generateRsaKeyPair();

    private OidcProperties properties;
    private TestJwksServer server;
    private FakeSharedCache shared;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestJwksServer(new ObjectMapper());
        server.publish(Map.of(KID_1, publicKey(KEY_PAIR_1)));

        properties = new OidcProperties();
        properties.setIssuer("https://test-idp.example.com");
        properties.setAudience("wgh-rag");
        properties.setJwksUri(server.uri());
        properties.setJwksCacheTtlSeconds(300);
        properties.setJwksRefreshMinIntervalSeconds(0);
        properties.setJwksSharedCacheEnabled(true);
        shared = new FakeSharedCache();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("冷启动实例复用共享缓存，不重复回源 IdP")
    void coldInstanceReusesSharedCache() {
        JwksProvider first = provider();
        assertThat(first.getVerificationKey(KID_1, "RS256")).isNotNull();
        assertThat(server.requestCount()).isEqualTo(1);

        JwksProvider second = provider();
        assertThat(second.getVerificationKey(KID_1, "RS256")).isNotNull();
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("密钥轮换：未知 kid 回源后写回共享缓存，后续实例直接复用")
    void rotatedKeyIsShared() throws Exception {
        provider().getVerificationKey(KID_1, "RS256");
        assertThat(server.requestCount()).isEqualTo(1);

        server.publish(Map.of(KID_1, publicKey(KEY_PAIR_1), KID_2, publicKey(KEY_PAIR_2)));
        assertThat(provider().getVerificationKey(KID_2, "RS256")).isNotNull();
        assertThat(server.requestCount()).isEqualTo(2);

        assertThat(provider().getVerificationKey(KID_2, "RS256")).isNotNull();
        assertThat(server.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("共享缓存读取异常时回退回源，认证不失败")
    void sharedCacheFailureFallsBackToIdp() {
        shared.failReads = true;

        assertThat(provider().getVerificationKey(KID_1, "RS256")).isNotNull();
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("关闭共享缓存时保持原行为：每个实例独立回源")
    void disabledSharedCacheKeepsOriginalBehavior() {
        shared.enabled = false;

        assertThat(provider().getVerificationKey(KID_1, "RS256")).isNotNull();
        assertThat(provider().getVerificationKey(KID_1, "RS256")).isNotNull();
        assertThat(server.requestCount()).isEqualTo(2);
    }

    private JwksProvider provider() {
        JwksProvider provider = new JwksProvider(properties);
        provider.setSharedCache(shared);
        return provider;
    }

    /** 进程内共享缓存替身：可模拟关闭与读取故障。 */
    private static final class FakeSharedCache implements JwksSharedCache {

        private boolean enabled = true;
        private boolean failReads;
        private volatile String body;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String get(String jwksUri) {
            if (failReads) {
                throw new IllegalStateException("redis down");
            }
            return enabled ? body : null;
        }

        @Override
        public void put(String jwksUri, String body, Duration ttl) {
            if (enabled) {
                this.body = body;
            }
        }
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

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 本地 JWKS HTTP 端点（127.0.0.1 随机端口），离线运行。 */
    private static final class TestJwksServer implements AutoCloseable {

        private final ObjectMapper objectMapper;
        private final HttpServer server;
        private final AtomicReference<String> responseBody = new AtomicReference<>("{\"keys\":[]}");
        private final AtomicInteger requests = new AtomicInteger();

        private TestJwksServer(ObjectMapper objectMapper) throws Exception {
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

        private void publish(Map<String, RSAPublicKey> keys) throws Exception {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode keyArray = root.putArray("keys");
            for (Map.Entry<String, RSAPublicKey> entry : keys.entrySet()) {
                ObjectNode keyNode = keyArray.addObject();
                keyNode.put("kty", "RSA");
                keyNode.put("kid", entry.getKey());
                keyNode.put("use", "sig");
                keyNode.put("alg", "RS256");
                keyNode.put("n", base64UrlUnsigned(entry.getValue().getModulus()));
                keyNode.put("e", base64UrlUnsigned(entry.getValue().getPublicExponent()));
            }
            responseBody.set(objectMapper.writeValueAsString(root));
        }

        private String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        }

        private int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
