package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agent.dto.AgentStepResult.RagSourceRecord;
import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatWindowMapper;
import com.wgh.rag.chat.service.flow.ChatMetricContext;
import com.wgh.rag.chat.service.flow.ChatTurnContext;
import com.wgh.rag.chat.service.flow.ChatTurnOrchestrator;
import com.wgh.rag.chat.service.prompt.ChatCitationAssembler;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.chat.service.session.ChatSessionStore;
import com.wgh.rag.chat.service.stream.ChatEventPublisher;
import com.wgh.rag.chat.service.stream.ChatStreamCancellation;
import com.wgh.rag.chat.service.stream.ChatStreamHandle;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.execution.event.ExecutionStreamSink;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.LlmModelService;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 聊天对外入口（Facade）。
 *
 * <p>只负责：窗口/分享校验、图片校验、配额、会话确保、SSE 与取消句柄创建、请求线程到流式线程的切换。
 * 具体链路编排由 {@link ChatTurnOrchestrator} 按有序步骤执行；SSE、落库、预算、成本、引用等
 * 横切能力由同包支撑类承担。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private final ChatWindowMapper windowMapper;
    private final ChatImageService chatImageService;
    private final QuotaService quotaService;
    private final LlmModelService llmModelService;
    private final ChatSessionStore sessionStore;
    private final ChatMessageStore messageStore;
    private final ChatTurnOrchestrator orchestrator;
    private final ChatCancellationStore chatCancellationStore;
    private final RagMetrics metrics;
    private final ToolExecutionEngine toolExecutionEngine;
    /** 统一受管执行器（MDC/异常/有界队列由 Bean 统一处理，P3 可替换为调度中心实现） */
    private final AsyncTaskExecutor ragAsyncExecutor;

    /**
     * 流式聊天（传输无关）：事件写入调用方提供的事件出口，返回连接生命周期句柄。
     *
     * <p>事件名、顺序、完成/取消/失败时序与既有 SSE 契约完全一致；SSE 的具体写出由接入层适配器负责。</p>
     *
     * @param shareMode 分享态标识：分享运行时对工作流工具节点启用安全模式
     * @param imageUrls 图片 data URL 或公网 URL，需窗口模型 supportsVision=true
     * @param eventSink 事件出口（SSE 适配器 / 后台作业 sink）
     */
    @Transactional
    public ChatStreamHandle streamChat(Long windowId, Long sessionId, String userMessage,
                                       boolean shareMode, List<String> imageUrls,
                                       ExecutionStreamSink eventSink) {
        ChatWindow window = windowMapper.selectById(windowId);
        if (window == null || window.getStatus() == 0) {
            throw new BusinessException("聊天窗口不可用");
        }

        final List<String> images = imageUrls == null ? List.of() : imageUrls;
        if (!images.isEmpty()) {
            chatImageService.validateImageUrls(images);
            LlmModel chatModel = llmModelService.getChatModel(window.getLlmModelId());
            if (!Boolean.TRUE.equals(chatModel.getSupportsVision())) {
                throw new BusinessException("当前窗口模型不支持图片输入，请切换视觉模型（如 qwen-vl / gpt-4o）");
            }
        }

        // 配额限流：发送前检查并计数（超限抛业务异常，请求线程内执行可取到登录用户）
        final String quotaUsername = SecurityUtils.currentUsername();
        final QuotaService.QuotaCharge quotaCharge = quotaService.checkAndIncrementChat(quotaUsername, windowId);

        // 确保会话存在（会话归属当前登录身份，防止跨用户续聊）
        ChatSession session = sessionStore.ensureSession(sessionId, window, SecurityUtils.currentUsername());
        // 扣费流水回填会话关联，便于对账（失败不影响主流程）
        quotaService.attachSession(quotaCharge, session.getId());

        // 首条消息生成会话标题，避免历史会话列表全是「新对话」
        sessionStore.applyAutoTitle(session, userMessage);

        ChatEventPublisher events = new ChatEventPublisher(eventSink);
        // 单次对话的取消句柄：客户端断开或显式取消时置位，流式回调据此短路。
        // 传入会话级共享 key，使显式停止请求落到任意实例都能被本流感知（Redis 不可用时退回进程内）。
        ChatStreamCancellation cancellation = new ChatStreamCancellation(
                ChatCancellationStore.sessionKey(session.getId()), chatCancellationStore);
        ChatStreamHandle handle = new ChatStreamHandle(cancellation, session.getId());

        // 保存用户消息（多模态：图片序列化进 attachments）
        com.wgh.rag.chat.entity.ChatMessage userMsg =
                messageStore.saveUserMessage(session.getId(), userMessage, images);

        // 子线程执行 RAG + LLM 并推送事件（MDC 由统一执行器透传，SSE 日志可按 requestId 串联）
        try {
            ragAsyncExecutor.execute(() -> {
            // 指标上下文：route 在链路分支处回填，首 token 延迟用于区分冷启动/模型抖动
            Timer.Sample sample = metrics.startSample();
            ChatMetricContext metricCtx = new ChatMetricContext(
                    new AtomicReference<>("rag"), new AtomicBoolean(false), System.nanoTime());
            ChatTurnContext ctx = ChatTurnContext.builder()
                    .window(window)
                    .session(session)
                    .userMessage(userMessage)
                    .events(events)
                    .userMsg(userMsg)
                    .shareMode(shareMode)
                    .quotaUsername(quotaUsername)
                    .imageUrls(images)
                    .metricCtx(metricCtx)
                    .cancellation(cancellation)
                    .build();
            // 执行已真正开始：扣费流水置 SETTLED，避免补偿作业把运行中的请求误退
            quotaService.markChargeSettled(quotaCharge);
            try {
                orchestrator.execute(ctx);
            } catch (Exception e) {
                metrics.recordChatError("stream");
                log.error("流式聊天异常", e);
                try {
                    events.send(ChatEvents.ERROR,
                            Map.of("text", e.getMessage() != null ? e.getMessage() : "未知错误"));
                    events.completeWithError(e);
                } catch (IOException ex) {
                    log.error("发送错误消息失败", ex);
                }
            } finally {
                metrics.recordChatRequest(metricCtx.route().get(), shareMode);
                metrics.recordChatDuration(sample, metricCtx.route().get(), shareMode);
                // 清理当前线程的工具注册（ThreadLocal）
                toolExecutionEngine.clearWindowTools();
            }
            });
        } catch (TaskRejectedException e) {
            // 任务未提交成功，执行不会发生：按扣费凭据原路退回，并区分「已退款/重复/无款可退/退款失败」
            QuotaService.RefundResult refund = quotaService.refundChat(quotaCharge);
            metrics.recordQuotaRefund(refund.name().toLowerCase(Locale.ROOT));
            if (refund == QuotaService.RefundResult.FAILED) {
                log.error("统一执行器拒绝聊天任务，配额退款失败: username={}, chargeId={}",
                        quotaUsername, quotaCharge == null ? null : quotaCharge.chargeId());
            } else {
                log.warn("统一执行器拒绝聊天任务，配额已处理: username={}, result={}",
                        quotaUsername, refund);
            }
            // 保持既有失败语义：异常上抛，由事务回滚与全局异常处理兜底
            throw e;
        }

        return handle;
    }

    /**
     * 分享窗口流式聊天（无需登录，验证 shareToken）
     * sessionId 为空时复用该匿名用户最近一次会话，否则使用指定会话（需校验归属）
     */
    public ChatStreamHandle streamShareChat(String shareToken, String userMessage, String sessionKey,
                                            Long sessionId, List<String> imageUrls,
                                            ExecutionStreamSink eventSink) {
        ChatWindow window = windowMapper.selectOne(
                new LambdaQueryWrapper<ChatWindow>()
                        .eq(ChatWindow::getShareToken, shareToken)
                        .eq(ChatWindow::getShareEnabled, 1)
                        .eq(ChatWindow::getStatus, 1));

        if (window == null) {
            throw new BusinessException("分享链接无效或已停用");
        }

        String userIdentifier = sessionKey != null ? sessionKey : "anon_" + UUID.randomUUID();
        ChatSession session = sessionStore.resolveShareSession(window, sessionId, userIdentifier);
        return streamChat(window.getId(), session.getId(), userMessage, true, imageUrls, eventSink);
    }

    /** HITL 确认恢复执行后的最终答复补写（REST 通道没有 SSE，需要主动落库）。 */
    public void appendAssistantMessage(Long sessionId, String content) {
        messageStore.appendAssistantMessage(sessionId, content);
    }

    // ============ 兼容既有测试/调用方的引用装配入口 ============

    List<Map<String, Object>> buildSourcesList(List<EmbeddingMatch<TextSegment>> matches) {
        return ChatCitationAssembler.buildSourcesList(matches);
    }

    String extractRagContext(List<EmbeddingMatch<TextSegment>> matches) {
        return ChatCitationAssembler.extractRagContext(matches);
    }

    static List<Map<String, Object>> buildSourcesListFromRecords(List<RagSourceRecord> records) {
        return ChatCitationAssembler.buildSourcesListFromRecords(records);
    }
}
