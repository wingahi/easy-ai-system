package com.wgh.rag.scheduler.facade;

import com.wgh.rag.common.log.MdcContext;
import com.wgh.rag.common.security.AuthContextPropagation;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.execution.api.ExecutionFacade;
import com.wgh.rag.execution.error.ExecutionCancelledException;
import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.execution.event.ExecutionEventTypes;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.model.ExecutionHandle;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.scheduler.admission.AdmissionController;
import com.wgh.rag.scheduler.admission.AdmissionDecision;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.event.ExecutionEventBus;
import com.wgh.rag.scheduler.event.ExecutionEventBusRegistry;
import com.wgh.rag.scheduler.event.ExecutionEventStore;
import com.wgh.rag.scheduler.execution.DefaultCancellationToken;
import com.wgh.rag.scheduler.execution.JobContextImpl;
import com.wgh.rag.scheduler.job.JobDispatcher;
import com.wgh.rag.scheduler.job.JobHandlerRegistry;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度层对外的统一执行门面。
 *
 * <p>车道语义：
 * <ul>
 *   <li>{@code INTERACTIVE}：进程内执行，受车道线程池与准入约束，不落作业表；</li>
 *   <li>{@code BACKGROUND}：落 {@code wgh_execution_job}，由调度循环领取，租约保证一作业一执行者；</li>
 *   <li>{@code MAINTENANCE}：落表 + Redis 单飞锁，跨实例同一作业类型只跑一份。</li>
 * </ul>
 *
 * <p>事件订阅：本实例执行中的作业走进程内总线；本实例没有总线时按事件表重放并轮询到终态，
 * 因此跨实例执行、断线重连（{@code Last-Event-ID}）与「提交后立刻订阅」都能拿到完整事件。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefaultExecutionFacade implements ExecutionFacade {

    /** 执行体抛出的非契约异常统一映射为该错误码。 */
    private static final String EXECUTION_FAILED = "EXECUTION_FAILED";

    /** 事件表轮询间隔：本实例没有执行中的总线时，用它补齐并跟踪跨实例事件。 */
    private static final long ATTACH_POLL_INTERVAL_MILLIS = 300L;

    /** 单次轮询读取的事件上限。 */
    private static final int ATTACH_POLL_BATCH = 200;

    private final SchedulerProperties properties;

    private final AdmissionController admissionController;

    private final LaneExecutors laneExecutors;

    private final JobStore jobStore;

    private final JobHandlerRegistry handlerRegistry;

    private final ExecutionEventBusRegistry eventBuses;

    private final JobDispatcher jobDispatcher;

    private final ObjectProvider<ExecutionEventStore> eventStoreProvider;

    private final Map<String, ExecutionTicket> interactiveTickets = new ConcurrentHashMap<>();

    /** 事件表轮询线程池（懒创建，仅在没有本地总线时使用）。 */
    private volatile ScheduledExecutorService attachPoller;

    /** 兼容仅需进程内总线的测试。 */
    public DefaultExecutionFacade(SchedulerProperties properties,
                                  AdmissionController admissionController,
                                  LaneExecutors laneExecutors,
                                  JobStore jobStore,
                                  JobHandlerRegistry handlerRegistry,
                                  ExecutionEventBusRegistry eventBuses,
                                  JobDispatcher jobDispatcher) {
        this(properties, admissionController, laneExecutors, jobStore, handlerRegistry, eventBuses,
                jobDispatcher, null);
    }

    @Autowired
    public DefaultExecutionFacade(SchedulerProperties properties,
                                  AdmissionController admissionController,
                                  LaneExecutors laneExecutors,
                                  JobStore jobStore,
                                  JobHandlerRegistry handlerRegistry,
                                  ExecutionEventBusRegistry eventBuses,
                                  JobDispatcher jobDispatcher,
                                  ObjectProvider<ExecutionEventStore> eventStoreProvider) {
        this.properties = properties;
        this.admissionController = admissionController;
        this.laneExecutors = laneExecutors;
        this.jobStore = jobStore;
        this.handlerRegistry = handlerRegistry;
        this.eventBuses = eventBuses;
        this.jobDispatcher = jobDispatcher;
        this.eventStoreProvider = eventStoreProvider;
    }

    @Override
    public ExecutionHandle submit(ExecutionRequest request) {
        // 提交时刻冻结租户/属主：调度线程不再回读 ThreadLocal
        ExecutionRequest effective = freezeOwnerRoles(enrich(request));
        JobHandler handler = handlerRegistry.find(effective.jobType())
                .orElseThrow(() -> new ExecutionException(ExecutionErrorCodes.JOB_TYPE_UNKNOWN,
                        "作业类型未注册处理器: " + effective.jobType()));
        AdmissionDecision decision = admissionController.decide(effective);
        if (!decision.allowed()) {
            throw new ExecutionException(decision.code(), decision.message());
        }
        if (effective.lane() == ExecutionLane.INTERACTIVE) {
            return submitInteractive(effective, handler);
        }
        ExecutionJob job = jobStore.submit(effective, properties.getMaxAttempts(), timeoutSeconds(effective));
        jobDispatcher.kick();
        return new ExecutionHandle(job.getExecutionId(), job.getJobType(), job.getLane(), job.getStatus(),
                job.getCreateTime() == null ? Instant.now()
                        : job.getCreateTime().atZone(ZoneId.systemDefault()).toInstant());
    }

    @Override
    public Optional<ExecutionSnapshot> query(String executionId) {
        ExecutionTicket ticket = interactiveTickets.get(executionId);
        if (ticket != null) {
            return Optional.of(ticket.snapshot());
        }
        return jobStore.query(executionId);
    }

    @Override
    public boolean cancel(String executionId, String reason) {
        ExecutionTicket ticket = interactiveTickets.get(executionId);
        if (ticket != null) {
            if (ticket.status().isTerminal()) {
                return false;
            }
            boolean cancelled = ticket.cancellation().cancel(reason);
            JobContext context = ticket.context();
            if (cancelled && context != null) {
                try {
                    ticket.handler().cancel(context);
                } catch (RuntimeException e) {
                    log.warn("交互式执行取消钩子失败 executionId={} error={}", executionId, e.getMessage());
                }
            }
            return cancelled;
        }
        return jobStore.cancel(executionId, reason);
    }

    @Override
    public AutoCloseable attach(String executionId, long fromSeq, ExecutionEventSink sink) {
        Optional<ExecutionEventBus> bus = eventBuses.find(executionId);
        if (bus.isPresent()) {
            return bus.get().attach(fromSeq, sink);
        }
        // 本实例无总线（作业尚未领取、在其它副本执行或已过保留期）：先补事件表历史，
        // 再持续轮询事件表直到终态，兼顾跨实例实时流与 Last-Event-ID 重连
        ExecutionEventStore store = eventStoreProvider == null ? null : eventStoreProvider.getIfAvailable();
        if (store == null) {
            log.debug("执行事件总线与事件表均不可用 executionId={}", executionId);
            return () -> {
            };
        }
        EventStoreSubscription subscription = new EventStoreSubscription(executionId, fromSeq, store, sink);
        if (subscription.replay()) {
            subscription.finish(true);
        } else {
            subscription.start();
        }
        return subscription;
    }

    private boolean isTerminalEvent(String type) {
        return ExecutionEventTypes.COMPLETED.equals(type)
                || ExecutionEventTypes.FAILED.equals(type)
                || ExecutionEventTypes.CANCELLED.equals(type);
    }

    private ScheduledExecutorService attachPoller() {
        ScheduledExecutorService poller = attachPoller;
        if (poller == null) {
            synchronized (this) {
                poller = attachPoller;
                if (poller == null) {
                    poller = Executors.newScheduledThreadPool(2, runnable -> {
                        Thread thread = new Thread(runnable, "sched-attach-poll");
                        thread.setDaemon(true);
                        return thread;
                    });
                    attachPoller = poller;
                }
            }
        }
        return poller;
    }

    @jakarta.annotation.PreDestroy
    public void shutdownAttachPoller() {
        ScheduledExecutorService poller = attachPoller;
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    /**
     * 基于事件表的订阅：先重放历史，再按固定间隔拉取新事件，终态后关闭出口。
     *
     * <p>用于本实例没有执行中总线的场景（其它副本执行、作业尚未被领取）。
     * 终止条件优先看终态事件；事件落库失败或进程被杀时，用作业状态兜底。
     */
    private final class EventStoreSubscription implements AutoCloseable {

        private final String executionId;

        private final ExecutionEventStore store;

        private final ExecutionEventSink sink;

        private final AtomicLong lastSeq;

        private final AtomicBoolean closed = new AtomicBoolean();

        private volatile ScheduledFuture<?> future;

        /** 连续观察到作业终态但事件表暂无终态事件的次数（给终态事件落库留出余量）。 */
        private int terminalStatusPolls;

        private EventStoreSubscription(String executionId, long fromSeq,
                                       ExecutionEventStore store, ExecutionEventSink sink) {
            this.executionId = executionId;
            this.store = store;
            this.sink = sink;
            this.lastSeq = new AtomicLong(Math.max(0L, fromSeq));
        }

        /**
         * 同步重放事件表中已有的历史事件。
         *
         * @return {@code true} 表示历史里已有终态事件，无需继续轮询
         */
        private boolean replay() {
            List<ExecutionEvent> events = store.after(executionId, lastSeq.get(), ATTACH_POLL_BATCH);
            boolean terminal = false;
            for (ExecutionEvent event : events) {
                if (closed.get()) {
                    return false;
                }
                lastSeq.accumulateAndGet(event.seq(), Math::max);
                sink.emit(event);
                terminal = terminal || isTerminalEvent(event.type());
            }
            log.debug("执行事件表重放完成 executionId={} replayedSeq={} count={}",
                    executionId, lastSeq.get(), events.size());
            return terminal;
        }

        private void start() {
            future = attachPoller().scheduleWithFixedDelay(this::pollSafely,
                    ATTACH_POLL_INTERVAL_MILLIS, ATTACH_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void pollSafely() {
            if (closed.get()) {
                finish(false);
                return;
            }
            try {
                pollOnce();
            } catch (RuntimeException e) {
                log.warn("执行事件轮询失败 executionId={} error={}", executionId, e.getMessage());
            }
        }

        private void pollOnce() {
            boolean terminalEvent = false;
            for (ExecutionEvent event : store.after(executionId, lastSeq.get(), ATTACH_POLL_BATCH)) {
                if (closed.get()) {
                    return;
                }
                lastSeq.accumulateAndGet(event.seq(), Math::max);
                sink.emit(event);
                terminalEvent = isTerminalEvent(event.type());
            }
            if (terminalEvent) {
                finish(true);
                return;
            }
            // 事件表可能因写入失败/进程被杀缺少终态事件：用作业状态兜底，避免订阅永不结束。
            // 作业状态先于终态事件落库，连续两轮确认后再收口，给终态事件留出落库时间。
            boolean terminal = jobStore.query(executionId)
                    .map(snapshot -> snapshot.status() != null && snapshot.status().isTerminal())
                    .orElse(false);
            if (!terminal) {
                terminalStatusPolls = 0;
            } else if (++terminalStatusPolls >= 2) {
                finish(true);
            }
        }

        /** 结束轮询；{@code notifySink} 为 {@code true} 时同时关闭事件出口。 */
        private void finish(boolean notifySink) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (notifySink) {
                try {
                    sink.close();
                } catch (RuntimeException e) {
                    log.debug("关闭执行事件出口失败 executionId={} error={}", executionId, e.getMessage());
                }
            }
        }

        @Override
        public void close() {
            finish(false);
        }
    }

    private ExecutionHandle submitInteractive(ExecutionRequest request, JobHandler handler) {
        String executionId = UUID.randomUUID().toString();
        ExecutionTicket ticket = new ExecutionTicket(executionId, request, handler);
        ExecutionEventBus bus = eventBuses.create(executionId);
        interactiveTickets.put(executionId, ticket);
        if (!laneExecutors.tryReserve(ExecutionLane.INTERACTIVE)) {
            cleanupInteractive(executionId);
            throw new ExecutionException(ExecutionErrorCodes.SCHEDULER_REJECTED,
                    "交互式车道在途已达上限", executionId);
        }
        try {
            // 交互式执行沿用提交线程的 MDC、登录态与租户上下文
            laneExecutors.executor(ExecutionLane.INTERACTIVE)
                    .execute(MdcContext.wrap(AuthContextPropagation.wrap(() -> {
                try {
                    runInteractive(ticket, bus);
                } finally {
                    laneExecutors.release(ExecutionLane.INTERACTIVE);
                }
            })));
        } catch (RejectedExecutionException e) {
            laneExecutors.release(ExecutionLane.INTERACTIVE);
            cleanupInteractive(executionId);
            throw new ExecutionException(ExecutionErrorCodes.SCHEDULER_REJECTED,
                    "交互式车道队列已满", executionId);
        }
        return new ExecutionHandle(executionId, request.jobType(), request.lane(),
                ExecutionStatus.RUNNING, ticket.submittedAt());
    }

    private void runInteractive(ExecutionTicket ticket, ExecutionEventBus bus) {
        DefaultCancellationToken cancellation = ticket.cancellation();
        ExecutionEventSink sink = event -> bus.publish(event.type(), event.payload());
        JobContext context = new JobContextImpl(ticket.executionId(), ticket.request(), sink, cancellation, null);
        ticket.bindContext(context);
        bus.publish(ExecutionEventTypes.STARTED, Map.of("status", ExecutionStatus.RUNNING.name()));
        ExecutionStatus terminal = ExecutionStatus.SUCCEEDED;
        String errorCode = null;
        try {
            ticket.handler().execute(context);
            if (cancellation.isCancelled()) {
                terminal = ExecutionStatus.CANCELLED;
                errorCode = ExecutionErrorCodes.CANCELLED;
            }
        } catch (ExecutionCancelledException e) {
            terminal = ExecutionStatus.CANCELLED;
            errorCode = ExecutionErrorCodes.CANCELLED;
        } catch (Exception e) {
            terminal = ExecutionStatus.FAILED;
            errorCode = e instanceof ExecutionException executionException
                    ? executionException.code() : EXECUTION_FAILED;
            log.warn("交互式执行失败 executionId={} error={}", ticket.executionId(), e.getMessage(), e);
        } finally {
            ticket.finish(terminal, errorCode);
            bus.publish(switch (terminal) {
                case SUCCEEDED -> ExecutionEventTypes.COMPLETED;
                case CANCELLED -> ExecutionEventTypes.CANCELLED;
                default -> ExecutionEventTypes.FAILED;
            }, errorCode == null
                    ? Map.of("status", terminal.name())
                    : Map.of("status", terminal.name(), "errorCode", errorCode));
            eventBuses.closeLater(ticket.executionId());
            scheduleTicketCleanup(ticket.executionId());
        }
    }

    private void cleanupInteractive(String executionId) {
        interactiveTickets.remove(executionId);
        eventBuses.closeLater(executionId);
    }

    /** 终态登记保留一段时间供查询，随后移除，避免内存常驻。 */
    private void scheduleTicketCleanup(String executionId) {
        Duration retention = properties.getEventRetention();
        long delayMillis = retention == null ? 0L : Math.max(0L, retention.toMillis());
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                .execute(() -> interactiveTickets.remove(executionId));
    }

    private Integer timeoutSeconds(ExecutionRequest request) {
        Duration timeout = request.timeout();
        if (timeout == null) {
            return null;
        }
        return (int) Math.max(1L, timeout.toSeconds());
    }

    /**
     * 用当前请求上下文补齐租户与属主：显式指定的非默认租户优先（系统任务），
     * 其余从 TenantContext 取，避免后台作业创建的资源归属 system/默认租户。
     */
    private ExecutionRequest enrich(ExecutionRequest request) {
        String contextTenant = TenantContext.currentTenantId();
        String contextUsername = TenantContext.currentUsername();
        boolean tenantMissing = request.tenantId() == null
                || ExecutionRequest.DEFAULT_TENANT.equals(request.tenantId());
        boolean useContextTenant = tenantMissing && contextTenant != null
                && !TenantContext.DEFAULT_TENANT.equals(contextTenant);
        if (!useContextTenant && request.ownerUserId() != null) {
            return request;
        }
        return ExecutionRequest.builder(request.jobType(), request.lane())
                .tenant(useContextTenant ? contextTenant : request.tenantId())
                .owner(request.ownerUserId() == null ? contextUsername : request.ownerUserId())
                .bizKey(request.bizKey())
                .payload(request.payload())
                .priority(request.priority())
                .scheduledAt(request.scheduledAt())
                .timeout(request.timeout())
                .build();
    }

    /**
     * 把提交线程的属主角色冻结进负载，供后台作业线程恢复登录态。
     *
     * <p>交互式执行沿用提交线程的 {@code LoginUser}，无需冻结；系统任务（无登录态）也不注入，
     * 执行方保持“无登录上下文”，按内部调用处理。
     */
    private ExecutionRequest freezeOwnerRoles(ExecutionRequest request) {
        if (request.lane() == ExecutionLane.INTERACTIVE) {
            return request;
        }
        LoginUser loginUser = LoginUser.get();
        if (loginUser == null) {
            return request;
        }
        Set<String> roles = loginUser.getRoles() == null ? Set.of() : loginUser.getRoles();
        Map<String, Object> data = new LinkedHashMap<>(request.payload().data());
        data.put(JobPayload.KEY_OWNER_ROLES, new ArrayList<>(roles));
        return ExecutionRequest.builder(request.jobType(), request.lane())
                .tenant(request.tenantId())
                .owner(request.ownerUserId())
                .bizKey(request.bizKey())
                .payload(JobPayload.of(data))
                .priority(request.priority())
                .scheduledAt(request.scheduledAt())
                .timeout(request.timeout())
                .build();
    }
}
