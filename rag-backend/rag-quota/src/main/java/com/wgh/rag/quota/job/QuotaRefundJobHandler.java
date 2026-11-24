package com.wgh.rag.quota.job;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.event.ExecutionEventTypes;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.quota.entity.QuotaChargeEntity;
import com.wgh.rag.quota.properties.QuotaProperties;
import com.wgh.rag.quota.service.QuotaChargeLedger;
import com.wgh.rag.quota.service.QuotaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 配额退款补偿作业（{@code QUOTA_REFUND}，维护车道）。
 *
 * <p>扫描「已扣未决（CHARGED 超过保护期）」与「退款失败（REFUND_FAILED 且已到退避时间）」的流水，
 * 逐条重试退款；失败按指数退避写回 {@code next_retry_at}，超过最大次数置 {@code DEAD} 等人工处理。</p>
 *
 * <p>执行保障完全复用调度中心：作业落 {@code wgh_execution_job}，租约与心跳由调度层维护；
 * 维护车道自动加 Redis 单飞锁，跨实例同一时刻只跑一份。本类不持有任何待退款状态，
 * Redis 只做锁，不存业务数据。</p>
 *
 * <p>本地兜底：当 {@code wgh.scheduler.enabled=false}（调度中心不提交周期作业）时，
 * 由本实例的守护线程按同一间隔执行；调度中心开启时本地兜底不启动，二者不会双跑。
 * 退款迁移本身是带状态条件的 UPDATE，即使多实例同时扫描也不会重复退款。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaRefundJobHandler implements JobHandler, RecurringJob {

    /** 作业类型。 */
    public static final String JOB_TYPE = "QUOTA_REFUND";

    private static final int HEARTBEAT_EVERY = 20;

    private final QuotaChargeLedger chargeLedger;

    private final QuotaProperties properties;

    private final RagMetrics metrics;

    /** 调度中心是否启用；关闭时改由本地定时兜底。 */
    @Value("${wgh.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    private ScheduledExecutorService localFallback;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Duration interval() {
        return properties.getCharge().getRefundScanInterval();
    }

    @PostConstruct
    void startLocalFallback() {
        QuotaProperties.Charge config = properties.getCharge();
        if (schedulerEnabled || !config.isRefundEnabled()) {
            return;
        }
        long intervalMillis = Math.max(1000L, config.getRefundScanInterval().toMillis());
        localFallback = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quota-refund-local-fallback");
            thread.setDaemon(true);
            return thread;
        });
        localFallback.scheduleWithFixedDelay(this::runLocalFallback, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        log.warn("调度中心未启用，配额退款补偿由本地定时兜底: interval={}ms", intervalMillis);
    }

    @PreDestroy
    void stopLocalFallback() {
        if (localFallback != null) {
            localFallback.shutdownNow();
        }
    }

    private void runLocalFallback() {
        try {
            processDue(null);
        } catch (Exception e) {
            log.error("[ALERT] 本地配额退款补偿执行失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void execute(JobContext context) {
        Map<String, Object> payload = processDue(context);
        if (payload == null) {
            return;
        }
        context.events().emit(new ExecutionEvent(context.executionId(), 1,
                ExecutionEventTypes.PROGRESS, payload, Instant.now()));
    }

    /**
     * 扫描并处理到期流水。
     *
     * @param context 调度上下文；本地兜底传入 {@code null} 表示无取消/心跳通道
     * @return 统计载荷；无到期流水时返回 {@code null}
     */
    private Map<String, Object> processDue(JobContext context) {
        QuotaProperties.Charge config = properties.getCharge();
        if (!config.isRefundEnabled()) {
            log.debug("配额退款补偿已关闭（wgh.rag.quota.charge.refund-enabled=false）");
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        Duration protection = config.getRefundProtection() == null
                ? Duration.ofMinutes(5) : config.getRefundProtection();
        List<QuotaChargeEntity> due = chargeLedger.findDueRefunds(
                now.minus(protection), now, config.getRefundBatchSize());
        if (due.isEmpty()) {
            log.debug("配额退款补偿：无到期流水");
            return null;
        }

        int refunded = 0;
        int retrying = 0;
        int dead = 0;
        int skipped = 0;
        for (int i = 0; i < due.size(); i++) {
            if (context != null) {
                context.cancellation().throwIfCancellationRequested();
                if (i > 0 && i % HEARTBEAT_EVERY == 0) {
                    context.heartbeat();
                }
            }
            QuotaChargeEntity row = due.get(i);
            QuotaService.QuotaCharge charge = new QuotaService.QuotaCharge(
                    row.getUsername(), row.getPeriodKey(), row.getChargeId(), row.getUsageEpoch());
            try {
                QuotaService.RefundResult result = chargeLedger.refund(charge);
                switch (result) {
                    case REFUNDED -> {
                        refunded++;
                        metrics.recordQuotaRefund("refunded");
                    }
                    case DUPLICATE -> {
                        skipped++;
                        metrics.recordQuotaRefund("duplicate");
                    }
                    default -> {
                        skipped++;
                        metrics.recordQuotaRefund("nothing_to_refund");
                    }
                }
            } catch (Exception e) {
                int nextAttempt = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
                if (nextAttempt >= config.getRefundMaxAttempts()) {
                    if (markDead(row, e)) {
                        dead++;
                        metrics.recordQuotaRefund("dead");
                    } else {
                        skipped++;
                    }
                } else {
                    scheduleRetry(row, nextAttempt, config, e);
                    retrying++;
                    metrics.recordQuotaRefund("failed");
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "quota.refund.compensated");
        payload.put("scanned", due.size());
        payload.put("refunded", refunded);
        payload.put("retrying", retrying);
        payload.put("dead", dead);
        payload.put("skipped", skipped);
        log.info("配额退款补偿完成: {}", payload);
        return payload;
    }

    private void scheduleRetry(QuotaChargeEntity row, int nextAttempt,
                               QuotaProperties.Charge config, Exception error) {
        Duration delay = backoff(nextAttempt, config);
        try {
            chargeLedger.markRefundFailed(row.getChargeId(), LocalDateTime.now().plus(delay), messageOf(error));
            log.warn("配额退款失败，等待退避重试: chargeId={}, attempt={}/{}, retryAt={}, reason={}",
                    row.getChargeId(), nextAttempt, config.getRefundMaxAttempts(),
                    LocalDateTime.now().plus(delay), messageOf(error));
        } catch (Exception markError) {
            // 流水仍是 CHARGED/REFUND_FAILED，下轮「已扣未决」仍会扫到，不丢补偿
            log.error("登记退款失败状态失败: chargeId={}, reason={}",
                    row.getChargeId(), markError.getMessage());
        }
    }

    /** @return {@code true} 表示本次确实迁移到了 DEAD（用于告警打点，避免重复计数）。 */
    private boolean markDead(QuotaChargeEntity row, Exception error) {
        try {
            boolean marked = chargeLedger.markDead(row.getChargeId(), messageOf(error));
            if (marked) {
                log.error("[ALERT] 配额退款补偿次数耗尽，流水进入 DEAD，需人工退款或核销: "
                                + "chargeId={}, username={}, periodKey={}, reason={}",
                        row.getChargeId(), row.getUsername(), row.getPeriodKey(), messageOf(error));
            }
            return marked;
        } catch (Exception markError) {
            log.error("置 DEAD 失败: chargeId={}, reason={}", row.getChargeId(), markError.getMessage());
            return false;
        }
    }

    /** 指数退避：base * 2^(attempt-1)，封顶 refund-backoff-max。 */
    private Duration backoff(int attempt, QuotaProperties.Charge config) {
        Duration base = config.getRefundBackoff() == null ? Duration.ofSeconds(60) : config.getRefundBackoff();
        Duration cap = config.getRefundBackoffMax() == null ? Duration.ofMinutes(30) : config.getRefundBackoffMax();
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long millis;
        try {
            millis = Math.multiplyExact(base.toMillis(), 1L << exponent);
        } catch (ArithmeticException e) {
            millis = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(millis, cap.toMillis()));
    }

    private static String messageOf(Exception error) {
        if (error == null) {
            return null;
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
