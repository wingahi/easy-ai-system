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
 * 开放 API / OpenAI 兼容 API 独立鉴权过滤器（作用于 /api/open/** 与 /v1/**）
 * <p>
 * 校验 X-API-Key / Authorization: Bearer，按路径校验 scope，
 * 并记录调用日志（谁、接口、状态码、耗时）。
 * <p>
 * /v1/** 为 OpenAI 兼容入口，鉴权失败时返回 OpenAI 错误结构
 * （{@code {"error":{"message","type","param","code"}}}），
 * 原有 /api/open/** 仍返回统一 Result 结构，互不影响。
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
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/open/") || uri.startsWith("/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        ApiKey key = null;
        try {
            String rawKey = extractKey(request);
            if (rawKey == null || rawKey.isBlank()) {
                writeError(request, response, ResultCode.TOKEN_INVALID.getCode(),
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
                writeError(request, response, e.getCode(), e.getMessage());
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
     * <p>
     * OpenAI 兼容入口：/v1/chat/completions 需要 chat:write，/v1/models 需要 model:read。
     */
    private String requiredScope(String uri) {
        if (uri.endsWith("/v1/models")) return AuthConstants.PERM_MODEL_READ;
        if (uri.contains("/v1/chat/")) return AuthConstants.PERM_CHAT_WRITE;
        if (uri.contains("/v1/kb/")) return AuthConstants.PERM_KB_READ;
        if (uri.contains("/v1/usage")) return AuthConstants.PERM_USAGE_READ;
        return null;
    }

    /**
     * OpenAI 兼容入口（/v1/** 及别名 /api/open/v1/chat/completions、/api/open/v1/models）
     * 使用 OpenAI 错误结构，其余开放 API 保持原有 Result 结构。
     */
    private boolean isOpenAiProtocol(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/v1/")
                || uri.endsWith("/v1/chat/completions")
                || uri.endsWith("/v1/models");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            Integer code, String message) throws IOException {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        if (ResultCode.PERMISSION_DENIED.getCode().equals(code)) status = HttpStatus.FORBIDDEN;
        else if (ResultCode.QUOTA_EXCEEDED.getCode().equals(code)) status = HttpStatus.TOO_MANY_REQUESTS;
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        if (isOpenAiProtocol(request)) {
            response.getWriter().write(objectMapper.writeValueAsString(
                    openAiError(status, message)));
            return;
        }
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
    }

    private java.util.Map<String, Object> openAiError(HttpStatus status, String message) {
        String type;
        String errorCode;
        if (status == HttpStatus.UNAUTHORIZED) {
            type = "authentication_error";
            errorCode = "invalid_api_key";
        } else if (status == HttpStatus.FORBIDDEN) {
            type = "permission_error";
            errorCode = "insufficient_permissions";
        } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
            type = "rate_limit_error";
            errorCode = "insufficient_quota";
        } else {
            type = "invalid_request_error";
            errorCode = "invalid_request";
        }
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("message", message);
        detail.put("type", type);
        detail.put("param", null);
        detail.put("code", errorCode);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", detail);
        return body;
    }
}
