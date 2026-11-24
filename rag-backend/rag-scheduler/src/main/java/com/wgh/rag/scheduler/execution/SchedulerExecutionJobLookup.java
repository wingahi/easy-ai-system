package com.wgh.rag.scheduler.execution;

import com.wgh.rag.data.tenant.DatabaseRoleTemplate;
import com.wgh.rag.execution.api.ExecutionJobLookup;
import com.wgh.rag.scheduler.mapper.ExecutionJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 基于 {@code wgh_execution_job.result_json.runId} 的活跃作业查询。
 *
 * <p>跨租户调度查询，在独立事务内切到 {@code wgh_scheduler}，不依赖调用线程的租户上下文。
 */
@Component
@RequiredArgsConstructor
public class SchedulerExecutionJobLookup implements ExecutionJobLookup {

    private static final String SCHEDULER_ROLE = "wgh_scheduler";

    private final ExecutionJobMapper mapper;

    private final DatabaseRoleTemplate roles;

    @Override
    public boolean hasActiveJobForRun(String jobType, String runId) {
        if (jobType == null || runId == null) {
            return false;
        }
        return roles.runReadOnlyAs(SCHEDULER_ROLE,
                () -> mapper.countActiveByResultRunId(jobType, runId)) > 0;
    }
}
