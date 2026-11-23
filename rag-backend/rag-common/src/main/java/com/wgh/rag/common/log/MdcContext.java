package com.wgh.rag.common.log;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * MDC（日志上下文）跨线程传递工具。
 * <p>
 * Web 请求线程的 MDC 不会自动传递到自建线程/线程池，SSE 与异步链路需显式
 * {@link #copy()} + {@link #wrap(Runnable)}，任务结束时恢复原上下文，避免
 * 线程复用造成 requestId 串号。
 */
public final class MdcContext {

    /** 链路追踪标识在 MDC 中的键（Micrometer Tracing / Brave 使用同名键）。 */
    public static final String TRACE_ID_KEY = "traceId";

    private MdcContext() {
    }

    /** 当前线程的 traceId；未处于链路中时返回 {@code null}。 */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 临时设置 traceId。
     *
     * @param traceId 目标 traceId；为空时移除
     * @return 设置前的 traceId，供调用方在 {@code finally} 中还原
     */
    public static String putTraceId(String traceId) {
        String previous = MDC.get(TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) {
            MDC.remove(TRACE_ID_KEY);
        } else {
            MDC.put(TRACE_ID_KEY, traceId);
        }
        return previous;
    }

    /** 还原 {@link #putTraceId(String)} 之前的 traceId。 */
    public static void restoreTraceId(String previous) {
        if (previous == null) {
            MDC.remove(TRACE_ID_KEY);
        } else {
            MDC.put(TRACE_ID_KEY, previous);
        }
    }

    /** 拷贝当前线程的 MDC 快照，供子线程恢复。 */
    public static Map<String, String> copy() {
        return MDC.getCopyOfContextMap();
    }

    /** 包装 Runnable：执行前恢复提交时的 MDC，执行后还原。 */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = copy();
        return () -> {
            Map<String, String> previous = copy();
            apply(snapshot);
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    /** 包装 Callable：语义同 {@link #wrap(Runnable)}，并保留返回值与异常。 */
    public static <V> Callable<V> wrap(Callable<V> task) {
        Map<String, String> snapshot = copy();
        return () -> {
            Map<String, String> previous = copy();
            apply(snapshot);
            try {
                return task.call();
            } finally {
                apply(previous);
            }
        };
    }

    /**
     * 包装 Executor：每个提交的任务都继承提交线程的 MDC。
     * <p>用于无法通过 Spring {@code TaskDecorator} 统一配置的自建线程池
     * （如固定线程池、并行工作线程），避免异步日志丢失 requestId/traceId。</p>
     */
    public static Executor wrap(Executor delegate) {
        return command -> delegate.execute(wrap(command));
    }

    private static void apply(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
