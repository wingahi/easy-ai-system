package com.wgh.rag.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.auth.tenant.TenantProperties;
import com.wgh.rag.auth.tenant.TenantService;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 租户上下文过滤器（认证之后执行）。
 * <p>
 * 从已认证的 {@link LoginUser} 取租户编码（绝不从请求参数取），写入
 * {@link TenantContext} 供数据面、审计、异步任务使用；请求结束清理。
 * <p>
 * 开启 {@code wgh.tenant.enforcement} 后：缺失、未知或停用租户一律 401；
 * 关闭时（默认，兼容存量单租户部署）未知租户回落 default。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantService tenantService;
    private final TenantProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        LoginUser loginUser = LoginUser.get();
        if (loginUser == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String requested = loginUser.getTenantId();
        String tenantId;
        try {
            if (properties.isEnforcement() && !StringUtils.hasText(requested)) {
                log.warn("拒绝无租户凭证的请求: username={}, {} {}",
                        loginUser.getUsername(), request.getMethod(), request.getRequestURI());
                writeUnauthorized(response, "令牌缺少租户信息");
                return;
            }
            tenantId = tenantService.resolveOrReject(requested);
        } catch (RuntimeException e) {
            log.warn("租户校验失败: username={}, tenant={}, reason={}",
                    loginUser.getUsername(), requested, e.getMessage());
            writeUnauthorized(response, e.getMessage());
            return;
        }

        TenantContext.set(tenantId, loginUser.getUserId(), loginUser.getUsername());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Result<Void> body = Result.fail(ResultCode.TOKEN_INVALID.getCode(), message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
