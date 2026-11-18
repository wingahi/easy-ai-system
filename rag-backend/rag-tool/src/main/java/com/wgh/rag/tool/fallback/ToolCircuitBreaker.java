package com.wgh.rag.tool.fallback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 工具维度内存熔断器。
 *
 * <p>状态机：CLOSED → 连续失败达到阈值 → OPEN（{@code openSeconds} 内直接短路）
 * → 到期进入半开（只放行一个探针）→ 探针成功回到 CLOSED，失败重新计时 OPEN。
 *
 * <p>仅在单实例内存中生效，不跨节点共享；重启即清空，符合兜底场景的轻量诉求。
 */
@Slf4j
@Component
public class ToolCircuitBreaker {

    private final ToolFallbackProperties properties;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    /** 时钟，默认系统时间；测试通过 {@link #forTest} 注入。 */
    private LongSupplier clock = System::currentTimeMillis;

    public ToolCircuitBreaker(ToolFallbackProperties properties) {
        this.properties = properties;
    }

    /**
     * 测试用：注入可控时钟。
     *
     * <p>刻意不在类上保留第二个构造器——Spring 只有在“唯一构造器”时才会自动装配参数，
     * 多一个私有构造器都会让它回退去找无参构造器并启动失败。
     */
    static ToolCircuitBreaker forTest(ToolFallbackProperties properties, LongSupplier clock) {
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(properties);
        breaker.clock = clock;
        return breaker;
    }

    /**
     * 尝试获取一次调用许可。
     *
     * @return false 表示熔断打开（或半开探针已被占用），本次调用应直接短路
     */
    public boolean tryAcquire(String toolKey) {
        State state = states.computeIfAbsent(toolKey, k -> new State());
        synchronized (state) {
            if (!state.open) {
                return true;
            }
            long now = clock.getAsLong();
            if (now < state.openUntil) {
                return false;
            }
            // 半开：只放行一个探针
            if (state.probeInFlight) {
                return false;
            }
            state.probeInFlight = true;
            log.info("工具熔断进入半开，放行探针: {}", toolKey);
            return true;
        }
    }

    /** 记录一次成功调用，重置熔断状态。 */
    public void onSuccess(String toolKey) {
        State state = states.computeIfAbsent(toolKey, k -> new State());
        synchronized (state) {
            state.failures = 0;
            state.open = false;
            state.openUntil = 0L;
            state.probeInFlight = false;
        }
    }

    /** 记录一次最终失败（重试耗尽后调用），达到阈值即打开熔断。 */
    public void onFailure(String toolKey) {
        State state = states.computeIfAbsent(toolKey, k -> new State());
        synchronized (state) {
            long now = clock.getAsLong();
            if (state.open) {
                // 半开探针失败：重新计时
                state.openUntil = now + properties.getOpenSeconds() * 1000L;
                state.probeInFlight = false;
                log.warn("工具熔断探针失败，重新打开: {}", toolKey);
                return;
            }
            state.failures++;
            if (state.failures >= properties.getFailureThreshold()) {
                state.open = true;
                state.openUntil = now + properties.getOpenSeconds() * 1000L;
                state.probeInFlight = false;
                log.warn("工具连续失败 {} 次，熔断 {}s: {}",
                        state.failures, properties.getOpenSeconds(), toolKey);
            }
        }
    }

    /** 当前是否处于打开（含半开等待）状态。 */
    public boolean isOpen(String toolKey) {
        State state = states.get(toolKey);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.open && clock.getAsLong() < state.openUntil;
        }
    }

    private static final class State {
        private int failures;
        private boolean open;
        private long openUntil;
        private boolean probeInFlight;
    }
}
