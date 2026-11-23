package com.wgh.rag.scheduler.job;

import com.wgh.rag.common.log.MdcContext;
import com.wgh.rag.execution.error.ExecutionCancelledException;
import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.error.LeaseLostException;
import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.execution.event.ExecutionEventTypes;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.event.ExecutionEventBus;
import com.wgh.rag.scheduler.event.ExecutionEventBusRegistry;
import com.wgh.rag.scheduler.execution.DefaultCancellationToken;
import com.wgh.rag.scheduler.execution.JobContextImpl;
import com.wgh.rag.scheduler.singleflight.SingleFlightGuard;
import com.wgh.rag.scheduler.singleflight.SingleFlightUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台作业执行器。
 *
 * <p>一次执行的生命周期：
 * <ol>
 *   <li>校验处理器与 payload 版本（不可用/不兼容直接死信）；</li>
 *   <li>维护车道先抢 Redis 单飞锁（拿不到或 Redis 不可用则放弃本次领取，失败关闭）；</li>
 *   <li>LEASED → RUNNING，启动看门狗：按心跳续租、轮询取消标记、执行超时；</li>
 *   <li>调用 {@code JobHandler#execute}，按返回值/异常落终态；</li>
 *   <li>所有终态写入都带 {@code lease_owner} 条件，租约丢失时丢弃结果，从根上避免双写。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobRunner {

    private final SchedulerProperties properties;

    private final JobStore jobStore;

    private final JobHandlerRegistry handlerRegistry;

    private final ExecutionEventBusRegistry eventBuses;

    private final SingleFlightGuard singleFlightGuard;

    public JobRunner(SchedulerProperties properties,
                     JobStore jobStore,
                     JobHandlerRegistry handlerRegistry,
                     ExecutionEventBusRegistry eventBuses,
                     SingleFlightGuard singleFlightGuard) {
        this.properties = properties;
        this.jobStore = jobStore;
        this.handlerRegistry = handlerRegistry;
        this.eventBuses = eventBuses;
        this.singleFlightGuard = singleFlightGuard;
    }

    /** 在当前车道线程内执行一条已领取作业。 */
    public void run(ExecutionJob job) {
        String owner = job.getLeaseOwner();
        String executionId = job.getExecutionId();
        ExecutionEventBus bus = eventBuses.create(executionId);
        DefaultCancellationToken cancellation = new DefaultCancellationToken();
        AtomicBoolean leaseLost = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicBoolean cancelHookInvoked = new AtomicBoolean();
        AtomicReference<SingleFlightGuard.Handle> singleFlightRef = new AtomicReference<>();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sched-watchdog-" + executionId);
            thread.setDaemon(true);
            return thread;
        });

        JobHandler handler = handlerRegistry.find(job.getJobType()).orElse(null);
        try {
            if (handler == null) {
                log.error("作业类型未注册处理器，置死信 jobType={} executionId={}", job.getJobType(), executionId);
                deadLetter(job, owner, ExecutionErrorCodes.JOB_TYPE_UNKNOWN, "作业类型未注册处理器");
                return;
            }
            JobPayload payload;
            try {
                payload = jobStore.payloadOf(job);
            } catch (IllegalArgumentException e) {
                log.error("作业负载无法解析，置死信 executionId={}", executionId, e);
                deadLetter(job, owner, ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD, "作业负载无法解析");
                return;
            }
            if (payload.schemaVersion() != JobPayload.CURRENT_SCHEMA_VERSION) {
                deadLetter(job, owner, ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                        "负载版本不兼容: " + payload.schemaVersion());
                return;
            }
            if (job.getLane() == ExecutionLane.MAINTENANCE) {
                try {
                    singleFlightRef.set(singleFlightGuard.tryAcquire("job:" + job.getJobType(),
                            properties.getSingleFlight().getLockTtl()));
                } catch (SingleFlightUnavailableException e) {
                    log.error("单飞锁不可用，作业回退排队（失败关闭，不降级为进程内锁） executionId={} error={}",
                            executionId, e.getMessage());
                    releaseForLater(job);
                    return;
                }
                if (singleFlightRef.get() == null) {
                    log.debug("同类维护作业已在其它实例执行，稍后重试 jobType={} executionId={}",
                            job.getJobType(), executionId);
                    releaseForLater(job);
                    return;
                }
            }
            if (!jobStore.markRunning(job.getId(), owner)) {
                log.warn("作业租约已被接管，跳过执行 executionId={} owner={}", executionId, owner);
                return;
            }
            if (jobStore.isCancelRequested(job.getId())) {
                cancellation.cancel("收到取消请求");
                jobStore.cancelOwned(job.getId(), owner, cancellation.reason());
                bus.publish(ExecutionEventTypes.CANCELLED, eventPayload("CANCELLED", null));
                return;
            }

            ExecutionRequest request = toRequest(job, payload);
            JobContext context = new JobContextImpl(executionId, request, sequencedSink(bus), cancellation,
                    () -> renewOrFail(job, owner, singleFlightRef.get(), leaseLost, cancellation),
                    jobStore.resultOf(job),
                    result -> {
                        if (!jobStore.saveResult(job.getId(), owner, result)) {
                            leaseLost.set(true);
                            throw new LeaseLostException("持久化执行进度时租约已丢失", job.getExecutionId());
                        }
                    });
            bus.publish(ExecutionEventTypes.STARTED, eventPayload("STARTED", null));

            long heartbeatMillis = Math.max(500L, properties.getHeartbeatInterval().toMillis());
            // 看门狗线程继承作业的 MDC（requestId / traceId），续约与取消日志仍可关联链路
            watchdog.scheduleWithFixedDelay(MdcContext.wrap(
                    () -> watch(job, owner, singleFlightRef.get(), context, handler, cancellation,
                            leaseLost, cancelHookInvoked)),
                    heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
            Duration timeout = request.timeout() == null ? properties.getDefaultTimeout() : request.timeout();
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                watchdog.schedule(MdcContext.wrap(() -> {
                    if (!cancellation.isCancelled()) {
                        timedOut.set(true);
                        cancellation.cancel("执行超时（" + timeout.toSeconds() + "s）");
                    }
                }), timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            handler.execute(context);

            if (cancellation.isCancelled()) {
                finishCancelled(job, owner, bus, cancellation, timedOut, leaseLost);
            } else {
                boolean written = jobStore.complete(job.getId(), owner, context.savedResult());
                if (!written) {
                    log.warn("作业完成但租约已丢失，结果丢弃 executionId={} owner={}", executionId, owner);
                }
                bus.publish(ExecutionEventTypes.COMPLETED, eventPayload("SUCCEEDED", null));
            }
        } catch (ExecutionCancelledException e) {
            finishCancelled(job, owner, bus, cancellation, timedOut, leaseLost);
        } catch (LeaseLostException e) {
            log.warn("执行期间租约丢失，停止写入 executionId={} reason={}", executionId, e.getMessage());
        } catch (Exception e) {
            if (leaseLost.get()) {
                log.warn("租约已丢失，不落终态 executionId={}", executionId);
            } else {
                handleFailure(job, owner, bus, e);
            }
        } finally {
            watchdog.shutdownNow();
            SingleFlightGuard.Handle lock = singleFlightRef.get();
            if (lock != null) {
                lock.close();
            }
            eventBuses.closeLater(executionId);
        }
    }

    /** 看门狗：续租、单飞续约、取消轮询。 */
    private void watch(ExecutionJob job, String owner, SingleFlightGuard.Handle singleFlight,
                       JobContext context, JobHandler handler, DefaultCancellationToken cancellation,
                       AtomicBoolean leaseLost, AtomicBoolean cancelHookInvoked) {
        try {
            boolean renewed = jobStore.renewLease(job.getId(), owner, properties.getLeaseTtl());
            if (!renewed) {
                leaseLost.set(true);
                cancellation.cancel("租约已丢失（可能被其它实例接管）");
                return;
            }
            if (singleFlight != null && !singleFlight.renew()) {
                leaseLost.set(true);
                cancellation.cancel("单飞锁已丢失");
                return;
            }
            if (!cancellation.isCancelled() && jobStore.isCancelRequested(job.getId())) {
                if (cancellation.cancel("收到取消请求") && cancelHookInvoked.compareAndSet(false, true)) {
                    handler.cancel(context);
                }
            }
        } catch (Exception e) {
            log.warn("租约续约/取消轮询失败，下个周期重试 executionId={} error={}",
                    job.getExecutionId(), e.getMessage());
        }
    }

    /** 执行体主动心跳：续约失败抛 {@link LeaseLostException}，要求执行体停止写入。 */
    private void renewOrFail(ExecutionJob job, String owner, SingleFlightGuard.Handle singleFlight,
                             AtomicBoolean leaseLost, DefaultCancellationToken cancellation) {
        if (leaseLost.get()) {
            throw new LeaseLostException(job.getExecutionId(), "租约已丢失");
        }
        boolean renewed = jobStore.renewLease(job.getId(), owner, properties.getLeaseTtl());
        if (!renewed) {
            leaseLost.set(true);
            cancellation.cancel("租约已丢失");
            throw new LeaseLostException(job.getExecutionId(), "租约已丢失，停止写入");
        }
        if (singleFlight != null && !singleFlight.renew()) {
            leaseLost.set(true);
            cancellation.cancel("单飞锁已丢失");
            throw new LeaseLostException(job.getExecutionId(), "单飞锁已丢失，停止写入");
        }
    }

    private void finishCancelled(ExecutionJob job, String owner, ExecutionEventBus bus,
                                 DefaultCancellationToken cancellation, AtomicBoolean timedOut,
                                 AtomicBoolean leaseLost) {
        if (leaseLost.get()) {
            log.warn("取消/超时终态未写入：租约已丢失 executionId={}", job.getExecutionId());
            return;
        }
        if (timedOut.get()) {
            jobStore.fail(job.getId(), owner, ExecutionStatus.DEAD, ExecutionErrorCodes.EXECUTION_TIMEOUT,
                    cancellation.reason(), null);
            bus.publish(ExecutionEventTypes.FAILED, eventPayload(ExecutionStatus.DEAD.name(),
                    ExecutionErrorCodes.EXECUTION_TIMEOUT));
            return;
        }
        String reason = cancellation.reason();
        jobStore.cancelOwned(job.getId(), owner, reason);
        bus.publish(ExecutionEventTypes.CANCELLED, eventPayload(ExecutionStatus.CANCELLED.name(),
                ExecutionErrorCodes.CANCELLED));
    }

    private void handleFailure(ExecutionJob job, String owner, ExecutionEventBus bus, Exception error) {
        String errorCode = error instanceof ExecutionException executionException
                ? executionException.code() : null;
        int attempt = job.getAttempt() == null ? 1 : job.getAttempt();
        int maxAttempts = job.getMaxAttempts() == null ? properties.getMaxAttempts() : job.getMaxAttempts();
        JobFailurePolicy.Outcome outcome = JobFailurePolicy.decide(attempt, maxAttempts, errorCode);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (outcome == JobFailurePolicy.Outcome.DEAD) {
            jobStore.fail(job.getId(), owner, ExecutionStatus.DEAD, errorCode, message, null);
            bus.publish(ExecutionEventTypes.FAILED, eventPayload(ExecutionStatus.DEAD.name(), errorCode));
            log.error("作业执行失败并进入死信 executionId={} attempt={}/{} error={}",
                    job.getExecutionId(), attempt, maxAttempts, message);
            return;
        }
        Instant retryAt = Instant.now().plus(JobFailurePolicy.backoff(attempt, properties.getRetryBackoff()));
        jobStore.fail(job.getId(), owner, ExecutionStatus.FAILED, errorCode, message, retryAt);
        bus.publish(ExecutionEventTypes.FAILED, eventPayload(ExecutionStatus.FAILED.name(), errorCode));
        log.warn("作业执行失败，等待重试 executionId={} attempt={}/{} retryAt={} error={}",
                job.getExecutionId(), attempt, maxAttempts, retryAt, message);
    }

    private void deadLetter(ExecutionJob job, String owner, String errorCode, String message) {
        jobStore.fail(job.getId(), owner, ExecutionStatus.DEAD, errorCode, message, null);
        ExecutionEventBus bus = eventBuses.create(job.getExecutionId());
        bus.publish(ExecutionEventTypes.FAILED, eventPayload(ExecutionStatus.DEAD.name(), errorCode));
        eventBuses.closeLater(job.getExecutionId());
    }

    private void releaseForLater(ExecutionJob job) {
        jobStore.releaseClaim(job.getId(), job.getLeaseOwner(), properties.getSingleFlight().getRetryDelay());
    }

    /** 执行体发出的事件不持有 seq；由总线统一分配，保证执行内单调递增。 */
    private ExecutionEventSink sequencedSink(ExecutionEventBus bus) {
        return event -> bus.publish(event.type(), event.payload());
    }

    private ExecutionRequest toRequest(ExecutionJob job, JobPayload payload) {
        return ExecutionRequest.builder(job.getJobType(), job.getLane())
                .tenant(job.getTenantId())
                .owner(job.getOwnerUserId())
                .bizKey(job.getBizKey())
                .payload(payload)
                .priority(job.getPriority() == null ? 0 : job.getPriority())
                .timeout(job.getTimeoutSeconds() == null ? null : Duration.ofSeconds(job.getTimeoutSeconds()))
                .build();
    }

    private static Map<String, Object> eventPayload(String status, String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        return payload;
    }
}
