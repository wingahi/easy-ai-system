package com.wgh.rag.scheduler.lane;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 三车道线程池与在途计数。
 *
 * <p>每个车道独立的有界队列 + 最大并发：后台批量作业不会占用交互式车道的线程。
 * 拒绝策略固定为 {@link ThreadPoolExecutor.AbortPolicy}——满了就显式拒绝，不静默丢弃、
 * 也不回退到提交线程。
 *
 * <p>{@code tryReserve}/{@code release} 是领取作业前的准入占位：在途数达到车道最大并发时
 * 不再领取新作业，避免线程池队列无界堆积。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LaneExecutors {

    private final SchedulerProperties properties;

    private final Map<ExecutionLane, ThreadPoolExecutor> executors = new EnumMap<>(ExecutionLane.class);

    private final Map<ExecutionLane, AtomicInteger> inFlight = new EnumMap<>(ExecutionLane.class);

    public LaneExecutors(SchedulerProperties properties) {
        this.properties = properties;
        build(ExecutionLane.INTERACTIVE, properties.getInteractive());
        build(ExecutionLane.BACKGROUND, properties.getBackground());
        build(ExecutionLane.MAINTENANCE, properties.getMaintenance());
    }

    /** 车道线程池。 */
    public ThreadPoolExecutor executor(ExecutionLane lane) {
        return executors.get(lane);
    }

    /** 车道最大并发。 */
    public int maxConcurrent(ExecutionLane lane) {
        return executors.get(lane).getMaximumPoolSize();
    }

    /** 车道队列容量（交互式=池队列；后台/维护=作业表 PENDING 上限）。 */
    public int queueCapacity(ExecutionLane lane) {
        return laneProperties(lane).getQueueCapacity();
    }

    /** 当前在途数。 */
    public int inFlight(ExecutionLane lane) {
        return inFlight.get(lane).get();
    }

    /** 线程池队列长度。 */
    public int queued(ExecutionLane lane) {
        return executors.get(lane).getQueue().size();
    }

    /**
     * 占一个在途名额。
     *
     * @return {@code false} 表示已达车道最大并发，调用方不应再领取/提交作业
     */
    public boolean tryReserve(ExecutionLane lane) {
        AtomicInteger counter = inFlight.get(lane);
        int limit = maxConcurrent(lane);
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 归还在途名额；重复归还不产生负数。 */
    public void release(ExecutionLane lane) {
        inFlight.get(lane).updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /** 三车道快照。 */
    public List<LaneRuntimeSnapshot> snapshots() {
        List<LaneRuntimeSnapshot> snapshots = new ArrayList<>(3);
        for (ExecutionLane lane : ExecutionLane.values()) {
            ThreadPoolExecutor executor = executors.get(lane);
            snapshots.add(new LaneRuntimeSnapshot(lane, inFlight(lane), executor.getQueue().size(),
                    executor.getPoolSize(), executor.getMaximumPoolSize(), queueCapacity(lane)));
        }
        return snapshots;
    }

    @PreDestroy
    public void shutdown() {
        long graceSeconds = Math.max(1, properties.getLeaseTtl().toSeconds());
        for (Map.Entry<ExecutionLane, ThreadPoolExecutor> entry : executors.entrySet()) {
            ThreadPoolExecutor executor = entry.getValue();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(graceSeconds, TimeUnit.SECONDS)) {
                    log.warn("调度车道 {} 在 {}s 内未排空，强制关闭", entry.getKey(), graceSeconds);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private void build(ExecutionLane lane, SchedulerProperties.Lane laneProperties) {
        int core = Math.max(1, laneProperties.getCoreSize());
        int max = Math.max(core, laneProperties.getMaxSize());
        int queue = Math.max(1, laneProperties.getQueueCapacity());
        ThreadFactory factory = new LaneThreadFactory(laneProperties.getThreadNamePrefix());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(core, max, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        executors.put(lane, executor);
        inFlight.put(lane, new AtomicInteger());
    }

    private SchedulerProperties.Lane laneProperties(ExecutionLane lane) {
        return switch (lane) {
            case INTERACTIVE -> properties.getInteractive();
            case BACKGROUND -> properties.getBackground();
            case MAINTENANCE -> properties.getMaintenance();
        };
    }

    /** 车道线程命名：前缀 + 自增序号，便于线程转储定位。 */
    private static final class LaneThreadFactory implements ThreadFactory {

        private final String prefix;

        private final AtomicInteger sequence = new AtomicInteger();

        private LaneThreadFactory(String prefix) {
            this.prefix = prefix == null || prefix.isBlank() ? "sched-" : prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
