package com.wgh.rag.controller.service.resume;

import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.run.RunAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRunRegistryTest {

    @Test
    void findsAdapterCaseInsensitively() {
        RunAdapter adapter = adapter("WORKFLOW");
        DefaultRunRegistry registry = new DefaultRunRegistry(List.of(adapter));

        assertThat(registry.find("workflow")).containsSame(adapter);
        assertThat(registry.find("WORKFLOW")).containsSame(adapter);
        assertThat(registry.runTypes()).containsExactly("WORKFLOW");
    }

    @Test
    void rejectsDuplicateRunType() {
        assertThatThrownBy(() -> new DefaultRunRegistry(List.of(adapter("AGENT"), adapter("agent"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT");
    }

    private RunAdapter adapter(String runType) {
        return new RunAdapter() {
            @Override
            public String runType() {
                return runType;
            }

            @Override
            public Optional<ExecutionSnapshot> load(String runId) {
                return Optional.empty();
            }

            @Override
            public void resume(String runId, JobContext context) {
            }

            @Override
            public boolean cancel(String runId) {
                return false;
            }
        };
    }
}
