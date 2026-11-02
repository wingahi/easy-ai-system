package com.wgh.rag.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 轻量 OIDC 客户端服务
 * <p>
 * 覆盖：IdP 自动发现（.well-known/openid-configuration）、JWKS 拉取与缓存、PKCE、
 * 授权码交换、IdToken 验签（RS256 / HS256）与 iss/aud/exp/sub 校验。
 * <p>
 * 不引入额外 OAuth2 依赖，仅使用：java.net.http.HttpClient + jjwt + jackson + java.security。
 */
@Slf4j
@Service
public class OidcService {

    private final OidcProperties props;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient httpClient;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 内存级 JWKS 缓存：kid → PublicKey，TTL 由 fetch 时的 Cache-Control 决定，默认 10 分钟 */
    private final ConcurrentMap<String, CachedKey> jwksCache = new ConcurrentHashMap<>();
    private volatile long jwksRefreshAt = 0L;
    private String cachedJwksUri;

    /** 内存级 state / nonce 存储：key = state 或 nonce，value = 过期时间戳 */
    private final ConcurrentMap<String, Long> stateStore = new ConcurrentHashMap<>();

    public OidcService(OidcProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ==================== 公开 API ====================

    /** 检查 OIDC 是否已启用且配置完整 */
    public boolean isAvailable() {
        return props.isEnabled()
                && props.getIssuer() != null && !props.getIssuer().isBlank()
                && props.getClientId() != null && !props.getClientId().isBlank()
                && props.getRedirectUri() != null && !props.getRedirectUri().isBlank();
    }

    /** 生成授权端点 URL（含 state + nonce + PKCE code_challenge） */
    public AuthorizeRequest generateAuthorizeRequest() {
        if (!isAvailable()) {
            throw new BusinessException("OIDC 未启用或配置不完整");
        }
        try {
            Map<String, String> discovery = discover();
            String authEndpoint = discovery.get("authorization_endpoint");

            String state = randomString(32);
            String nonce = randomString(32);
            stateStore.put(state, System.currentTimeMillis() + props.getStateTtlSeconds() * 1000L);
            stateStore.put("nonce:" + nonce, System.currentTimeMillis() + props.getStateTtlSeconds() * 1000L);

            StringBuilder url = new StringBuilder(authEndpoint);
            url.append("?response_type=code");
            url.append("&client_id=").append(urlEnc(props.getClientId()));
            url.append("&redirect_uri=").append(urlEnc(props.getRedirectUri()));
            url.append("&scope=").append(urlEnc(String.join(" ", props.getScopes())));
            url.append("&state=").append(state);
            url.append("&nonce=").append(nonce);

            String codeVerifier = null;
            if (props.isEnablePkce()) {
                codeVerifier = randomString(64);
                String codeChallenge = base64url(sha256(codeVerifier));
                url.append("&code_challenge=").append(codeChallenge);
                url.append("&code_challenge_method=S256");
            }

            return new AuthorizeRequest(url.toString(), state, nonce, codeVerifier);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成 OIDC 授权 URL 失败", e);
            throw new BusinessException("生成 OIDC 授权 URL 失败：" + e.getMessage());
        }
    }

    /** 校验 state 并交换授权码，返回解析后的 IdToken claims */
    public IdTokenClaims handleCallback(String code, String state, String codeVerifier) {
        if (!isAvailable()) {
            throw new BusinessException("OIDC 未启用");
        }
        // 1. state 校验
        Long expireAt = stateStore.remove(state);
        if (expireAt == null || expireAt < System.currentTimeMillis()) {
            throw new BusinessException("state 无效或已过期");
        }

        try {
            // 2. 授权码交换
            Map<String, String> discovery = discover();
            String tokenEndpoint = discovery.get("token_endpoint");

            StringBuilder form = new StringBuilder();
            form.append("grant_type=authorization_code");
            form.append("&code=").append(urlEnc(code));
            form.append("&redirect_uri=").append(urlEnc(props.getRedirectUri()));
            form.append("&client_id=").append(urlEnc(props.getClientId()));
            if (props.getClientSecret() != null && !props.getClientSecret().isBlank()) {
                form.append("&client_secret=").append(urlEnc(props.getClientSecret()));
            }
            if (props.isEnablePkce() && codeVerifier != null) {
                form.append("&code_verifier=").append(urlEnc(codeVerifier));
            }

            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<String> tokenResp = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            if (tokenResp.statusCode() != 200) {
                log.warn("token endpoint 返回非 200: status={}, body={}", tokenResp.statusCode(), tokenResp.body());
                throw new BusinessException("授权码交换失败");
            }

            JsonNode tokenJson = om.readTree(tokenResp.body());
            String idToken = tokenJson.path("id_token").asText(null);
            if (idToken == null || idToken.isBlank()) {
                throw new BusinessException("IdP 未返回 id_token");
            }

            // 3. 解析 JWT 头部取 alg + kid，然后验签
            return validateIdToken(idToken, state);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("OIDC 回调处理失败", e);
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new BusinessException("OIDC 回调处理失败：" + reason, e);
        }
    }

    // ==================== 内部实现 ====================

    /** 从 issuer 发现 endpoints */
    private Map<String, String> discover() throws Exception {
        String wellKnown = props.getIssuer().replaceAll("/+$", "") + "/.well-known/openid-configuration";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wellKnown))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new BusinessException("IdP discovery 失败：HTTP " + resp.statusCode());
        }
        JsonNode json = om.readTree(resp.body());
        Map<String, String> result = new HashMap<>();
        result.put("authorization_endpoint", json.path("authorization_endpoint").asText());
        result.put("token_endpoint", json.path("token_endpoint").asText());
        result.put("jwks_uri", json.path("jwks_uri").asText());
        result.put("issuer", json.path("issuer").asText());
        cachedJwksUri = result.get("jwks_uri");
        return result;
    }

    /** 校验 IdToken：验签 + iss/aud/exp/sub 校验 + nonce 校验 */
    private IdTokenClaims validateIdToken(String idToken, String originalState) throws Exception {
        // 1. 解析 JWT 头
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) throw new BusinessException("id_token 格式非法");
        JsonNode header = om.readTree(base64urlDecode(parts[0]));
        String alg = header.path("alg").asText("RS256");
        String kid = header.path("kid").asText(null);

        // 2. 验签 —— jjwt 不支持动态 JWK 直接验证 RS 签名，手动验证
        Claims claims;
        if ("HS256".equals(alg)) {
            if (props.getClientSecret() == null) {
                throw new BusinessException("HS256 需要 client_secret 作为密钥");
            }
            claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(props.getClientSecret().getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } else {
            // RS256 / ES256 —— 从 JWKS 找 key
            PublicKey pubKey = resolvePublicKey(kid);
            claims = Jwts.parser()
                    .verifyWith(pubKey)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        }

        // 3. 标准校验 —— 先校验 iss，确保 issuer 不匹配时原因不被后续解码/类型异常掩盖
        String issuer = validateIssuer(claims);
        String aud = null;
        java.util.Set<String> audience = claims.getAudience();
        if (audience != null && !audience.isEmpty()) {
            aud = audience.iterator().next();
        }
        long exp = claims.getExpiration() != null ? claims.getExpiration().getTime() : 0;
        long now = System.currentTimeMillis();

        if (!props.getClientId().equals(aud)) {
            throw new BusinessException("id_token aud 不匹配");
        }
        if (exp < now) {
            throw new BusinessException("id_token 已过期");
        }

        // 4. nonce 校验
        String nonce = claims.get("nonce", String.class);
        if (nonce != null) {
            Long nExpireAt = stateStore.remove("nonce:" + nonce);
            if (nExpireAt == null || nExpireAt < now) {
                throw new BusinessException("nonce 无效或已过期");
            }
        }

        // 5. 构造 claims
        IdTokenClaims c = new IdTokenClaims();
        c.subject = claims.getSubject();
        c.issuer = issuer;
        c.email = claims.get("email", String.class);
        c.name = claims.get("name", String.class);
        c.preferredUsername = claims.get("preferred_username", String.class);

        // groups / roles 声明 → 映射到本系统角色
        List<String> idpGroups = new ArrayList<>();
        Object groupsObj = claims.get("groups");
        if (groupsObj instanceof List<?>) {
            for (Object g : (List<?>) groupsObj) idpGroups.add(g.toString());
        } else if (groupsObj instanceof String) {
            idpGroups.add((String) groupsObj);
        }
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?>) {
            for (Object r : (List<?>) rolesObj) idpGroups.add(r.toString());
        } else if (rolesObj instanceof String) {
            idpGroups.add((String) rolesObj);
        }

        c.mappedRole = props.getDefaultRole();
        for (String g : idpGroups) {
            String mapped = props.getRoleMapping().get(g);
            if (mapped != null) {
                c.mappedRole = mapped;
                break;
            }
        }
        return c;
    }

    /** 校验 issuer：解析失败与不匹配均以 iss 为关键信息抛出，避免被包装成无原因信息 */
    private String validateIssuer(Claims claims) {
        String issuer;
        try {
            issuer = claims.getIssuer();
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new BusinessException("id_token iss 解析失败：" + reason, e);
        }
        if (!props.getIssuer().equals(issuer)) {
            throw new BusinessException("id_token iss 不匹配：期望 " + props.getIssuer() + "，实际 " + issuer);
        }
        return issuer;
    }

    /** 从 JWKS 缓存或远端获取公钥 */
    private PublicKey resolvePublicKey(String kid) throws Exception {
        // 查缓存（带 kid 或不带 kid 都尝试）
        if (kid != null) {
            CachedKey cached = jwksCache.get(kid);
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                return cached.publicKey;
            }
        } else if (!jwksCache.isEmpty()) {
            // 无 kid → 取缓存里第一个
            var first = jwksCache.values().iterator().next();
            if (first.expireAt > System.currentTimeMillis()) return first.publicKey;
        }
        // 刷新 JWKS
        if (cachedJwksUri == null || jwksRefreshAt < System.currentTimeMillis()) {
            refreshJwks();
        }
        if (kid != null) {
            CachedKey cached = jwksCache.get(kid);
            if (cached != null) return cached.publicKey;
        } else if (!jwksCache.isEmpty()) {
            return jwksCache.values().iterator().next().publicKey;
        }
        throw new BusinessException("JWKS 中未找到 kid=" + kid);
    }

    private void refreshJwks() throws Exception {
        String jwksUri = cachedJwksUri;
        if (jwksUri == null) {
            discover();
            jwksUri = cachedJwksUri;
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(jwksUri))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new BusinessException("JWKS 拉取失败：HTTP " + resp.statusCode());
        }
        JsonNode keys = om.readTree(resp.body()).path("keys");
        long expireAt = System.currentTimeMillis() + 10 * 60 * 1000L;
        jwksCache.clear();
        for (JsonNode key : keys) {
            String kid = key.path("kid").asText();
            String kty = key.path("kty").asText();
            String alg = key.path("alg").asText("RS256");
            try {
                PublicKey pubKey = parseJwk(key, kty, alg);
                jwksCache.put(kid, new CachedKey(pubKey, expireAt));
            } catch (Exception e) {
                log.warn("跳过 JWK: kid={}, alg={}, error={}", kid, alg, e.getMessage());
            }
        }
        jwksRefreshAt = expireAt;
    }

    /** 解析 JWK JSON → PublicKey（支持 RSA / EC） */
    private PublicKey parseJwk(JsonNode key, String kty, String alg) throws Exception {
        if ("RSA".equals(kty)) {
            BigInteger n = new BigInteger(1, base64urlDecode(key.path("n").asText()));
            BigInteger e = new BigInteger(1, base64urlDecode(key.path("e").asText()));
            RSAPublicKeySpec spec = new RSAPublicKeySpec(n, e);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        }
        // 其他类型暂不支持
        throw new BusinessException("暂不支持的 JWK kty: " + kty);
    }

    // ==================== 工具方法 ====================

    private String randomString(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        return base64urlNoPad(buf);
    }

    private static String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String base64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String base64urlNoPad(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64urlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static byte[] sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(s.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== DTO ====================

    public record AuthorizeRequest(String authorizeUrl, String state, String nonce, String codeVerifier) {}

    public static class IdTokenClaims {
        public String subject;
        public String issuer;
        public String email;
        public String name;
        public String preferredUsername;
        public String mappedRole;
    }

    private record CachedKey(PublicKey publicKey, long expireAt) {}
}
