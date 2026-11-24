package com.wgh.rag.scheduler.event;

import com.wgh.rag.scheduler.config.SchedulerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 进程内事件总线注册表。
 *
 * <p>只保留本实例执行过的执行；终态后按 {@code event-retention} 延迟清理，
 * 让调用方在结束前后都能订阅。跨实例订阅需要事件表，不在 P3 范围。
 */
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionEventBusRegistry {

    private final Map<String, ExecutionEventBus> buses = new ConcurrentHashMap<>();

    private final SchedulerProperties properties;

    private final ObjectProvider<ExecutionEventStore> eventStoreProvider;

    /** 兼容仅需进程内总线的测试。 */
    public ExecutionEventBusRegistry(SchedulerProperties properties) {
        this(properties, null);
    }

    @Autowired
    public ExecutionEventBusRegistry(SchedulerProperties properties,
                                     ObjectProvider<ExecutionEventStore> eventStoreProvider) {
        this.properties = properties;
        this.eventStoreProvider = eventStoreProvider;
    }

    /** 创建（或复用）执行事件总线；同一执行被重试时替换已关闭的旧总线。 */
    public ExecutionEventBus create(String executionId) {
        return buses.compute(executionId, (id, existing) -> {
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            ExecutionEventBus created = new ExecutionEventBus(id);
            ExecutionEventStore store = eventStoreProvider == null ? null : eventStoreProvider.getIfAvailable();
            if (store != null) {
                // 订阅者方式落库：所有生命周期与执行体事件都自动进入事件表。
                // 从当前序号开始订阅：复用未关闭的旧总线时不把历史事件重复写回事件表。
                created.attach(created.currentSeq(), store.sink());
            }
            return created;
        });
    }

    public Optional<ExecutionEventBus> find(String executionId) {
        if (executionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(buses.get(executionId));
    }

    /** 终态后延迟关闭并移除，避免内存常驻。 */
    public void closeLater(String executionId) {
        ExecutionEventBus bus = buses.get(executionId);
        if (bus == null) {
            return;
        }
        bus.close();
        Duration retention = properties.getEventRetention();
        long delayMillis = retention == null ? 0L : Math.max(0L, retention.toMillis());
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                .execute(() -> buses.remove(executionId, bus));
    }
}
