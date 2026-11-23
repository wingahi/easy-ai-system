package com.wgh.rag.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部 IdP JWKS 公钥提供者。
 * <p>
 * 只使用非对称公钥验签（RSA / EC），不含任何本地密钥或对称密钥。
 * 缓存策略：
 * <ul>
 *   <li>本地缓存 JWKS（默认 TTL 300s），作为第一层；</li>
 *   <li>多实例共享缓存（默认启用，复用 {@code wgh.rag.redis}）：本地未命中/过期时先读共享缓存，
 *       避免每个实例重复回源 IdP；回源成功后把原始 JWKS 写回共享缓存；</li>
 *   <li>未知 kid 触发刷新，刷新有最小间隔限制（默认 30s），避免被伪造 kid 打爆；
 *       刷新前先重读共享缓存，其它实例完成轮换后可直接复用；</li>
 *   <li>网络或共享缓存失败时沿用旧缓存；启动阶段不拉取，IdP 不可达不会阻断应用启动。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwksProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final OidcProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private final Object refreshLock = new Object();

    /** 跨实例共享缓存；未注入时为空实现，行为退化为纯本地缓存。 */
    private JwksSharedCache sharedCache = JwksSharedCache.NOOP;

    /** kid → 公钥（原子替换，读路径无锁） */
    private volatile Map<String, PublicKey> keyCache = Map.of();
    private volatile long lastSuccessNanos = Long.MIN_VALUE;
    private volatile long lastAttemptNanos = 0L;
    private volatile String resolvedJwksUri;
    /** 当前 keyCache 对应的原始 JWKS 文档，用于判断共享缓存是否已变化。 */
    private volatile String loadedBody;

    /** 由 Spring 在容器内注入；单元测试直接构造时使用 {@link JwksSharedCache#NOOP}。 */
    @Autowired(required = false)
    public void setSharedCache(JwksSharedCache sharedCache) {
        this.sharedCache = sharedCache == null ? JwksSharedCache.NOOP : sharedCache;
    }

    /**
     * 按 kid / 算法获取验签公钥。
     *
     * @return 匹配的公钥；取不到时返回 null（由调用方按 401 处理）
     */
    public PublicKey getVerificationKey(String kid, String alg) {
        long now = System.nanoTime();
        if (keyCache.isEmpty() || isTtlExpired(now)) {
            // 先看其它实例是否已把最新 JWKS 写入共享缓存，避免每个实例各自回源
            if (!loadFromSharedCache()) {
                refresh(now);
            }
        }

        PublicKey key = findByKidOrAlgorithm(kid, alg);
        if (key != null) {
            return key;
        }
        if (StringUtils.hasText(kid)) {
            // 未知 kid：先重读共享缓存（可能刚完成轮换），再限频回源后重试
            if (loadFromSharedCache()) {
                key = findByKidOrAlgorithm(kid, alg);
                if (key != null) {
                    return key;
                }
            }
            refresh(now);
            return findByKidOrAlgorithm(kid, alg);
        }
        return null;
    }

    private boolean isTtlExpired(long now) {
        if (lastSuccessNanos == Long.MIN_VALUE) {
            return true;
        }
        long ttlNanos = Math.max(0L, properties.getJwksCacheTtlSeconds()) * NANOS_PER_SECOND;
        return now - lastSuccessNanos > ttlNanos;
    }

    private void refresh(long now) {
        synchronized (refreshLock) {
            long minIntervalNanos = Math.max(0L, properties.getJwksRefreshMinIntervalSeconds()) * NANOS_PER_SECOND;
            if (lastAttemptNanos != 0L && now - lastAttemptNanos < minIntervalNanos) {
                return;
            }
            lastAttemptNanos = now;
            try {
                JwksDocument document = fetchJwks();
                if (!document.keys().isEmpty()) {
                    keyCache = document.keys();
                    loadedBody = document.body();
                    lastSuccessNanos = now;
                    writeSharedCache(document);
                } else {
                    log.warn("JWKS 未包含可用的非对称验签公钥，沿用旧缓存");
                }
            } catch (Exception e) {
                // IdP 不可达 / 响应异常：不抛到请求线程，沿用旧缓存；无旧缓存时调用方按 401 处理
                log.warn("JWKS 刷新失败，沿用旧缓存：{}", e.getMessage());
            }
        }
    }

    /**
     * 从共享缓存加载 JWKS。
     *
     * @return {@code true} 表示加载了与本地不同的有效 JWKS，调用方无需回源
     */
    private boolean loadFromSharedCache() {
        if (!sharedCache.enabled()) {
            return false;
        }
        String jwksUri = resolveJwksUriQuietly();
        if (!StringUtils.hasText(jwksUri)) {
            return false;
        }
        String body;
        try {
            body = sharedCache.get(jwksUri);
        } catch (RuntimeException e) {
            // 共享缓存故障不能影响认证，直接回退回源
            log.debug("读取共享 JWKS 缓存失败，回退回源：{}", e.getMessage());
            return false;
        }
        if (!StringUtils.hasText(body) || body.equals(loadedBody)) {
            return false;
        }
        try {
            Map<String, PublicKey> parsed = parseJwks(body);
            if (parsed.isEmpty()) {
                return false;
            }
            keyCache = parsed;
            loadedBody = body;
            lastSuccessNanos = System.nanoTime();
            log.debug("已从共享缓存加载 JWKS，跳过回源");
            return true;
        } catch (Exception e) {
            log.debug("共享缓存 JWKS 解析失败，忽略：{}", e.getMessage());
            return false;
        }
    }

    private void writeSharedCache(JwksDocument document) {
        long ttlSeconds = properties.getJwksCacheTtlSeconds();
        if (ttlSeconds <= 0) {
            return;
        }
        String jwksUri = StringUtils.hasText(resolvedJwksUri) ? resolvedJwksUri : properties.getJwksUri();
        if (!StringUtils.hasText(jwksUri)) {
            return;
        }
        try {
            sharedCache.put(jwksUri, document.body(), Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.debug("写入共享 JWKS 缓存失败，忽略：{}", e.getMessage());
        }
    }

    private String resolveJwksUriQuietly() {
        try {
            return resolveJwksUri();
        } catch (Exception e) {
            log.debug("解析 JWKS 地址失败，跳过共享缓存：{}", e.getMessage());
            return null;
        }
    }

    private PublicKey findByKidOrAlgorithm(String kid, String alg) {
        Map<String, PublicKey> snapshot = keyCache;
        if (StringUtils.hasText(kid)) {
            return snapshot.get(kid);
        }
        return snapshot.values().stream()
                .filter(key -> keyMatchesAlgorithm(key, alg))
                .findFirst()
                .orElse(null);
    }

    private JwksDocument fetchJwks() throws Exception {
        String jwksUri = resolveJwksUri();
        if (!StringUtils.hasText(jwksUri)) {
            throw new IllegalStateException("未配置 wgh.auth.oidc.jwks-uri，且 issuer 为空");
        }

        HttpResponse<String> response = send(jwksUri);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("JWKS 端点返回 HTTP " + response.statusCode());
        }
        return new JwksDocument(response.body(), parseJwks(response.body()));
    }

    private Map<String, PublicKey> parseJwks(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        Map<String, PublicKey> result = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode keyNode : root.path("keys")) {
            // use=sig 或未声明 use 才用于验签
            String use = keyNode.path("use").asText("sig");
            if (!"sig".equals(use)) {
                continue;
            }
            PublicKey publicKey = parsePublicKey(keyNode);
            if (publicKey == null) {
                continue;
            }
            String kid = keyNode.path("kid").asText(null);
            result.put(StringUtils.hasText(kid) ? kid : "#" + index, publicKey);
            index++;
        }
        return result;
    }

    private String resolveJwksUri() throws Exception {
        if (StringUtils.hasText(properties.getJwksUri())) {
            return properties.getJwksUri();
        }
        if (!StringUtils.hasText(properties.getIssuer())) {
            return null;
        }
        if (StringUtils.hasText(resolvedJwksUri)) {
            return resolvedJwksUri;
        }

        String issuer = properties.getIssuer().replaceAll("/+$", "");
        HttpResponse<String> response = send(issuer + "/.well-known/openid-configuration");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OIDC discovery 返回 HTTP " + response.statusCode());
        }
        String jwksUri = objectMapper.readTree(response.body()).path("jwks_uri").asText(null);
        if (!StringUtils.hasText(jwksUri)) {
            throw new IllegalStateException("OIDC discovery 未返回 jwks_uri");
        }
        resolvedJwksUri = jwksUri;
        return jwksUri;
    }

    private HttpResponse<String> send(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private PublicKey parsePublicKey(JsonNode keyNode) {
        String kty = keyNode.path("kty").asText(null);
        try {
            if ("RSA".equals(kty)) {
                String n = keyNode.path("n").asText(null);
                String e = keyNode.path("e").asText(null);
                if (!StringUtils.hasText(n) || !StringUtils.hasText(e)) {
                    return null;
                }
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                return keyFactory.generatePublic(new RSAPublicKeySpec(decode(n), decode(e)));
            }
            if ("EC".equals(kty)) {
                String crv = keyNode.path("crv").asText(null);
                String x = keyNode.path("x").asText(null);
                String y = keyNode.path("y").asText(null);
                if (!StringUtils.hasText(crv) || !StringUtils.hasText(x) || !StringUtils.hasText(y)) {
                    return null;
                }
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec(ecCurveName(crv)));
                ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
                KeyFactory keyFactory = KeyFactory.getInstance("EC");
                return keyFactory.generatePublic(new ECPublicKeySpec(
                        new ECPoint(decode(x), decode(y)), spec));
            }
        } catch (Exception e) {
            log.warn("解析 JWKS 公钥失败（kty={}）：{}", kty, e.getMessage());
        }
        return null;
    }

    private BigInteger decode(String value) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(value));
    }

    private String ecCurveName(String crv) {
        return switch (crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> throw new IllegalArgumentException("不支持的 EC 曲线: " + crv);
        };
    }

    static boolean keyMatchesAlgorithm(PublicKey key, String alg) {
        if (alg == null) {
            return true;
        }
        if (alg.startsWith("RS") || alg.startsWith("PS")) {
            return key instanceof RSAPublicKey;
        }
        if (alg.startsWith("ES")) {
            return key instanceof ECPublicKey;
        }
        return false;
    }

    /** 一次回源得到的原始 JWKS 文档与解析后的公钥。 */
    private record JwksDocument(String body, Map<String, PublicKey> keys) {
    }
}
