package com.wgh.rag.controller.publicaccess.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.controller.support.SseExecutionStreamSink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * OpenAI 兼容入口：让外部系统可直接用 OpenAI 协议调用本系统。
 * <p>
 * <b>路径</b>：{@code POST /v1/chat/completions}、{@code GET /v1/models}
 * （同时提供 {@code /api/open/v1/**} 别名，便于复用现有开放 API 的网关配置）。
 * <p>
 * <b>模型名映射规则</b>：一个“模型”对应一个已启用的聊天窗口（{@code wgh_chat_window}），
 * 因为温度、知识库、工具、Agent/工作流等 RAG 参数都挂在窗口上。请求中的 {@code model} 支持：
 * <ol>
 *     <li>{@code wgh-window-<windowId>}（{@code GET /v1/models} 返回的 id）</li>
 *     <li>纯数字 {@code <windowId>}</li>
 *     <li>窗口名称精确匹配（仅启用状态）</li>
 * </ol>
 * 都不匹配时返回 404 + {@code error.code=model_not_found}。不支持直接传底层 LLM 模型名。
 * <p>
 * <b>鉴权</b>：由 {@code ApiKeyAuthenticationFilter} 统一校验
 * {@code Authorization: Bearer <key>}（或 {@code X-API-Key}），scope 要求：
 * chat/completions 需要 {@code chat:write}，models 需要 {@code model:read}。
 * <p>
 * <b>多轮会话</b>：OpenAI 客户端每次会重发完整 messages，本系统由服务端会话维护上下文。
 * 复用会话请携带扩展字段 {@code session_id}（兼容 {@code sessionId}），响应与分块会回带
 * {@code wgh_session_id}；未携带时每次请求新建会话，不会重放 messages 中的历史。
 */
@Slf4j
@Tag(name = "OpenAI 兼容 API")
@RestController
@RequestMapping({"/v1", "/api/open/v1"})
@RequiredArgsConstructor
public class OpenAiCompatController {

    /** models 列表返回的模型 id 前缀 */
    private static final String WINDOW_MODEL_PREFIX = "wgh-window-";
    /** 单次列出的最大窗口数（模型数量=聊天窗口数，通常远小于该值） */
    private static final long MAX_MODEL_LIST_SIZE = 500L;
    /** 单次补全最长等待时间（覆盖上游 SSE 超时） */
    private static final long COMPLETION_TIMEOUT_SECONDS = 300L;

    private final ChatWindowService windowService;
    private final RagChatService ragChatService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "列出可用模型（已启用的聊天窗口）")
    @GetMapping("/models")
    public Map<String, Object> models() {
        List<Map<String, Object>> data = new ArrayList<>();
        PageResult<ChatWindow> page = windowService.page(1L, MAX_MODEL_LIST_SIZE, null);
        for (ChatWindow window : page.getRecords()) {
            if (window.getStatus() == null || window.getStatus() == 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", WINDOW_MODEL_PREFIX + window.getId());
            item.put("object", "model");
            item.put("created", toEpochSecond(window));
            item.put("owned_by", "wgh-rag");
            data.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "list");
        body.put("data", data);
        return body;
    }

    @Operation(summary = "OpenAI 兼容对话补全（支持 stream=true）")
    @PostMapping(value = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> chatCompletions(
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求体不能为空");
        }
        ChatWindow window = resolveWindow(body.get("model"));
        PromptPayload prompt = extractPrompt(body.get("messages"));
        boolean stream = isTrue(body.get("stream"));
        boolean includeUsage = stream && includeUsage(body.get("stream_options"));

        String identifier = currentApiIdentity();
        Long sessionId = resolveSession(window, body, identifier);

        // 在请求线程内调用，配额/窗口校验等同步异常可返回正确的 HTTP 状态码
        SseEmitter upstream = new SseEmitter(ChatConstants.SSE_TIMEOUT_MS);
        withApiIdentity(identifier, () -> ragChatService.streamChat(window.getId(), sessionId,
                prompt.text(), true, prompt.images(), new SseExecutionStreamSink(upstream)));

        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        String modelName = WINDOW_MODEL_PREFIX + window.getId();

        if (stream) {
            OpenAiCompletionSink sink = new OpenAiCompletionSink(objectMapper, completionId, created,
                    modelName, sessionId, prompt.text(), true, includeUsage);
            StreamingResponseBody responseBody = output -> {
                sink.startStreaming(output);
                OpenAiSseTap.attach(upstream, sink);
                sink.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header(HttpHeaders.CONNECTION, "keep-alive")
                    .header("X-Accel-Buffering", "no")
                    .body(responseBody);
        }

        OpenAiCompletionSink sink = new OpenAiCompletionSink(objectMapper, completionId, created,
                modelName, sessionId, prompt.text(), false, false);
        OpenAiSseTap.attach(upstream, sink);
        sink.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", sink.contentText());

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", completionId);
        response.put("object", "chat.completion");
        response.put("created", created);
        response.put("model", modelName);
        response.put("choices", List.of(choice));
        response.put("usage", sink.usage());
        response.put("wgh_session_id", sessionId);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(output -> objectMapper.writeValue(output, response));
    }

    // ===== 内部方法 =====

    private ChatWindow resolveWindow(Object modelValue) {
        String model = asString(modelValue);
        if (model == null || model.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "model 不能为空");
        }
        String normalized = model.trim();
        Long windowId = null;
        if (normalized.startsWith(WINDOW_MODEL_PREFIX)) {
            windowId = parseLongOrNull(normalized.substring(WINDOW_MODEL_PREFIX.length()));
        } else if (normalized.chars().allMatch(Character::isDigit)) {
            windowId = parseLongOrNull(normalized);
        }

        ChatWindow window = windowId == null ? null : windowService.getVisible(windowId);
        if (window == null) {
            // 退化按窗口名精确匹配（page 的关键字为 LIKE 查询，这里再做一次精确过滤）
            PageResult<ChatWindow> page = windowService.page(1L, MAX_MODEL_LIST_SIZE, normalized);
            window = page.getRecords().stream()
                    .filter(item -> normalized.equals(item.getName()))
                    .findFirst()
                    .orElse(null);
        }
        if (window == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "模型不存在: " + model);
        }
        if (window.getStatus() == null || window.getStatus() == 0) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "模型已停用: " + model);
        }
        return window;
    }

    private Long resolveSession(ChatWindow window, Map<String, Object> body, String identifier) {
        Object raw = body.get("session_id") != null ? body.get("session_id") : body.get("sessionId");
        Long sessionId = parseSessionId(raw);
        if (sessionId == null) {
            ChatSession created = windowService.createSession(window.getId(), identifier);
            return created.getId();
        }
        windowService.getOwnedSession(window.getId(), sessionId, identifier);
        return sessionId;
    }

    private PromptPayload extractPrompt(Object messages) {
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "messages 不能为空");
        }
        PromptPayload prompt = null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> message)) {
                continue;
            }
            if (!"user".equalsIgnoreCase(asString(message.get("role")))) {
                continue;
            }
            prompt = parseContent(message.get("content"));
        }
        if (prompt == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "messages 中缺少 user 消息");
        }
        if (prompt.text().isBlank() && prompt.images().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "messages 中最后一条 user 消息内容为空");
        }
        return prompt;
    }

    /**
     * 支持 OpenAI 的多模态 content：字符串或 [{type:text,...},{type:image_url,...}] 数组。
     */
    private PromptPayload parseContent(Object content) {
        if (content instanceof String text) {
            return new PromptPayload(text.trim(), List.of());
        }
        if (!(content instanceof List<?> parts)) {
            return new PromptPayload("", List.of());
        }
        StringBuilder text = new StringBuilder();
        List<String> images = new ArrayList<>();
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> partMap)) {
                continue;
            }
            String type = asString(partMap.get("type"));
            if ("text".equals(type)) {
                String value = asString(partMap.get("text"));
                if (value != null) {
                    text.append(value);
                }
            } else if ("image_url".equals(type)) {
                Object imageUrl = partMap.get("image_url");
                String url = imageUrl instanceof Map<?, ?> imageMap
                        ? asString(imageMap.get("url")) : asString(imageUrl);
                if (url != null && !url.isBlank()) {
                    images.add(url);
                }
            }
        }
        return new PromptPayload(text.toString().trim(), images);
    }

    private String currentApiIdentity() {
        String identifier = SecurityUtils.currentUsername();
        if (identifier == null || !identifier.startsWith("api_")) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "OpenAI 兼容接口仅支持 API Key 鉴权");
        }
        return identifier;
    }

    private <T> T withApiIdentity(String identifier, Supplier<T> action) {
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

    private boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private boolean includeUsage(Object streamOptions) {
        if (!(streamOptions instanceof Map<?, ?> options)) {
            return false;
        }
        return isTrue(options.get("include_usage"));
    }

    private Long parseSessionId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "session_id 必须为数字");
        }
    }

    private Long parseLongOrNull(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long toEpochSecond(ChatWindow window) {
        return window.getCreateTime() == null ? 0L
                : window.getCreateTime().toEpochSecond(ZoneOffset.UTC);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 请求中提取出的最后一条 user 消息。
     */
    private record PromptPayload(String text, List<String> images) {
    }
}
