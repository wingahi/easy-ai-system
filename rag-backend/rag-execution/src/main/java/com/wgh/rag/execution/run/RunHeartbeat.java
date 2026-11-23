package com.wgh.rag.execution.run;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 后台续跑的心跳线程：长时间停留在单个节点/步骤时持续续租。
 *
 * <p>心跳失败（租约丢失）只记录，不中断执行线程；执行域在下一个生命周期检查点
 * 通过 {@link RunLifecycle#throwIfCancelled()} 退出，避免跨线程强杀。
 */
public final class RunHeartbeat implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    private RunHeartbeat(RunLifecycle lifecycle, Duration interval) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "run-resume-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long millis = Math.max(1000L, interval.toMillis());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                lifecycle.heartbeat();
            } catch (RuntimeException e) {
                failure.compareAndSet(null, e);
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    public static RunHeartbeat start(RunLifecycle lifecycle, Duration interval) {
        return new RunHeartbeat(lifecycle == null ? RunLifecycle.NOOP : lifecycle,
                interval == null ? Duration.ofSeconds(30) : interval);
    }

    /** 心跳线程已发现租约丢失时抛出。 */
    public void throwIfFailed() {
        RuntimeException e = failure.get();
        if (e != null) {
            throw e;
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
