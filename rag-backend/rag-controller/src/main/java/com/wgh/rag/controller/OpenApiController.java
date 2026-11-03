package com.wgh.rag.controller;

import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.kb.service.RetrievalOptions;
import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.entity.ApiCallLog;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.service.ApiCallLogService;
import com.wgh.rag.publicaccess.service.ApiKeyService;
import com.wgh.rag.publicaccess.service.ClientIpResolver;
import com.wgh.rag.publicaccess.service.PublicAccessRateLimiter;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.service.QuotaService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放 API（独立 API Key 鉴权，见 ApiKeyAuthenticationFilter）
 * <p>
 * scope 约定：chat:write（对话）、kb:read（知识库检索）、usage:read（用量）、*（全部）
 */
@Tag(name = "开放 API")
@RestController
@RequestMapping("/api/open/v1")
@RequiredArgsConstructor
public class OpenApiController {

    private final ApiKeyService apiKeyService;
    private final ApiCallLogService apiCallLogService;
    private final PublicAccessRateLimiter rateLimiter;
    private final PublicAccessProperties properties;
    private final ChatWindowService windowService;
    private final ChatSessionMapper sessionMapper;
    private final RagChatService ragChatService;
    private final RagRetrieveService retrieveService;
    private final QuotaService quotaService;
    private final ClientIpResolver clientIpResolver;

    @Operation(summary = "当前 API Key 信息")
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        ApiKey key = currentKey();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", key.getId());
        data.put("name", key.getName());
        data.put("scopes", key.getScopes());
        data.put("expiresAt", key.getExpiresAt());
        data.put("lastUsedTime", key.getLastUsedTime());
        return Result.success(data);
    }

    @Operation(summary = "开放 API：匿名流式问答（scope=chat:write）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ApiKey key = currentKey();
        rateLimit(key, request);

        Long windowId = body.get("windowId") == null ? null
                : Long.parseLong(String.valueOf(body.get("windowId")));
        if (windowId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "windowId 不能为空");
        }
        ChatWindow window = windowService.getById(windowId);
        if (window.getStatus() == null || window.getStatus() == 0) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "聊天窗口已停用");
        }

        String identifier = "api_" + key.getId();
        Long sessionId = body.get("sessionId") == null ? null
                : Long.parseLong(String.valueOf(body.get("sessionId")));
        if (sessionId == null) {
            sessionId = windowService.createSession(windowId, identifier).getId();
        } else {
            windowService.getOwnedSession(windowId, sessionId, identifier);
        }

        String message = body.get("message") == null ? "" : String.valueOf(body.get("message")).trim();
        if (message.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "message 不能为空");
        }
        List<String> images = parseImages(body.get("imageUrls"));
        Long finalSessionId = sessionId;
        return withApiIdentity(identifier, () ->
                ragChatService.streamChat(windowId, finalSessionId, message, true, images));
    }

    @Operation(summary = "开放 API：知识库检索（scope=kb:read）")
    @GetMapping("/kb/search")
    public Result<Map<String, Object>> kbSearch(
            @RequestParam Long kbId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") Integer topK,
            HttpServletRequest request) {
        ApiKey key = currentKey();
        rateLimit(key, request);
        if (query == null || query.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "query 不能为空");
        }
        List<EmbeddingMatch<TextSegment>> matches = retrieveService.searchMultiple(
                List.of(kbId), query,
                new RetrievalOptions(null, false, null, null, Math.min(Math.max(topK, 1), 20))).matches();
        List<Map<String, Object>> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", m.score());
            item.put("content", m.embedded() != null ? m.embedded().text() : null);
            if (m.embedded() != null && m.embedded().metadata() != null) {
                item.put("fileName", m.embedded().metadata().getString("fileName"));
                item.put("kbName", m.embedded().metadata().getString("kbName"));
            }
            results.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        data.put("query", query);
        data.put("results", results);
        data.put("total", results.size());
        return Result.success(data);
    }

    @Operation(summary = "开放 API：用量统计（scope=usage:read）")
    @GetMapping("/usage")
    public Result<Map<String, Object>> usage() {
        ApiKey key = currentKey();
        String username = "api_" + key.getId();
        QuotaUsage quota = quotaService.todayUsage(username);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiKeyId", key.getId());
        data.put("today", quotaService.todayKey());
        data.put("chatCount", quota == null ? 0 : quota.getChatCount());
        data.put("tokenUsed", quota == null ? 0 : quota.getTokenUsed());
        data.put("totalCalls", apiCallLogService.countByKey(key.getId()));
        List<ApiCallLog> recent = apiCallLogService.recent(key.getId(), 20);
        data.put("recentCalls", recent);
        return Result.success(data);
    }

    // ===== 内部方法 =====

    /**
     * 开放 API 按 API Key 与客户端 IP 两个维度分别限流，均包含接口路径。
     */
    private void rateLimit(ApiKey key, HttpServletRequest request) {
        String path = request.getRequestURI();
        rateLimiter.acquire("api-key", String.valueOf(key.getId()), path);
        rateLimiter.acquire("api-ip", clientIpResolver.resolve(request), path);
    }

    private ApiKey currentKey() {
        String username = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getName();
        Long id = parseKeyId(username);
        if (id == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未识别到 API Key");
        }
        ApiKey key = apiKeyService.findById(id);
        if (key == null) throw new BusinessException(ResultCode.TOKEN_INVALID, "API Key 不存在");
        key.setKeyHash(null);
        return key;
    }

    private Long parseKeyId(String username) {
        if (username == null || !username.startsWith("api_")) return null;
        try {
            return Long.parseLong(username.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private <T> T withApiIdentity(String identifier, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext temp = SecurityContextHolder.createEmptyContext();
        temp.setAuthentication(new UsernamePasswordAuthenticationToken(
                identifier, null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));
        SecurityContextHolder.setContext(temp);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseImages(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> images = new ArrayList<>();
            for (Object o : list) if (o != null) images.add(String.valueOf(o));
            if (images.size() > properties.getMaxImagesPerRequest()) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "单次最多上传 " + properties.getMaxImagesPerRequest() + " 张图片");
            }
            return images;
        }
        return List.of();
    }
}
