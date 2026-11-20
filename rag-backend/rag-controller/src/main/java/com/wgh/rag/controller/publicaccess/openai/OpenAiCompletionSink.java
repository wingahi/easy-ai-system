package com.wgh.rag.controller.publicaccess.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.llm.util.TokenEstimator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 RAG 聊天事件流转换为 OpenAI 协议的分块/一次性响应。
 * <p>
 * 流式模式下每个 {@code content} 事件立即写出一帧
 * {@code data: {"object":"chat.completion.chunk",...}}，实现逐 token 透传；
 * 非流式模式只累积内容，{@link #await} 返回后由 Controller 组装标准 chat.completion。
 * <p>
 * 注意：这里不记录 Prompt 或 API Key，仅记录客户端断开等非敏感信息。
 */
@Slf4j
final class OpenAiCompletionSink implements OpenAiSseTap.EventSink {

    private static final String OBJECT_CHUNK = "chat.completion.chunk";

    private final ObjectMapper objectMapper;
    private final String completionId;
    private final long created;
    private final String model;
    private final Long sessionId;
    private final String promptText;
    private final boolean streaming;
    private final boolean includeUsage;

    private final StringBuilder content = new StringBuilder();
    private final CountDownLatch finishedLatch = new CountDownLatch(1);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private volatile String fullResponse;
    private volatile BusinessException failure;
    private volatile OutputStream output;
    private volatile boolean writeBroken;

    OpenAiCompletionSink(ObjectMapper objectMapper, String completionId, long created, String model,
                         Long sessionId, String promptText, boolean streaming, boolean includeUsage) {
        this.objectMapper = objectMapper;
        this.completionId = completionId;
        this.created = created;
        this.model = model;
        this.sessionId = sessionId;
        this.promptText = promptText;
        this.streaming = streaming;
        this.includeUsage = includeUsage;
    }

    /**
     * 绑定 HTTP 输出流并先发送首帧（role=assistant），保证角色帧先于内容帧。
     */
    void startStreaming(OutputStream out) {
        this.output = out;
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        delta.put("content", "");
        writeChunk(chunk(delta, null));
    }

    @Override
    public void event(String name, Object payload) {
        if (finished.get()) {
            return;
        }
        Map<?, ?> data = payload instanceof Map<?, ?> map ? map : null;
        switch (name) {
            case ChatEvents.CONTENT -> {
                String text = data == null ? null : asString(data.get("text"));
                if (text != null && !text.isEmpty()) {
                    content.append(text);
                    if (streaming) {
                        Map<String, Object> delta = new LinkedHashMap<>();
                        delta.put("content", text);
                        writeChunk(chunk(delta, null));
                    }
                }
            }
            case ChatEvents.DONE -> {
                String full = data == null ? null : asString(data.get("fullResponse"));
                if (full != null) {
                    fullResponse = full;
                }
                finishNormally();
            }
            case ChatEvents.ERROR -> {
                String message = data == null ? null : asString(data.get("text"));
                fail(message == null || message.isBlank() ? "生成失败" : message);
            }
            default -> {
                // start / retrieve / sources / accuracy / metrics / judge / tool_* 等非 OpenAI 协议事件忽略
            }
        }
    }

    @Override
    public void completed() {
        finishNormally();
    }

    @Override
    public void failed(Throwable error) {
        fail(error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "生成失败" : error.getMessage());
    }

    /**
     * 等待生成结束。非流式模式下若生成失败，抛出业务异常交由统一错误处理转成 OpenAI 错误体。
     */
    void await(long timeout, TimeUnit unit) {
        try {
            if (!finishedLatch.await(timeout, unit)) {
                throw new BusinessException(ResultCode.LLM_TIMEOUT, "生成超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.LLM_TIMEOUT, "生成被中断");
        }
        if (!streaming && failure != null) {
            throw failure;
        }
    }

    String contentText() {
        return fullResponse != null ? fullResponse : content.toString();
    }

    Map<String, Object> usage() {
        int promptTokens = TokenEstimator.estimate(promptText);
        int completionTokens = TokenEstimator.estimate(contentText());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        return usage;
    }

    // ===== 内部实现 =====

    private void finishNormally() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            if (streaming) {
                writeChunk(chunk(new LinkedHashMap<>(), "stop"));
                if (includeUsage) {
                    Map<String, Object> usageChunk = new LinkedHashMap<>();
                    usageChunk.put("id", completionId);
                    usageChunk.put("object", OBJECT_CHUNK);
                    usageChunk.put("created", created);
                    usageChunk.put("model", model);
                    usageChunk.put("choices", List.of());
                    usageChunk.put("usage", usage());
                    if (sessionId != null) {
                        usageChunk.put("wgh_session_id", sessionId);
                    }
                    writeChunk(usageChunk);
                }
                writeRaw("[DONE]");
            }
        } finally {
            finishedLatch.countDown();
        }
    }

    private void fail(String message) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        failure = new BusinessException(ResultCode.LLM_CALL_FAILED, message);
        try {
            if (streaming) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("message", message);
                detail.put("type", "api_error");
                detail.put("param", null);
                detail.put("code", "internal_error");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", detail);
                writeChunk(body);
                writeRaw("[DONE]");
            }
        } finally {
            finishedLatch.countDown();
        }
    }

    private Map<String, Object> chunk(Object delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", completionId);
        chunk.put("object", OBJECT_CHUNK);
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        if (sessionId != null) {
            chunk.put("wgh_session_id", sessionId);
        }
        return chunk;
    }

    private void writeChunk(Map<String, Object> body) {
        if (output == null || writeBroken) {
            return;
        }
        try {
            writeRaw(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            writeBroken = true;
            log.debug("OpenAI 兼容分块序列化失败: {}", e.getMessage());
        }
    }

    private void writeRaw(String data) {
        if (output == null || writeBroken) {
            return;
        }
        try {
            output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            // 客户端断开：停止写出，但不在日志中输出任何请求内容
            writeBroken = true;
            log.debug("OpenAI 兼容流式写入中断（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
