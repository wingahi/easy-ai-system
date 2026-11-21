package com.wgh.rag.scheduler.execution;

import com.wgh.rag.execution.error.ExecutionCancelledException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultCancellationTokenTest {

    @Test
    void shouldBeCancellableOnceAndKeepFirstReason() {
        DefaultCancellationToken token = new DefaultCancellationToken();

        assertThat(token.isCancelled()).isFalse();
        assertThat(token.cancel("用户取消")).isTrue();
        assertThat(token.cancel("第二次取消")).isFalse();
        assertThat(token.isCancelled()).isTrue();
        assertThat(token.reason()).isEqualTo("用户取消");
    }

    @Test
    void shouldThrowCancelledExceptionWhenRequested() {
        DefaultCancellationToken token = new DefaultCancellationToken();
        token.cancel("超时");

        assertThatThrownBy(token::throwIfCancellationRequested)
                .isInstanceOf(ExecutionCancelledException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void shouldNotThrowWhenNotCancelled() {
        new DefaultCancellationToken().throwIfCancellationRequested();
    }
}
