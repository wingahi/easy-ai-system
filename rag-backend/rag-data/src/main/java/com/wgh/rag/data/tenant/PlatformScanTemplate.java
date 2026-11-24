package com.wgh.rag.data.tenant;

import com.wgh.rag.common.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 平台级全租户扫描骨架。
 *
 * <p>维护作业不再依赖「当前作业的 tenantId」：统一从 {@link TenantDirectory} 枚举租户，
 * 逐租户设置 {@link TenantContext} 后执行任务，单租户异常只告警并计入失败数，不中断整轮。
 *
 * <p>事务边界：本类<b>不</b>在租户外层开大事务；需要读事务的调用方用 {@link #read(String, Supplier)}
 * 开短只读事务（并带上 {@code statement_timeout}），写操作仍由各业务服务的
 * {@code @Transactional} 自行控制，避免长事务占连接。
 */
@Slf4j
@Component
public class PlatformScanTemplate {

    private final TenantDirectory tenants;

    private final TenantTransactionTemplate transactions;

    private final PlatformScanProperties properties;

    private final JdbcTemplate jdbc;

    public PlatformScanTemplate(TenantDirectory tenants, TenantTransactionTemplate transactions,
                                PlatformScanProperties properties, DataSource dataSource) {
        this.tenants = tenants;
        this.transactions = transactions;
        this.properties = properties;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 单租户任务的产出。 */
    public record TenantCounters(long scanned, long failed) {

        public static final TenantCounters EMPTY = new TenantCounters(0, 0);
    }

    /** 单租户任务；抛出的异常由模板隔离，不影响其他租户。 */
    @FunctionalInterface
    public interface TenantTask {
        TenantCounters run(String tenantId);
    }

    /**
     * 一轮扫描结果。
     *
     * @param tenantsTotal 本轮枚举到的租户数（已按单轮上限截断）
     * @param tenantsOk    成功租户数（含内部部分条目失败的租户）
     * @param tenantsFailed 整租户失败数
     * @param itemsScanned 各租户上报的处理条目数合计
     * @param itemsFailed  各租户上报的失败条目数合计
     * @param cancelled    是否因取消提前结束
     */
    public record Result(int tenantsTotal, int tenantsOk, int tenantsFailed,
                         long itemsScanned, long itemsFailed, boolean cancelled) {

        public static Result skipped() {
            return new Result(0, 0, 0, 0, 0, false);
        }
    }

    /**
     * 遍历租户执行任务。
     *
     * @param includeDisabled {@code true} 含停用租户（对账/存储清理），{@code false} 仅启用租户
     * @param cancelled       取消探测；每进入一个租户前检查
     * @param task            单租户任务
     */
    public Result scan(boolean includeDisabled, BooleanSupplier cancelled, TenantTask task) {
        if (!properties.isEnabled()) {
            log.debug("平台级租户扫描已关闭（wgh.platform-scan.enabled=false），跳过本轮");
            return Result.skipped();
        }
        List<String> codes = includeDisabled ? tenants.listAll() : tenants.listActive();
        int limit = Math.max(1, properties.getMaxTenantsPerRound());
        List<String> scoped = codes;
        if (codes.size() > limit) {
            scoped = new ArrayList<>(codes.subList(0, limit));
            log.warn("[ALERT] 平台级租户扫描被单轮上限截断: total={} limit={}", codes.size(), limit);
        }

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong scanned = new AtomicLong();
        AtomicLong itemsFailed = new AtomicLong();
        AtomicBoolean wasCancelled = new AtomicBoolean(false);

        int concurrency = Math.max(1, properties.getTenantConcurrency());
        if (concurrency == 1 || scoped.size() <= 1) {
            for (String tenantId : scoped) {
                if (isCancelled(cancelled)) {
                    wasCancelled.set(true);
                    break;
                }
                runOne(tenantId, cancelled, wasCancelled, task, ok, failed, scanned, itemsFailed);
            }
        } else {
            runConcurrently(scoped, Math.min(concurrency, scoped.size()), cancelled, wasCancelled,
                    task, ok, failed, scanned, itemsFailed);
        }

        Result result = new Result(scoped.size(), ok.get(), failed.get(),
                scanned.get(), itemsFailed.get(), wasCancelled.get());
        if (result.tenantsFailed() > 0) {
            log.warn("[ALERT] 平台级租户扫描部分租户失败: total={} ok={} failed={} items={}/{}",
                    result.tenantsTotal(), result.tenantsOk(), result.tenantsFailed(),
                    result.itemsScanned(), result.itemsFailed());
        }
        return result;
    }

    /**
     * 租户只读短事务，并下发 {@code statement_timeout}。
     *
     * <p>必须在最外层调用（REQUIRED 语义，已有事务时不会重设 GUC）。
     */
    public <T> T read(String tenantId, Supplier<T> action) {
        return transactions.execute(tenantId, true, () -> {
            applyStatementTimeout();
            return action.get();
        });
    }

    private void runOne(String tenantId, BooleanSupplier cancelled, AtomicBoolean wasCancelled,
                        TenantTask task, AtomicInteger ok, AtomicInteger failed,
                        AtomicLong scanned, AtomicLong itemsFailed) {
        TenantContext.Context current = TenantContext.get();
        TenantContext.Context scoped = new TenantContext.Context(tenantId,
                current == null ? null : current.userId(),
                current == null ? null : current.username());
        try {
            TenantContext.callWith(scoped, () -> {
                TenantCounters counters = task.run(tenantId);
                if (counters != null) {
                    scanned.addAndGet(counters.scanned());
                    itemsFailed.addAndGet(counters.failed());
                }
                ok.incrementAndGet();
                return null;
            });
        } catch (RuntimeException e) {
            if (isCancelled(cancelled)) {
                wasCancelled.set(true);
                log.debug("平台级扫描已取消，中止租户 {} 的处理", tenantId);
                return;
            }
            failed.incrementAndGet();
            log.warn("[ALERT] 平台级扫描单租户失败，已跳过并继续: tenant={}", tenantId, e);
        }
    }

    private void runConcurrently(List<String> scoped, int threads, BooleanSupplier cancelled,
                                 AtomicBoolean wasCancelled, TenantTask task, AtomicInteger ok,
                                 AtomicInteger failed, AtomicLong scanned, AtomicLong itemsFailed) {
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "platform-scan");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(scoped.size());
            for (String tenantId : scoped) {
                futures.add(pool.submit(() -> {
                    if (isCancelled(cancelled)) {
                        wasCancelled.set(true);
                        return;
                    }
                    runOne(tenantId, cancelled, wasCancelled, task, ok, failed, scanned, itemsFailed);
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    wasCancelled.set(true);
                    break;
                } catch (ExecutionException e) {
                    // runOne 已捕获全部业务异常，这里只可能是框架级错误
                    failed.incrementAndGet();
                    log.warn("[ALERT] 平台级扫描线程异常", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void applyStatementTimeout() {
        Duration timeout = properties.getStatementTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        long millis = Math.max(1L, timeout.toMillis());
        try {
            jdbc.execute("SET LOCAL statement_timeout = " + millis);
        } catch (RuntimeException e) {
            log.warn("平台级扫描设置 statement_timeout 失败，按数据库默认执行: {}", e.getMessage());
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }
}
