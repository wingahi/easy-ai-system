package com.wgh.rag.scheduler.facade;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.model.ExecutionHandle;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.scheduler.admission.AdmissionController;
import com.wgh.rag.scheduler.admission.AdmissionDecision;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.event.ExecutionEventBusRegistry;
import com.wgh.rag.scheduler.job.JobDispatcher;
import com.wgh.rag.scheduler.job.JobHandlerRegistry;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultExecutionFacadeTest {

    @Mock
    private AdmissionController admissionController;

    @Mock
    private JobStore jobStore;

    @Mock
    private JobHandlerRegistry handlerRegistry;

    @Mock
    private JobDispatcher jobDispatcher;

    private SchedulerProperties properties;

    private LaneExecutors laneExecutors;

    private DefaultExecutionFacade facade;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        properties.getInteractive().setCoreSize(2);
        properties.getInteractive().setMaxSize(2);
        properties.getInteractive().setQueueCapacity(2);
        laneExecutors = new LaneExecutors(properties);
        ExecutionEventBusRegistry eventBuses = new ExecutionEventBusRegistry(properties);
        facade = new DefaultExecutionFacade(properties, admissionController, laneExecutors,
                jobStore, handlerRegistry, eventBuses, jobDispatcher);
    }

    @AfterEach
    void tearDown() {
        laneExecutors.shutdown();
    }

    @Test
    void shouldSubmitBackgroundJobAndKickDispatcher() {
        when(handlerRegistry.find("WF_RUN")).thenReturn(Optional.of(noopHandler("WF_RUN")));
        when(admissionController.decide(any())).thenReturn(AdmissionDecision.permit());
        ExecutionJob job = pendingJob("e-1");
        when(jobStore.submit(any(), eq(3), isNull())).thenReturn(job);

        ExecutionHandle handle = facade.submit(ExecutionRequest.builder("WF_RUN", ExecutionLane.BACKGROUND).build());

        assertThat(handle.executionId()).isEqualTo("e-1");
        assertThat(handle.lane()).isEqualTo(ExecutionLane.BACKGROUND);
        assertThat(handle.status()).isEqualTo(ExecutionStatus.PENDING);
        verify(jobDispatcher).kick();
    }

    @Test
    void shouldRejectUnknownJobType() {
        when(handlerRegistry.find("UNKNOWN")).thenReturn(Optional.empty());

        ExecutionException error = catchThrowableOfType(
                () -> facade.submit(ExecutionRequest.builder("UNKNOWN", ExecutionLane.BACKGROUND).build()),
                ExecutionException.class);

        assertThat(error.code()).isEqualTo(ExecutionErrorCodes.JOB_TYPE_UNKNOWN);
    }

    @Test
    void shouldRejectWhenAdmissionDenied() {
        when(handlerRegistry.find("WF_RUN")).thenReturn(Optional.of(noopHandler("WF_RUN")));
        when(admissionController.decide(any())).thenReturn(
                AdmissionDecision.reject(ExecutionErrorCodes.SCHEDULER_REJECTED, "队列已满"));

        ExecutionException error = catchThrowableOfType(
                () -> facade.submit(ExecutionRequest.builder("WF_RUN", ExecutionLane.BACKGROUND).build()),
                ExecutionException.class);

        assertThat(error.code()).isEqualTo(ExecutionErrorCodes.SCHEDULER_REJECTED);
    }

    @Test
    void shouldDelegateQueryAndCancelToJobStore() {
        ExecutionSnapshot snapshot = new ExecutionSnapshot("e-1", null, "WF_RUN",
                ExecutionLane.BACKGROUND, ExecutionStatus.RUNNING, "default", null, null, 1,
                Map.of(), null, Instant.now(), Instant.now(), null);
        when(jobStore.query("e-1")).thenReturn(Optional.of(snapshot));
        when(jobStore.cancel("e-1", "用户取消")).thenReturn(true);

        assertThat(facade.query("e-1")).contains(snapshot);
        assertThat(facade.cancel("e-1", "用户取消")).isTrue();
    }

    @Test
    void shouldRunInteractiveExecutionAndReachSucceeded() throws Exception {
        CountDownLatch executed = new CountDownLatch(1);
        JobHandler handler = new JobHandler() {
            @Override
            public String jobType() {
                return "CHAT";
            }

            @Override
            public void execute(JobContext context) {
                executed.countDown();
            }
        };
        when(handlerRegistry.find("CHAT")).thenReturn(Optional.of(handler));
        when(admissionController.decide(any())).thenReturn(AdmissionDecision.permit());

        ExecutionHandle handle = facade.submit(ExecutionRequest.builder("CHAT", ExecutionLane.INTERACTIVE).build());

        assertThat(handle.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(executed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitStatus(handle.executionId())).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void shouldReturnNoopSubscriptionWhenExecutionIsNotLocal() throws Exception {
        AutoCloseable subscription = facade.attach("not-local", 0, event -> {
        });

        subscription.close();
    }

    private ExecutionStatus awaitStatus(String executionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<ExecutionSnapshot> snapshot = facade.query(executionId);
            if (snapshot.isPresent() && snapshot.get().status().isTerminal()) {
                return snapshot.get().status();
            }
            Thread.sleep(20);
        }
        return facade.query(executionId).map(ExecutionSnapshot::status).orElse(null);
    }

    private static JobHandler noopHandler(String jobType) {
        return new JobHandler() {
            @Override
            public String jobType() {
                return jobType;
            }

            @Override
            public void execute(JobContext context) {
            }
        };
    }

    private static ExecutionJob pendingJob(String executionId) {
        ExecutionJob job = new ExecutionJob();
        job.setExecutionId(executionId);
        job.setJobType("WF_RUN");
        job.setLane(ExecutionLane.BACKGROUND);
        job.setStatus(ExecutionStatus.PENDING);
        job.setTenantId("default");
        return job;
    }
}
