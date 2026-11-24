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
import com.wgh.rag.chat.service.stream.StreamBillingObserver;
import com.wgh.rag.chat.service.stream.replay.StreamReplayService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.execution.api.InteractiveRunRegistry;
import com.wgh.rag.execution.event.ExecutionStreamSink;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.LlmModelService;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    /** 异步任务等待外层事务提交的最长时间；超时视为未提交，本次不生成。 */
    private static final long COMMIT_GATE_TIMEOUT_SECONDS = 10L;

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
    /** SSE 事件重放：跨副本共享缓冲 + Last-Event-ID 续传（Redis 不可用时自动降级） */
    private final StreamReplayService streamReplayService;
    /** 交互式运行态登记：让管理端「进行中任务」的 type=CHAT 能跨副本查到真实记录（调度中心未启用时为空） */
    private final ObjectProvider<InteractiveRunRegistry> interactiveRunRegistry;

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
        return streamChat(windowId, sessionId, userMessage, shareMode, imageUrls, eventSink,
                StreamBillingObserver.NOOP);
    }

    /**
     * 流式聊天（带计费观察者）：只有「向客户端下发过至少一个有效内容增量」才结算扣费，
     * 否则在执行结束时立即退款（补偿作业兜底）。
     *
     * <p>异步任务通过提交闸门等待外层事务提交后才开始：事务未提交/回滚则不生成，
     * 从根本上消除「异步先结算、事务后提交/回滚」导致的误退窗口。</p>
     *
     * @param billingObserver 调用方附加的产出/无产出回调（可为 {@link StreamBillingObserver#NOOP}）
     */
    @Transactional
    public ChatStreamHandle streamChat(Long windowId, Long sessionId, String userMessage,
                                       boolean shareMode, List<String> imageUrls,
                                       ExecutionStreamSink eventSink, StreamBillingObserver billingObserver) {
        StreamBillingObserver observer = billingObserver == null ? StreamBillingObserver.NOOP : billingObserver;
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

        // 事件重放/断连隔离出口：为每个事件分配流内序号，Redis 可用时写共享缓冲供其它副本续传。
        // 客户端断开只停止推送、不取消生成（断连不等于失败）；只有总开关显式关闭才退回旧语义。
        StreamReplayService.ReplayStream replay = streamReplayService.openChatStream(session.getId(), eventSink);
        ChatEventPublisher events = new ChatEventPublisher(replay.sink());
        // 单次对话的取消句柄：仅显式停止（共享标记）或真实失败会置位，流式回调据此短路。
        // 传入会话级共享 key，使显式停止请求落到任意实例都能被本流感知（Redis 不可用时退回进程内）。
        ChatStreamCancellation cancellation = new ChatStreamCancellation(
                ChatCancellationStore.sessionKey(session.getId()), chatCancellationStore);
        ChatStreamHandle handle = new ChatStreamHandle(cancellation, session.getId(), replay.detachAware());

        // 保存用户消息（多模态：图片序列化进 attachments）
        com.wgh.rag.chat.entity.ChatMessage userMsg =
                messageStore.saveUserMessage(session.getId(), userMessage, images);

        // 提交闸门：异步任务必须等外层事务提交后才开始（普通入口与分享入口都走事务代理）
        final CompletableFuture<Void> commitGate = commitGate();
        // 产出确认：首个非空内容增量下发后结算一次，后续失败/停止/断连都不再退款
        final AtomicBoolean outputDelivered = new AtomicBoolean(false);
        final Runnable onOutput = () -> {
            if (outputDelivered.compareAndSet(false, true)) {
                quotaService.markChargeSettled(quotaCharge);
                notifyQuietly(observer::onOutputDelivered, "onOutputDelivered");
            }
        };
        // 收口回调：只在「本轮确实不可能再产出」时执行（同步路线由编排线程收尾，
        // 流式/工作流路线由各自终态收尾），避免首包到达前提前判「无产出」退款
        final Runnable onTurnComplete = () -> {
            try {
                settleBilling(quotaCharge, outputDelivered, observer);
            } catch (Exception e) {
                log.error("对话计费收尾异常（等待补偿作业兜底）: chargeId={}",
                        quotaCharge == null ? null : quotaCharge.chargeId(), e);
            }
        };

        // 子线程执行 RAG + LLM 并推送事件（MDC 由统一执行器透传，SSE 日志可按 requestId 串联）
        try {
            ragAsyncExecutor.execute(() -> {
            if (!awaitCommit(commitGate)) {
                log.error("聊天事务未提交或等待超时，放弃本次生成（不结算、不产生扣费）: sessionId={}, chargeId={}",
                        session.getId(), quotaCharge == null ? null : quotaCharge.chargeId());
                notifyQuietly(observer::onNoOutput, "onNoOutput");
                return;
            }
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
                    .outputHook(onOutput)
                    .completionHook(onTurnComplete)
                    .build();
            // 增量登记「进行中」运行态（失败不影响对话；结束在 finally 注销）
            InteractiveRunRegistry.RunRegistration registration =
                    registerRunning(session, window, userMsg, userMessage, quotaUsername);
            boolean failed = false;
            try {
                orchestrator.execute(ctx);
            } catch (Exception e) {
                failed = true;
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
                finishRunning(registration, failed);
                metrics.recordChatRequest(metricCtx.route().get(), shareMode);
                metrics.recordChatDuration(sample, metricCtx.route().get(), shareMode);
                // 清理当前线程的工具注册（ThreadLocal）
                toolExecutionEngine.clearWindowTools();
                // 无产出不计费：仅在「本轮已不可能再产出」时收口。
                // 走流式模型/工作流的路线已把收口交给各自终态，这里不重复判定
                ctx.completeTurnIfNotDeferred();
            }
            });
        } catch (TaskRejectedException e) {
            // 任务未提交成功，执行不会发生
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // 事务内：计数与流水随异常整体回滚，无需退款，也不打「已退款」指标
                log.warn("统一执行器拒绝聊天任务，扣费随事务回滚: username={}", quotaUsername);
            } else {
                QuotaService.RefundResult refund = quotaService.refundChat(quotaCharge);
                metrics.recordQuotaRefund(refund == null ? "unknown" : refund.name().toLowerCase(Locale.ROOT));
                if (refund == QuotaService.RefundResult.FAILED) {
                    log.error("统一执行器拒绝聊天任务，配额退款失败: username={}, chargeId={}",
                            quotaUsername, quotaCharge == null ? null : quotaCharge.chargeId());
                } else {
                    log.warn("统一执行器拒绝聊天任务，配额已处理: username={}, result={}",
                            quotaUsername, refund);
                }
            }
            // 保持既有失败语义：异常上抛，由事务回滚与全局异常处理兜底
            throw e;
        }

        return handle;
    }

    /**
     * 事务提交闸门：当前事务 afterCommit 时放行；回滚或超时则不放行。
     * <p>无事务（直接调用）时立即放行。</p>
     */
    private static CompletableFuture<Void> commitGate() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            gate.complete(null);
            return gate;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gate.complete(null);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    gate.completeExceptionally(new IllegalStateException("聊天外层事务未提交"));
                }
            }
        });
        return gate;
    }

    /** 等待外层事务提交；回滚/超时返回 {@code false}（本次不生成）。 */
    private static boolean awaitCommit(CompletableFuture<Void> gate) {
        try {
            gate.get(COMMIT_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计费收敛：已产出则结算（幂等兜底）；未产出则立即退款，失败交给补偿作业。
     */
    private void settleBilling(QuotaService.QuotaCharge charge, AtomicBoolean outputDelivered,
                               StreamBillingObserver observer) {
        if (outputDelivered.get()) {
            if (charge != null) {
                quotaService.markChargeSettled(charge);
            }
            return;
        }
        notifyQuietly(observer::onNoOutput, "onNoOutput");
        if (charge == null) {
            return;
        }
        QuotaService.RefundResult result = quotaService.refundChat(charge);
        metrics.recordQuotaRefund(result == null ? "unknown" : result.name().toLowerCase(Locale.ROOT));
        if (result == QuotaService.RefundResult.FAILED) {
            log.error("无产出退款失败，等待补偿作业兜底: username={}, chargeId={}",
                    charge.username(), charge.chargeId());
        } else {
            log.info("本次对话未下发有效内容，按无产出不计费处理: username={}, chargeId={}, result={}",
                    charge.username(), charge.chargeId(), result);
        }
    }

    private static void notifyQuietly(Runnable action, String name) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("计费观察者回调失败（不影响主流程）: callback={}, reason={}", name, e.getMessage());
        }
    }

    /**
     * 分享窗口流式聊天（无需登录，验证 shareToken）
     * sessionId 为空时复用该匿名用户最近一次会话，否则使用指定会话（需校验归属）
     *
     * <p>带 {@code @Transactional}：controller 经代理调用，随后的 {@code streamChat} 自调用共享同一事务，
     * 使「计数 + 流水 + 会话 + 用户消息」在分享入口同样原子（与普通入口一致）。</p>
     */
    @Transactional
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

    /**
     * 登记本次对话的「进行中」运行态。
     *
     * <p>调度中心未启用或登记失败时返回 {@code null}，不影响对话；注销由调用方在 finally 完成。</p>
     */
    private InteractiveRunRegistry.RunRegistration registerRunning(
            ChatSession session, ChatWindow window, com.wgh.rag.chat.entity.ChatMessage userMsg,
            String userMessage, String owner) {
        InteractiveRunRegistry registry = interactiveRunRegistry == null
                ? null : interactiveRunRegistry.getIfAvailable();
        if (registry == null) {
            return null;
        }
        try {
            Long messageId = userMsg == null ? null : userMsg.getId();
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("sessionId", session.getId());
            attributes.put("windowId", window.getId());
            if (messageId != null) {
                attributes.put("messageId", messageId);
            }
            String title = session.getTitle();
            if (title == null || title.isBlank()) {
                title = excerpt(userMessage);
            }
            if (title != null && !title.isBlank()) {
                attributes.put("title", title);
            }
            return registry.register(new InteractiveRunRegistry.InteractiveRunRequest(
                    InteractiveRunRegistry.RUN_TYPE_CHAT,
                    "chat:" + session.getId() + ":" + (messageId == null ? "na" : messageId),
                    null, owner, attributes));
        } catch (RuntimeException e) {
            log.warn("对话运行态登记失败（不影响对话）: session={}, error={}", session.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 注销运行态：异常=FAILED、其余（含用户停止）=SUCCEEDED。
     *
     * <p>登记行只服务「进行中」查询，注销即删除，不需要区分「完成」与「用户停止」；
     * 这里不回读取消标记，避免为收尾多一次 Redis 交互。</p>
     */
    private void finishRunning(InteractiveRunRegistry.RunRegistration registration, boolean failed) {
        if (registration == null) {
            return;
        }
        ExecutionStatus status = failed ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
        try {
            registration.finish(status, failed ? "CHAT_STREAM_FAILED" : null);
        } catch (RuntimeException e) {
            log.debug("对话运行态注销失败: error={}", e.getMessage());
        }
    }

    private static String excerpt(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 50 ? normalized : normalized.substring(0, 50) + "…";
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
