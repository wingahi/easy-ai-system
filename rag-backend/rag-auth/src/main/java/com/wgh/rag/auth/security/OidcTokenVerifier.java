package com.wgh.rag.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.PublicKey;
import java.util.Base64;
import java.util.Set;

/**
 * 外部 OIDC token 验证器（资源服务器形态）。
 * <p>
 * 仅使用 IdP JWKS 中的<strong>非对称公钥</strong>验签，禁止 HS256 等对称算法与 none。
 * 校验：签名、alg 白名单、iss、aud、exp / nbf（带时钟偏差）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcTokenVerifier {

    /** 允许的非对称签名算法白名单（拒绝 HS* / none） */
    static final Set<String> ALLOWED_ALGORITHMS = Set.of(
            "RS256", "RS384", "RS512",
            "PS256", "PS384", "PS512",
            "ES256", "ES384", "ES512");

    private final OidcProperties properties;
    private final JwksProvider jwksProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证外部 token 并返回 claims。
     *
     * @throws TokenVerificationException 验签失败、算法不允许、iss/aud/exp/nbf 不符
     */
    public Claims verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw new TokenVerificationException("token 为空");
        }

        if (!StringUtils.hasText(properties.getIssuer()) || !StringUtils.hasText(properties.getAudience())) {
            // 未配置 issuer/audience 时 fail-closed，避免跳过关键校验
            throw new TokenVerificationException("wgh.auth.oidc.issuer/audience 未配置");
        }

        TokenHeader header = parseHeader(token);
        if (!ALLOWED_ALGORITHMS.contains(header.alg())) {
            throw new TokenVerificationException("不支持的 token 签名算法: " + header.alg());
        }

        PublicKey publicKey = jwksProvider.getVerificationKey(header.kid(), header.alg());
        if (publicKey == null) {
            throw new TokenVerificationException("无法从 JWKS 获取验签公钥（kid=" + header.kid() + "）");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .clockSkewSeconds(Math.max(0L, properties.getClockSkewSeconds()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw new TokenVerificationException("token 验签失败: " + e.getClass().getSimpleName(), e);
        }

        validateIssuer(claims);
        validateAudience(claims);
        return claims;
    }

    private void validateIssuer(Claims claims) {
        if (!StringUtils.hasText(properties.getIssuer())) {
            return;
        }
        if (!properties.getIssuer().equals(claims.getIssuer())) {
            throw new TokenVerificationException("token issuer 不匹配");
        }
    }

    private void validateAudience(Claims claims) {
        if (!StringUtils.hasText(properties.getAudience())) {
            return;
        }
        Set<String> audiences = claims.getAudience();
        if (audiences == null || !audiences.contains(properties.getAudience())) {
            throw new TokenVerificationException("token audience 不匹配");
        }
    }

    private TokenHeader parseHeader(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new TokenVerificationException("token 格式非法");
        }
        try {
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            String alg = header.path("alg").asText(null);
            String kid = header.path("kid").asText(null);
            return new TokenHeader(alg, kid);
        } catch (Exception e) {
            throw new TokenVerificationException("token 头部解析失败", e);
        }
    }

    private record TokenHeader(String alg, String kid) {
    }
}
