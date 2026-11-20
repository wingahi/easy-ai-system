package com.wgh.rag.chat.service;

import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.chat.service.session.ChatSessionStore;
import com.wgh.rag.chat.service.stream.ChatSsePublisher;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.workflow.constant.WorkflowEvents;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天窗口工作流执行服务 —— 把工作流引擎的原始事件映射为聊天 SSE 协议，
 * 并在运行结束时把最终答复写入会话消息（与普通聊天/Agent 链路保持一致的落库语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatWorkflowService {

    private static final long SSE_TIMEOUT_MS = ChatConstants.SSE_TIMEOUT_MS;

    private final WorkflowService workflowService;
    private final ChatMessageStore messageStore;
    private final ChatSessionStore sessionStore;

    /**
     * 聊天窗口触发工作流：复用当前请求的 SSE 通道，每条用户消息运行一次完整工作流。
     */
    public WorkflowRun stream(ChatWindow window, ChatSession session, String userMessage,
                              SseEmitter emitter, boolean safeMode) {
        StreamContext ctx = new StreamContext(emitter, session, new AtomicBoolean(false));
        return workflowService.runForChat(window.getWorkflowId(), userMessage, session.getId(), safeMode,
                (event, payload) -> handleEvent(ctx, event, payload));
    }

    /**
     * 聊天窗口的 HITL 确认：从断点续跑并返回新的 SSE 通道（事件同样映射为聊天协议）。
     */
    public SseEmitter hitlConfirm(Long runId, Long sessionId, String userInput, boolean safeMode) {
        WorkflowRun run = workflowService.getRun(runId);
        if (sessionId != null && !sessionId.equals(run.getSessionId())) {
            throw new BusinessException("工作流运行不属于当前会话");
        }
        ChatSession session = sessionStore.findById(sessionId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StreamContext ctx = new StreamContext(emitter, session, new AtomicBoolean(false));
        safeSend(emitter, ChatEvents.START, Map.of("sessionId", sessionId == null ? "" : sessionId));
        workflowService.resumeForChat(runId, userInput, (event, payload) -> handleEvent(ctx, event, payload));
        return emitter;
    }

    /** 按会话查询最近一次工作流运行状态（页面刷新恢复用）。 */
    public Map<String, Object> getSessionRunState(Long sessionId) {
        return workflowService.getRunStateBySession(sessionId);
    }

    private void handleEvent(StreamContext ctx, String event, Map<String, Object> payload) {
        Map<String, Object> data = payload == null ? Map.of() : payload;
        switch (event) {
            case WorkflowEvents.NODE_HITL_PENDING -> {
                safeSend(ctx.emitter, event, data);
                // 暂停后本次运行不会再有事件：主动结束 SSE，前端弹窗确认后另开续跑通道
                safeComplete(ctx.emitter);
            }
            case WorkflowEvents.WORKFLOW_COMPLETE -> {
                safeSend(ctx.emitter, event, data);
                if (ctx.terminal.compareAndSet(false, true)) {
                    finishSuccess(ctx, data);
                }
            }
            case WorkflowEvents.WORKFLOW_FAILED -> {
                safeSend(ctx.emitter, event, data);
                if (ctx.terminal.compareAndSet(false, true)) {
                    String error = data.get("error") == null ? "工作流执行失败" : String.valueOf(data.get("error"));
                    safeSend(ctx.emitter, ChatEvents.ERROR, Map.of("text", error));
                    safeSend(ctx.emitter, ChatEvents.DONE, Map.of("fullResponse", ""));
                    safeComplete(ctx.emitter);
                }
            }
            case WorkflowEvents.WORKFLOW_CANCELLED -> {
                safeSend(ctx.emitter, event, data);
                if (ctx.terminal.compareAndSet(false, true)) {
                    safeSend(ctx.emitter, ChatEvents.DONE, Map.of("fullResponse", ""));
                    safeComplete(ctx.emitter);
                }
            }
            default -> safeSend(ctx.emitter, event, data);
        }
    }

    private void finishSuccess(StreamContext ctx, Map<String, Object> payload) {
        String output = payload.get("output") == null ? "" : String.valueOf(payload.get("output"));
        if (output.isBlank()) {
            output = "工作流执行完成，但没有产出文本内容。";
        }
        if (ctx.session != null) {
            messageStore.saveWorkflowMessage(ctx.session, output);
        } else {
            log.warn("工作流完成但会话缺失，跳过 assistant 消息落库: runId={}", payload.get("runId"));
        }

        // 与普通聊天一致：最终文本通过 content 事件下发（JSON 包装避免换行丢失）
        safeSend(ctx.emitter, ChatEvents.CONTENT, Map.of("text", output));
        safeSend(ctx.emitter, ChatEvents.SOURCES, List.of());
        Map<String, Object> done = new HashMap<>();
        done.put("fullResponse", output);
        if (payload.get("runId") != null) done.put("runId", payload.get("runId"));
        safeSend(ctx.emitter, ChatEvents.DONE, done);
        safeComplete(ctx.emitter);
    }

    private void safeSend(SseEmitter emitter, String eventName, Object data) {
        ChatSsePublisher.sendQuietly(emitter, eventName, data);
    }

    private void safeComplete(SseEmitter emitter) {
        ChatSsePublisher.completeQuietly(emitter);
    }

    /** 单次运行的事件流上下文（终态只处理一次） */
    private record StreamContext(SseEmitter emitter, ChatSession session, AtomicBoolean terminal) {
    }
}
