package com.wgh.rag.common.web;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 请求关联 ID 过滤器测试：沿用合法 ID、生成 UUID、拒绝头注入、异步分发复用、MDC 清理。
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    @DisplayName("RID-01 合法 X-Request-Id 原样沿用并写入 MDC 与响应头")
    void reuseValidHeader() throws Exception {
        String incoming = "req-abc_123.456";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.addHeader(RequestIdFilter.HEADER_NAME, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, resp) -> mdcDuringChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertEquals(incoming, mdcDuringChain.get());
        assertEquals(incoming, response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "请求结束后必须清理 MDC，避免线程复用串号");
    }

    @Test
    @DisplayName("RID-02 缺失 X-Request-Id 时生成 UUID 并回写响应头")
    void generateWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        String generated = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertNotNull(generated);
        assertEquals(36, generated.length());
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    @DisplayName("RID-03 含 CRLF/空白等非法字符的请求 ID 被拒绝并重新生成")
    void rejectHeaderInjection() throws Exception {
        String malicious = "abc\r\nX-Injected: evil";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.addHeader(RequestIdFilter.HEADER_NAME, malicious);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
        });

        String resolved = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertNotNull(resolved);
        assertNotEquals(malicious, resolved);
        // 校验失败必须走 UUID 分支，不能把原始头写回响应
        assertEquals(36, resolved.length());
    }

    @Test
    @DisplayName("RID-04 ASYNC 分发复用首次请求的 requestId")
    void asyncDispatchReusesRequestId() throws Exception {
        String incoming = "async-req-0001";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/stream");
        request.addHeader(RequestIdFilter.HEADER_NAME, incoming);

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(request, firstResponse, (req, resp) -> {
        });

        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse asyncResponse = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringAsync = new AtomicReference<>();
        filter.doFilter(request, asyncResponse, (req, resp) -> mdcDuringAsync.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertEquals(incoming, mdcDuringAsync.get());
        assertEquals(incoming, asyncResponse.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
