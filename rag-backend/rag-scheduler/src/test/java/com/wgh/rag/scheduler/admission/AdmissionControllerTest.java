package com.wgh.rag.scheduler.admission;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdmissionControllerTest {

    @Mock
    private JobStore jobStore;

    private SchedulerProperties properties;

    private LaneExecutors laneExecutors;

    private AdmissionController admissionController;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        properties.getInteractive().setCoreSize(1);
        properties.getInteractive().setMaxSize(1);
        properties.getInteractive().setQueueCapacity(1);
        laneExecutors = new LaneExecutors(properties);
        admissionController = new AdmissionController(properties, laneExecutors, jobStore);
    }

    @AfterEach
    void tearDown() {
        laneExecutors.shutdown();
    }

    @Test
    void shouldRejectInteractiveWhenConcurrencyAndQueueFull() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        laneExecutors.executor(ExecutionLane.INTERACTIVE).execute(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        // 第一个任务占用唯一线程，第二个进入队列 → 在途已满 + 队列已满
        laneExecutors.executor(ExecutionLane.INTERACTIVE).execute(() -> {
        });
        assertThat(laneExecutors.tryReserve(ExecutionLane.INTERACTIVE)).isTrue();

        AdmissionDecision decision = admissionController.decide(
                ExecutionRequest.builder("CHAT", ExecutionLane.INTERACTIVE).build());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo(ExecutionErrorCodes.SCHEDULER_REJECTED);
        release.countDown();
    }

    @Test
    void shouldRejectBackgroundWhenPendingQueueFull() {
        when(jobStore.countPending(ExecutionLane.BACKGROUND)).thenReturn(500L);

        AdmissionDecision decision = admissionController.decide(
                ExecutionRequest.builder("EVAL_RUN", ExecutionLane.BACKGROUND).build());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo(ExecutionErrorCodes.SCHEDULER_REJECTED);
    }

    @Test
    void shouldQueueBackgroundWhenTenantConcurrencyFullAndQueueAllowed() {
        when(jobStore.countPending(ExecutionLane.BACKGROUND)).thenReturn(0L);
        when(jobStore.countTenantActive(any(), any())).thenReturn(2L);
        when(jobStore.countPendingByTenant(any(), any())).thenReturn(0L);

        // S2 默认策略：后台车道 reject_policy=QUEUE，并发满但未达排队上限时允许入队
        AdmissionDecision decision = admissionController.decide(
                ExecutionRequest.builder("EVAL_RUN", ExecutionLane.BACKGROUND).build());

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void shouldPermitBackgroundWhenWithinLimits() {
        when(jobStore.countPending(ExecutionLane.BACKGROUND)).thenReturn(1L);
        when(jobStore.countTenantActive(any(), any())).thenReturn(0L);

        AdmissionDecision decision = admissionController.decide(
                ExecutionRequest.builder("EVAL_RUN", ExecutionLane.BACKGROUND).build());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.code()).isNull();
    }

    @Test
    void shouldIgnoreTenantConcurrencyForMaintenanceLane() {
        when(jobStore.countPending(ExecutionLane.MAINTENANCE)).thenReturn(0L);

        AdmissionDecision decision = admissionController.decide(
                ExecutionRequest.builder("UPLOAD_CLEANUP", ExecutionLane.MAINTENANCE).build());

        assertThat(decision.allowed()).isTrue();
    }
}
