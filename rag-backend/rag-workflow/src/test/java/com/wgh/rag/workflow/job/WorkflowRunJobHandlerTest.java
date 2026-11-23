package com.wgh.rag.workflow.job;

import com.wgh.rag.execution.event.ExecutionEventSink;
import com.wgh.rag.execution.job.ExecutionCancellationToken;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRunJobHandlerTest {

    @Mock
    private WorkflowService workflowService;
    @Mock
    private JobContext context;
    @Mock
    private ExecutionEventSink events;
    @Mock
    private ExecutionCancellationToken cancellation;

    @Test
    void firstExecutionCreatesRunAndPersistsRunId() {
        when(context.payload()).thenReturn(JobPayload.of(Map.of("workflowId", "1", "input", "hi")));
        when(context.cancellation()).thenReturn(cancellation);
        when(context.events()).thenReturn(events);
        when(context.executionId()).thenReturn("exec-1");
        when(context.savedResult()).thenReturn(Map.of());
        WorkflowRun run = new WorkflowRun();
        run.setId(88L);
        run.setStatus("COMPLETED");
        when(workflowService.runHeadlessBlocking(eq(1L), eq("hi"), any())).thenReturn(run);

        new WorkflowRunJobHandler(workflowService).execute(context);

        verify(context).saveResult(Map.of("runId", 88L, "status", "COMPLETED"));
        verify(workflowService, never()).resumeInterrupted(anyLong(), any());
    }

    @Test
    void retryWithSavedRunIdResumesInsteadOfCreatingNewRun() {
        when(context.payload()).thenReturn(JobPayload.of(Map.of("workflowId", "1", "input", "hi")));
        when(context.cancellation()).thenReturn(cancellation);
        when(context.events()).thenReturn(events);
        when(context.executionId()).thenReturn("exec-2");
        when(context.savedResult()).thenReturn(Map.of("runId", 77L));
        WorkflowRun run = new WorkflowRun();
        run.setId(77L);
        run.setStatus("COMPLETED");
        when(workflowService.resumeInterrupted(77L, context)).thenReturn(run);

        new WorkflowRunJobHandler(workflowService).execute(context);

        verify(workflowService).resumeInterrupted(77L, context);
        verify(workflowService, never()).runHeadlessBlocking(anyLong(), anyString(), any());
    }
}
