package com.wgh.rag.scheduler.execution;

import com.wgh.rag.execution.api.ExecutionJobLookup;
import com.wgh.rag.scheduler.mapper.ExecutionJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 基于 {@code wgh_execution_job.result_json.runId} 的活跃作业查询。
 */
@Component
@RequiredArgsConstructor
public class SchedulerExecutionJobLookup implements ExecutionJobLookup {

    private final ExecutionJobMapper mapper;

    @Override
    public boolean hasActiveJobForRun(String jobType, String runId) {
        if (jobType == null || runId == null) {
            return false;
        }
        return mapper.countActiveByResultRunId(jobType, runId) > 0;
    }
}
