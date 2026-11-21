package com.wgh.rag.scheduler.facade;

import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.scheduler.execution.DefaultCancellationToken;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交互式执行的进程内登记。
 *
 * <p>交互式执行不落作业表：状态、取消令牌与事件只在持有执行体的实例内可见。
 * 后台作业的登记在作业表，本类不参与。
 */
public class ExecutionTicket {

    private final String executionId;

    private final ExecutionRequest request;

    private final JobHandler handler;

    private final Instant submittedAt = Instant.now();

    private final Instant startedAt = Instant.now();

    private final AtomicReference<ExecutionStatus> status = new AtomicReference<>(ExecutionStatus.RUNNING);

    private final DefaultCancellationToken cancellation = new DefaultCancellationToken();

    private volatile Instant finishedAt;

    private volatile String errorCode;

    private volatile JobContext context;

    public ExecutionTicket(String executionId, ExecutionRequest request, JobHandler handler) {
        this.executionId = executionId;
        this.request = request;
        this.handler = handler;
    }

    public String executionId() {
        return executionId;
    }

    public ExecutionRequest request() {
        return request;
    }

    public JobHandler handler() {
        return handler;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public DefaultCancellationToken cancellation() {
        return cancellation;
    }

    public ExecutionStatus status() {
        return status.get();
    }

    public void bindContext(JobContext context) {
        this.context = context;
    }

    public JobContext context() {
        return context;
    }

    /** 落终态。 */
    public void finish(ExecutionStatus terminal, String errorCode) {
        this.errorCode = errorCode;
        this.finishedAt = Instant.now();
        this.status.set(terminal);
    }

    /** 当前快照。 */
    public ExecutionSnapshot snapshot() {
        return new ExecutionSnapshot(
                executionId,
                null,
                request.jobType(),
                request.lane(),
                status.get(),
                request.tenantId(),
                request.ownerUserId(),
                request.bizKey(),
                1,
                Map.of(),
                errorCode,
                submittedAt,
                startedAt,
                finishedAt);
    }
}
