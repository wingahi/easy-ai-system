package com.wgh.rag.controller;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.JwtUtils;
import com.wgh.rag.common.security.OidcProperties;
import com.wgh.rag.common.security.OidcService;
import com.wgh.rag.controller.dto.UserResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * OIDC 登录端点
 * <p>
 * 授权码模式（带 PKCE）：/authorize → IdP → /callback → 签发本地 JWT → 前端
 */
@Slf4j
@Tag(name = "OIDC 登录")
@RestController
@RequestMapping("/api/auth/oidc")
@RequiredArgsConstructor
public class OidcController {

    private final OidcService oidcService;
    private final AuthRBACService authRBACService;
    private final JwtUtils jwtUtils;
    private final OidcProperties oidcProperties;

    /**
     * 发起 OIDC 授权跳转（302 → IdP authorization endpoint）
     */
    @Operation(summary = "发起 OIDC 授权跳转")
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request) {
        if (!oidcService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        OidcService.AuthorizeRequest req = oidcService.generateAuthorizeRequest();
        // 把 codeVerifier 存在 HttpSession 里（短暂使用）
        jakarta.servlet.http.HttpSession session = request.getSession(true);
        if (req.codeVerifier() != null) {
            session.setAttribute("oidc_code_verifier", req.codeVerifier());
        }
        session.setAttribute("oidc_state", req.state());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(req.authorizeUrl())).build();
    }

    /**
     * IdP 回调：校验 state → 换 token → 验签 → 自动建号 → 签发本地 JWT → 重定向前端
     */
    @Operation(summary = "OIDC 回调处理")
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request) {

        Map<String, Object> data = new HashMap<>();

        if (error != null) {
            log.warn("OIDC 回调携带 error: {}", error);
            data.put("error", error);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(data);
        }
        if (code == null || state == null) {
            data.put("error", "missing_code_or_state");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(data);
        }

        try {
            String codeVerifier = null;
            Object sessionCodeVerifier = request.getSession(false) != null
                    ? request.getSession(false).getAttribute("oidc_code_verifier")
                    : null;
            if (sessionCodeVerifier != null) {
                codeVerifier = sessionCodeVerifier.toString();
            }

            OidcService.IdTokenClaims claims = oidcService.handleCallback(code, state, codeVerifier);

            // 自动建号 / 复用
            UserResult user = authRBACService.loginByOidc(
                    claims.issuer, claims.subject, claims.email, claims.name);

            // 签发本地 JWT
            String token = jwtUtils.generateToken(user.getUserId(), user.getUsername());

            data.put("token", token);
            data.put("userId", user.getUserId());
            data.put("username", user.getUsername());
            data.put("roles", user.getRoles());
            data.put("permissions", user.getPermissions());
            data.put("mappedRole", claims.mappedRole);
            return ResponseEntity.ok(data);
        } catch (BusinessException e) {
            log.warn("OIDC 回调失败: {}", e.getMessage());
            data.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(data);
        } catch (Exception e) {
            log.error("OIDC 回调异常", e);
            data.put("error", "oidc_callback_failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(data);
        }
    }

    /**
     * OIDC 登出 —— 本地会话失效（前端清除 JWT 即可；若 IdP 支持 end_session_endpoint 可在此重定向）
     */
    @Operation(summary = "OIDC 登出")
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        // JWT 无状态，客户端删除 token 即可
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * OIDC 健康检查 —— 返回是否启用
     */
    @Operation(summary = "OIDC 健康检查")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", oidcProperties.isEnabled());
        if (oidcProperties.isEnabled()) {
            result.put("issuer", oidcProperties.getIssuer());
            result.put("clientId", oidcProperties.getClientId());
            result.put("redirectUri", oidcProperties.getRedirectUri());
            result.put("defaultRole", oidcProperties.getDefaultRole());
        }
        return result;
    }
}
