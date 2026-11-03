package com.wgh.rag.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   <li>本地缓存 JWKS（默认 TTL 300s）；</li>
 *   <li>未知 kid 触发一次刷新，刷新有最小间隔限制（默认 30s），避免被伪造 kid 打爆；</li>
 *   <li>网络失败时沿用旧缓存；启动阶段不拉取，IdP 不可达不会阻断应用启动。</li>
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

    /** kid → 公钥（原子替换，读路径无锁） */
    private volatile Map<String, PublicKey> keyCache = Map.of();
    private volatile long lastSuccessNanos = Long.MIN_VALUE;
    private volatile long lastAttemptNanos = 0L;
    private volatile String resolvedJwksUri;

    /**
     * 按 kid / 算法获取验签公钥。
     *
     * @return 匹配的公钥；取不到时返回 null（由调用方按 401 处理）
     */
    public PublicKey getVerificationKey(String kid, String alg) {
        long now = System.nanoTime();
        if (keyCache.isEmpty() || isTtlExpired(now)) {
            refresh(now);
        }

        PublicKey key = findByKidOrAlgorithm(kid, alg);
        if (key != null) {
            return key;
        }
        if (StringUtils.hasText(kid)) {
            // 未知 kid：限频刷新一次后重试
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
                Map<String, PublicKey> fetched = fetchJwks();
                if (!fetched.isEmpty()) {
                    keyCache = fetched;
                    lastSuccessNanos = now;
                } else {
                    log.warn("JWKS 未包含可用的非对称验签公钥，沿用旧缓存");
                }
            } catch (Exception e) {
                // IdP 不可达 / 响应异常：不抛到请求线程，沿用旧缓存；无旧缓存时调用方按 401 处理
                log.warn("JWKS 刷新失败，沿用旧缓存：{}", e.getMessage());
            }
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

    private Map<String, PublicKey> fetchJwks() throws Exception {
        String jwksUri = resolveJwksUri();
        if (!StringUtils.hasText(jwksUri)) {
            throw new IllegalStateException("未配置 wgh.auth.oidc.jwks-uri，且 issuer 为空");
        }

        HttpResponse<String> response = send(jwksUri);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("JWKS 端点返回 HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
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
}
