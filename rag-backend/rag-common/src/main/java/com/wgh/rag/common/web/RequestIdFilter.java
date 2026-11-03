package com.wgh.rag.common.web;

import com.wgh.rag.common.constant.HttpConstants;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求关联 ID 过滤器：生成/沿用 X-Request-Id，写入 MDC 并回写响应头。
 * <ul>
 *   <li>格式校验：仅允许 {@code [A-Za-z0-9._-]{8,64}}，拒绝 CRLF/空白等头注入字符；</li>
 *   <li>非法或缺失时生成 UUID；</li>
 *   <li>异步/错误分发同样参与（SSE 完成阶段日志仍可关联同一请求）；</li>
 *   <li>finally 清理 MDC，避免 Tomcat 线程复用导致串号。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = HttpConstants.HEADER_X_REQUEST_ID;
    public static final String MDC_KEY = "requestId";
    private static final String ATTR_NAME = RequestIdFilter.class.getName() + ".requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // SSE 完成时会触发 ASYNC 分发，需保证日志仍带同一 requestId
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        request.setAttribute(ATTR_NAME, requestId);
        response.setHeader(HEADER_NAME, requestId);

        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous != null) {
                MDC.put(MDC_KEY, previous);
            } else {
                MDC.remove(MDC_KEY);
            }
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        DispatcherType type = request.getDispatcherType();
        if (type == DispatcherType.ASYNC || type == DispatcherType.ERROR) {
            Object cached = request.getAttribute(ATTR_NAME);
            if (cached instanceof String cachedId && SAFE_ID.matcher(cachedId).matches()) {
                return cachedId;
            }
        }
        String incoming = request.getHeader(HEADER_NAME);
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
