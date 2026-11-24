package com.wgh.rag.controller.publicaccess.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.publicaccess.config.ApiKeyAuthenticationFilter;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.service.ApiCallLogService;
import com.wgh.rag.publicaccess.service.ApiKeyService;
import com.wgh.rag.publicaccess.service.ClientIpResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 /v1/** 复用现有 API Key 鉴权规则，且失败时返回 OpenAI 错误结构。
 */
class ApiKeyAuthenticationFilterOpenAiTest {

    private ApiKeyService apiKeyService;
    private ApiKeyAuthenticationFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        com.wgh.rag.auth.tenant.TenantService tenantService =
                mock(com.wgh.rag.auth.tenant.TenantService.class);
        when(tenantService.isActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        filter = new ApiKeyAuthenticationFilter(apiKeyService, mock(ApiCallLogService.class),
                mock(ClientIpResolver.class), objectMapper, tenantService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAuthenticateValidKeyOnV1Endpoint() throws Exception {
        ApiKey key = new ApiKey();
        key.setId(7L);
        when(apiKeyService.verify("wgh_sk_valid")).thenReturn(key);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader("Authorization", "Bearer wgh_sk_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("api_7");
        verify(apiKeyService).requireScope(key, "chat:write");
    }

    @Test
    void shouldRejectMissingKeyWithOpenAiError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("type").asText()).isEqualTo("authentication_error");
        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_api_key");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void shouldRejectInvalidKeyWithOpenAiError() throws Exception {
        when(apiKeyService.verify(anyString()))
                .thenThrow(new BusinessException(ResultCode.TOKEN_INVALID, "API Key 无效"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader("X-API-Key", "wgh_sk_bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_api_key");
    }

    @Test
    void shouldRejectMissingScopeWithPermissionError() throws Exception {
        ApiKey key = new ApiKey();
        key.setId(9L);
        when(apiKeyService.verify("wgh_sk_limited")).thenReturn(key);
        doThrow(new BusinessException(ResultCode.PERMISSION_DENIED, "缺少 chat:write 权限"))
                .when(apiKeyService).requireScope(eq(key), eq("chat:write"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader("Authorization", "Bearer wgh_sk_limited");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("error").path("type").asText()).isEqualTo("permission_error");
        assertThat(body.path("error").path("code").asText()).isEqualTo("insufficient_permissions");
    }

    @Test
    void shouldIgnoreNonOpenAiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/windows");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(apiKeyService, never()).verify(anyString());
    }
}
