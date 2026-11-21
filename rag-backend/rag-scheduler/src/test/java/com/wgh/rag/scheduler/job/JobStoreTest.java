package com.wgh.rag.scheduler.job;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.mapper.ExecutionJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobStoreTest {

    @Mock
    private ExecutionJobMapper mapper;

    private JobStore store;

    @BeforeEach
    void setUp() {
        store = new JobStore(mapper, new JobPayloadCodec());
    }

    @Test
    void shouldInsertPendingJobWithFrozenContext() {
        ExecutionJob job = store.submit(request("k-1"), 3, 60);

        assertThat(job.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(job.getExecutionId()).isNotBlank();
        assertThat(job.getAttempt()).isZero();
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getTimeoutSeconds()).isEqualTo(60);
        assertThat(job.getPayloadJson()).contains("workflowId");
        verify(mapper).insert(job);
    }

    @Test
    void shouldReuseExistingJobForSameBizKey() {
        ExecutionJob existing = job("e-existing", ExecutionStatus.RUNNING);
        when(mapper.selectByBizKey("WF_RUN", "k-1")).thenReturn(existing);

        ExecutionJob job = store.submit(request("k-1"), 3, null);

        assertThat(job.getExecutionId()).isEqualTo("e-existing");
        verify(mapper, never()).insert(any(ExecutionJob.class));
    }

    @Test
    void shouldFallBackToExistingJobWhenUniqueIndexRejectsInsert() {
        ExecutionJob existing = job("e-existing", ExecutionStatus.PENDING);
        when(mapper.selectByBizKey("WF_RUN", "k-1")).thenReturn(null, existing);
        when(mapper.insert(any(ExecutionJob.class))).thenThrow(new DuplicateKeyException("uk_exec_job_biz"));

        ExecutionJob job = store.submit(request("k-1"), 3, null);

        assertThat(job.getExecutionId()).isEqualTo("e-existing");
        verify(mapper, times(1)).insert(any(ExecutionJob.class));
    }

    @Test
    void shouldMapResultRunIdIntoSnapshot() {
        ExecutionJob job = job("e-1", ExecutionStatus.SUCCEEDED);
        job.setResultJson("{\"runId\":\"77\"}");
        job.setStartTime(LocalDateTime.now());
        when(mapper.selectByExecutionId("e-1")).thenReturn(job);

        ExecutionSnapshot snapshot = store.query("e-1").orElseThrow();

        assertThat(snapshot.executionId()).isEqualTo("e-1");
        assertThat(snapshot.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(snapshot.runId()).isEqualTo("77");
        assertThat(snapshot.attempt()).isEqualTo(1);
    }

    @Test
    void shouldCancelPendingJob() {
        ExecutionJob job = job("e-1", ExecutionStatus.PENDING);
        when(mapper.selectByExecutionId("e-1")).thenReturn(job);
        when(mapper.cancelPending(job.getId(), "用户取消")).thenReturn(1);

        assertThat(store.cancel("e-1", "用户取消")).isTrue();
        verify(mapper, never()).requestCancel(any(), anyString());
    }

    @Test
    void shouldRequestCancelForRunningJob() {
        ExecutionJob job = job("e-1", ExecutionStatus.RUNNING);
        when(mapper.selectByExecutionId("e-1")).thenReturn(job);
        when(mapper.cancelPending(job.getId(), "用户取消")).thenReturn(0);
        when(mapper.requestCancel(job.getId(), "用户取消")).thenReturn(1);

        assertThat(store.cancel("e-1", "用户取消")).isTrue();
    }

    @Test
    void shouldNotCancelTerminalJob() {
        ExecutionJob job = job("e-1", ExecutionStatus.SUCCEEDED);
        when(mapper.selectByExecutionId("e-1")).thenReturn(job);

        assertThat(store.cancel("e-1", "太晚了")).isFalse();
        verify(mapper, never()).cancelPending(any(), anyString());
        verify(mapper, never()).requestCancel(any(), anyString());
    }

    @Test
    void shouldClaimNextJob() {
        ExecutionJob job = job("e-1", ExecutionStatus.LEASED);
        when(mapper.claimNext("BACKGROUND", "instance-1", 60L)).thenReturn(9L);
        when(mapper.selectById(9L)).thenReturn(job);

        Optional<ExecutionJob> claimed = store.claim(ExecutionLane.BACKGROUND, "instance-1",
                Duration.ofSeconds(60));

        assertThat(claimed).contains(job);
    }

    @Test
    void shouldReturnEmptyWhenNoJobToClaim() {
        when(mapper.claimNext(eq("MAINTENANCE"), eq("instance-1"), eq(60L))).thenReturn(null);

        assertThat(store.claim(ExecutionLane.MAINTENANCE, "instance-1", Duration.ofSeconds(60))).isEmpty();
    }

    private static ExecutionRequest request(String bizKey) {
        return ExecutionRequest.builder("WF_RUN", ExecutionLane.BACKGROUND)
                .bizKey(bizKey)
                .payload(Map.of("workflowId", "1"))
                .build();
    }

    private static ExecutionJob job(String executionId, ExecutionStatus status) {
        ExecutionJob job = new ExecutionJob();
        job.setId(1L);
        job.setExecutionId(executionId);
        job.setJobType("WF_RUN");
        job.setLane(ExecutionLane.BACKGROUND);
        job.setTenantId("default");
        job.setStatus(status);
        job.setAttempt(1);
        job.setMaxAttempts(3);
        job.setScheduledAt(LocalDateTime.now());
        return job;
    }
}
