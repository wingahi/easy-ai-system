package com.wgh.rag.controller.service.resume;

import com.wgh.rag.controller.service.RunningTaskService;
import com.wgh.rag.data.tenant.PlatformScanTemplate;
import com.wgh.rag.execution.api.ExecutionFacade;
import com.wgh.rag.execution.api.ExecutionJobLookup;
import com.wgh.rag.execution.job.ExecutionCancellationToken;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RunResumeScanJobHandlerTest {

    @Mock
    private RunningTaskService runningTaskService;
    @Mock
    private ExecutionFacade executionFacade;
    @Mock
    private ExecutionJobLookup jobLookup;
    @Mock
    private ObjectProvider<ExecutionFacade> facadeProvider;
    @Mock
    private ObjectProvider<ExecutionJobLookup> lookupProvider;
    @Mock
    private JobContext context;
    @Mock
    private ExecutionCancellationToken cancellationToken;
    @Mock
    private PlatformScanTemplate platformScan;

    private ResumeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ResumeProperties();
        properties.setStaleMinutes(30);
        properties.setBatchSize(20);
        lenient().when(platformScan.scan(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            PlatformScanTemplate.TenantTask task = invocation.getArgument(2);
            task.run("default");
            return new PlatformScanTemplate.Result(1, 1, 0, 0, 0, false);
        });
    }

    @Test
    void submitsResumeJobsAndSkipsWorkflowWithActiveRun() {
        when(context.cancellation()).thenReturn(cancellationToken);
        when(facadeProvider.getIfAvailable()).thenReturn(executionFacade);
        when(lookupProvider.getIfAvailable()).thenReturn(jobLookup);
        LocalDateTime last = LocalDateTime.of(2026, 11, 23, 10, 0);
        when(runningTaskService.listStaleCandidates("default", 30, 20)).thenReturn(List.of(
                new RunningTaskService.StaleCandidate(RunningTaskService.TYPE_AGENT, "11", "RUNNING", last),
                new RunningTaskService.StaleCandidate(RunningTaskService.TYPE_MULTI_AGENT, "22", "RUNNING", last),
                new RunningTaskService.StaleCandidate(RunningTaskService.TYPE_WORKFLOW, "33", "RUNNING", last)));
        when(jobLookup.hasActiveJobForRun("WF_RUN", "33")).thenReturn(true);

        handler().execute(context);

        ArgumentCaptor<ExecutionRequest> captor = ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(executionFacade, org.mockito.Mockito.times(2)).submit(captor.capture());
        List<ExecutionRequest> requests = captor.getAllValues();
        assertThat(requests).allSatisfy(r -> assertThat(r.lane()).isEqualTo(ExecutionLane.BACKGROUND));
        assertThat(requests).extracting(r -> r.payload().getString("runType"))
                .containsExactlyInAnyOrder("AGENT", "MULTI_AGENT");
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.jobType()).isEqualTo(ResumeJobTypes.RUN_RESUME);
            assertThat(r.bizKey()).startsWith("run-resume:");
            assertThat(r.bizKey()).endsWith(":" + last.toEpochSecond(java.time.ZoneOffset.UTC));
        });
        verify(executionFacade, never()).submit(org.mockito.ArgumentMatchers.argThat(
                r -> r != null && "33".equals(r.payload().getString("runId"))));
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        handler().execute(context);

        verify(runningTaskService, never()).listStaleCandidates(anyString(), anyLong(), anyInt());
        verify(executionFacade, never()).submit(any());
    }

    private RunResumeScanJobHandler handler() {
        return new RunResumeScanJobHandler(properties, runningTaskService, facadeProvider, lookupProvider,
                platformScan);
    }
}
