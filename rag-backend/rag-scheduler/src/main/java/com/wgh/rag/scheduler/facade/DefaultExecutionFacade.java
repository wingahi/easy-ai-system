package com.wgh.rag.scheduler.facade;

import com.wgh.rag.common.log.MdcContext;
import com.wgh.rag.common.security.AuthContextPropagation;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
 * <p>事件订阅只覆盖本实例执行中的作业；跨实例重放依赖事件表（S3），不在 P3 范围。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefaultExecutionFacade implements ExecutionFacade {

    /** 执行体抛出的非契约异常统一映射为该错误码。 */
    private static final String EXECUTION_FAILED = "EXECUTION_FAILED";

    private final SchedulerProperties properties;

    private final AdmissionController admissionController;

    private final LaneExecutors laneExecutors;

    private final JobStore jobStore;

    private final JobHandlerRegistry handlerRegistry;

    private final ExecutionEventBusRegistry eventBuses;

    private final JobDispatcher jobDispatcher;

    private final ObjectProvider<ExecutionEventStore> eventStoreProvider;

    private final Map<String, ExecutionTicket> interactiveTickets = new ConcurrentHashMap<>();

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
        ExecutionRequest effective = enrich(request);
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
        // 本实例无总线（作业在其它副本执行或已过保留期）：从事件表补齐历史，支持 Last-Event-ID 重连
        ExecutionEventStore store = eventStoreProvider == null ? null : eventStoreProvider.getIfAvailable();
        if (store != null) {
            int replayed = 0;
            for (ExecutionEvent event : store.after(executionId, fromSeq, 1000)) {
                sink.emit(event);
                replayed++;
            }
            log.debug("执行事件表重放完成 executionId={} fromSeq={} replayed={}",
                    executionId, fromSeq, replayed);
        } else {
            log.debug("执行事件总线与事件表均不可用 executionId={}", executionId);
        }
        return () -> {
        };
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
}
