package com.wgh.rag.chat.service;

import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.chat.service.session.ChatSessionStore;
import com.wgh.rag.chat.service.stream.ChatEventPublisher;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.execution.event.ExecutionStreamSink;
import com.wgh.rag.workflow.constant.WorkflowEvents;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final WorkflowService workflowService;
    private final ChatMessageStore messageStore;
    private final ChatSessionStore sessionStore;

    /**
     * 聊天窗口触发工作流：复用当前请求的 SSE 通道，每条用户消息运行一次完整工作流。
     *
     * <p>运行异步进行：{@code onOutput} 在有效最终文本下发后回调，
     * {@code onFinished} 在工作流到达终态（完成/失败/取消/暂停）时回调且只回调一次，
     * 供调用方在「本轮不可能再产出」时收口计费。</p>
     *
     * @param onOutput   有效最终文本下发后的计费回调，可为 {@code null}
     * @param onFinished 运行终态回调，可为 {@code null}
     */
    public WorkflowRun stream(ChatWindow window, ChatSession session, String userMessage,
                              ChatEventPublisher events, boolean safeMode, Runnable onOutput,
                              Runnable onFinished) {
        StreamContext ctx = new StreamContext(events, session, new AtomicBoolean(false),
                new AtomicBoolean(false), onOutput, onFinished);
        return workflowService.runForChat(window.getWorkflowId(), userMessage, session.getId(), safeMode,
                (event, payload) -> handleEvent(ctx, event, payload));
    }

    /**
     * 聊天窗口的 HITL 确认：从断点续跑并通过事件出口输出（事件同样映射为聊天协议）。
     */
    public void hitlConfirm(Long runId, Long sessionId, String userInput, boolean safeMode,
                            ExecutionStreamSink eventSink) {
        WorkflowRun run = workflowService.getRun(runId);
        if (sessionId != null && !sessionId.equals(run.getSessionId())) {
            throw new BusinessException("工作流运行不属于当前会话");
        }
        ChatSession session = sessionStore.findById(sessionId);

        ChatEventPublisher events = new ChatEventPublisher(eventSink);
        StreamContext ctx = new StreamContext(events, session, new AtomicBoolean(false),
                new AtomicBoolean(false), null, null);
        events.sendQuietly(ChatEvents.START, Map.of("sessionId", sessionId == null ? "" : sessionId));
        workflowService.resumeForChat(runId, userInput, (event, payload) -> handleEvent(ctx, event, payload));
    }

    /** 按会话查询最近一次工作流运行状态（页面刷新恢复用）。 */
    public Map<String, Object> getSessionRunState(Long sessionId) {
        return workflowService.getRunStateBySession(sessionId);
    }

    private void handleEvent(StreamContext ctx, String event, Map<String, Object> payload) {
        Map<String, Object> data = payload == null ? Map.of() : payload;
        switch (event) {
            case WorkflowEvents.NODE_HITL_PENDING -> {
                ctx.events.sendQuietly(event, data);
                // 暂停后本次运行不会再有事件：主动结束 SSE，前端弹窗确认后另开续跑通道
                ctx.events.completeQuietly();
                // 本次运行到此为止，本轮不可能再产出：收口计费
                finishOnce(ctx);
            }
            case WorkflowEvents.WORKFLOW_COMPLETE -> {
                ctx.events.sendQuietly(event, data);
                if (ctx.terminal.compareAndSet(false, true)) {
                    finishSuccess(ctx, data);
                }
                finishOnce(ctx);
            }
            case WorkflowEvents.WORKFLOW_FAILED -> {
                ctx.events.sendQuietly(event, data);
                if (ctx.terminal.compareAndSet(false, true)) {
                    String error = data.get("error") == null ? "工作流执行失败" : String.valueOf(data.get("error"));
                    ctx.events.sendQuietly(ChatEvents.ERROR, Map.of("text", error));
                    ctx.events.sendQuietly(ChatEvents.DONE, Map.of("fullResponse", ""));
                    ctx.events.completeQuietly();
                }
                finishOnce(ctx);
            }
            case WorkflowEvents.WORKFLOW_CANCELLED -> {
                ctx.events.sendQuietly(event, data);
                if (ctx.terminal.compareAndSet(false, true)) {
                    ctx.events.sendQuietly(ChatEvents.DONE, Map.of("fullResponse", ""));
                    ctx.events.completeQuietly();
                }
                finishOnce(ctx);
            }
            default -> ctx.events.sendQuietly(event, data);
        }
    }

    /** 工作流终态回调（只回调一次）：通知调用方本轮已不可能再产出。 */
    private void finishOnce(StreamContext ctx) {
        if (ctx.finished.compareAndSet(false, true) && ctx.onFinished != null) {
            ctx.onFinished.run();
        }
    }

    private void finishSuccess(StreamContext ctx, Map<String, Object> payload) {
        String output = payload.get("output") == null ? "" : String.valueOf(payload.get("output"));
        // 只有工作流真正产出了非空文本才算有效产出；占位话术仅用于展示，不计费
        boolean produced = !output.isBlank();
        if (!produced) {
            output = "工作流执行完成，但没有产出文本内容。";
        }
        if (ctx.session != null) {
            messageStore.saveWorkflowMessage(ctx.session, output);
        } else {
            log.warn("工作流完成但会话缺失，跳过 assistant 消息落库: runId={}", payload.get("runId"));
        }

        // 与普通聊天一致：最终文本通过 content 事件下发（JSON 包装避免换行丢失）
        ctx.events.sendQuietly(ChatEvents.CONTENT, Map.of("text", output));
        if (produced && ctx.onOutput != null) {
            ctx.onOutput.run();
        }
        ctx.events.sendQuietly(ChatEvents.SOURCES, List.of());
        Map<String, Object> done = new HashMap<>();
        done.put("fullResponse", output);
        if (payload.get("runId") != null) done.put("runId", payload.get("runId"));
        ctx.events.sendQuietly(ChatEvents.DONE, done);
        ctx.events.completeQuietly();
    }

    /** 单次运行的事件流上下文（终态只处理一次、终态回调只触发一次） */
    private record StreamContext(ChatEventPublisher events, ChatSession session, AtomicBoolean terminal,
                                 AtomicBoolean finished, Runnable onOutput, Runnable onFinished) {
    }
}
