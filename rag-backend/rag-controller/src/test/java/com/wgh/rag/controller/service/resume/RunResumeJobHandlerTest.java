package com.wgh.rag.controller.service.resume;

import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.execution.run.RunAdapter;
import com.wgh.rag.execution.run.RunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunResumeJobHandlerTest {

    @Mock
    private RunRegistry registry;
    @Mock
    private JobContext context;

    @Test
    void dispatchesToRegisteredAdapter() {
        RunAdapter adapter = mock(RunAdapter.class);
        when(context.payload()).thenReturn(JobPayload.of(Map.of(
                RunResumeJobHandler.PAYLOAD_RUN_TYPE, "WORKFLOW",
                RunResumeJobHandler.PAYLOAD_RUN_ID, "42")));
        when(registry.find("WORKFLOW")).thenReturn(Optional.of(adapter));

        new RunResumeJobHandler(registry).execute(context);

        verify(adapter).resume("42", context);
    }

    @Test
    void rejectsMissingPayload() {
        when(context.payload()).thenReturn(JobPayload.empty());
        when(context.executionId()).thenReturn("exec-1");

        assertThatThrownBy(() -> new RunResumeJobHandler(registry).execute(context))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("runType/runId");
    }

    @Test
    void rejectsUnregisteredRunType() {
        when(context.payload()).thenReturn(JobPayload.of(Map.of(
                RunResumeJobHandler.PAYLOAD_RUN_TYPE, "UNKNOWN",
                RunResumeJobHandler.PAYLOAD_RUN_ID, "42")));
        when(context.executionId()).thenReturn("exec-2");
        when(registry.find("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new RunResumeJobHandler(registry).execute(context))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("未注册的 runType");
    }
}
