package com.wgh.rag.scheduler.execution;

import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.execution.job.ExecutionCancellationToken;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.ExecutionRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 调度层注入给 {@code JobHandler} 的运行上下文。
 *
 * <p>执行期不允许回读 HTTP 请求上下文；租户、属主、负载都在提交时冻结在
 * {@link #request()} 中。心跳动作由调度层提供：后台作业续约数据库租约与单飞锁，
 * 交互式执行则为无操作。
 */
public class JobContextImpl implements JobContext {

    private final String executionId;

    private final ExecutionRequest request;

    private final ExecutionEventSink events;

    private final ExecutionCancellationToken cancellation;

    private final Runnable heartbeatAction;

    private final AtomicReference<Map<String, Object>> savedResult;

    private final Consumer<Map<String, Object>> saveAction;

    public JobContextImpl(String executionId,
                          ExecutionRequest request,
                          ExecutionEventSink events,
                          ExecutionCancellationToken cancellation,
                          Runnable heartbeatAction) {
        this(executionId, request, events, cancellation, heartbeatAction, Map.of(), null);
    }

    public JobContextImpl(String executionId,
                          ExecutionRequest request,
                          ExecutionEventSink events,
                          ExecutionCancellationToken cancellation,
                          Runnable heartbeatAction,
                          Map<String, Object> initialResult,
                          Consumer<Map<String, Object>> saveAction) {
        this.executionId = executionId;
        this.request = request;
        this.events = events;
        this.cancellation = cancellation;
        this.heartbeatAction = heartbeatAction;
        this.savedResult = new AtomicReference<>(initialResult == null ? Map.of() : Map.copyOf(initialResult));
        this.saveAction = saveAction;
    }

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public ExecutionRequest request() {
        return request;
    }

    @Override
    public ExecutionEventSink events() {
        return events;
    }

    @Override
    public ExecutionCancellationToken cancellation() {
        return cancellation;
    }

    @Override
    public void heartbeat() {
        if (heartbeatAction != null) {
            heartbeatAction.run();
        }
    }

    @Override
    public Map<String, Object> savedResult() {
        return savedResult.get();
    }

    @Override
    public void saveResult(Map<String, Object> result) {
        Map<String, Object> snapshot = result == null ? Map.of() : new LinkedHashMap<>(result);
        if (saveAction != null) {
            saveAction.accept(snapshot);
        }
        savedResult.set(Map.copyOf(snapshot));
    }
}
