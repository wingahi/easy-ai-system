package com.wgh.rag.scheduler.job;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JobFailurePolicyTest {

    @Test
    void shouldRetryWhenAttemptsRemain() {
        assertThat(JobFailurePolicy.decide(1, 3, null)).isEqualTo(JobFailurePolicy.Outcome.RETRY);
        assertThat(JobFailurePolicy.decide(2, 3, "SOME_ERROR")).isEqualTo(JobFailurePolicy.Outcome.RETRY);
    }

    @Test
    void shouldDeadLetterWhenAttemptsExhausted() {
        assertThat(JobFailurePolicy.decide(3, 3, null)).isEqualTo(JobFailurePolicy.Outcome.DEAD);
        assertThat(JobFailurePolicy.decide(4, 3, "SOME_ERROR")).isEqualTo(JobFailurePolicy.Outcome.DEAD);
    }

    @Test
    void shouldDeadLetterImmediatelyForNonRetryableCodes() {
        assertThat(JobFailurePolicy.decide(1, 3, ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD))
                .isEqualTo(JobFailurePolicy.Outcome.DEAD);
        assertThat(JobFailurePolicy.decide(1, 3, ExecutionErrorCodes.JOB_TYPE_UNKNOWN))
                .isEqualTo(JobFailurePolicy.Outcome.DEAD);
    }

    @Test
    void shouldBackoffLinearlyAndFallbackForInvalidBase() {
        assertThat(JobFailurePolicy.backoff(2, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(60));
        assertThat(JobFailurePolicy.backoff(1, Duration.ZERO)).isEqualTo(Duration.ofSeconds(30));
        assertThat(JobFailurePolicy.backoff(3, Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(30));
    }
}
