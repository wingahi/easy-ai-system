package com.wgh.rag.publicaccess.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.service.ApiCallLogService;
import com.wgh.rag.publicaccess.service.ApiKeyService;
import com.wgh.rag.publicaccess.service.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 开放 API 独立鉴权过滤器（只作用于 /api/open/**）
 * <p>
 * 校验 X-API-Key / Authorization: Bearer，按路径校验 scope，
 * 并记录调用日志（谁、接口、状态码、耗时）。
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final ApiCallLogService apiCallLogService;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/open/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        ApiKey key = null;
        try {
            String rawKey = extractKey(request);
            if (rawKey == null || rawKey.isBlank()) {
                writeError(response, ResultCode.TOKEN_INVALID.getCode(),
                        "缺少 API Key（请求头 X-API-Key 或 Authorization: Bearer <key>）");
                return;
            }
            key = apiKeyService.verify(rawKey);
            apiKeyService.requireIpAllowed(key, clientIpResolver.resolve(request));
            apiKeyService.requireScope(key, requiredScope(request.getRequestURI()));

            var authentication = new UsernamePasswordAuthenticationToken(
                    "api_" + key.getId(), rawKey,
                    List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
        } catch (BusinessException e) {
            if (!response.isCommitted()) {
                writeError(response, e.getCode(), e.getMessage());
            }
        } finally {
            if (key != null) {
                apiCallLogService.record(key, request, response.getStatus(),
                        System.currentTimeMillis() - start);
            }
        }
    }

    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader("X-API-Key");
        if (header != null && !header.isBlank()) return header;
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return null;
    }

    /**
     * 路径 → 所需 scope
     */
    private String requiredScope(String uri) {
        if (uri.contains("/v1/chat/")) return AuthConstants.PERM_CHAT_WRITE;
        if (uri.contains("/v1/kb/")) return AuthConstants.PERM_KB_READ;
        if (uri.contains("/v1/usage")) return AuthConstants.PERM_USAGE_READ;
        return null;
    }

    private void writeError(HttpServletResponse response, Integer code, String message) throws IOException {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        if (ResultCode.PERMISSION_DENIED.getCode().equals(code)) status = HttpStatus.FORBIDDEN;
        else if (ResultCode.QUOTA_EXCEEDED.getCode().equals(code)) status = HttpStatus.TOO_MANY_REQUESTS;
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
    }
}
