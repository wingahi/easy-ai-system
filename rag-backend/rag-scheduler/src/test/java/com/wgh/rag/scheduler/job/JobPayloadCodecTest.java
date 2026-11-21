package com.wgh.rag.scheduler.job;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobPayloadCodecTest {

    private final JobPayloadCodec codec = new JobPayloadCodec();

    @Test
    void shouldRoundTripPayload() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflowId", "42");
        data.put("input", "你好");
        data.put("attempt", 2);

        Map<String, Object> parsed = codec.fromJson(codec.toJson(data));

        assertThat(parsed).containsEntry("workflowId", "42")
                .containsEntry("input", "你好")
                .containsEntry("attempt", 2);
    }

    @Test
    void shouldTreatBlankAsEmptyObject() {
        assertThat(codec.fromJson(null)).isEmpty();
        assertThat(codec.fromJson("  ")).isEmpty();
    }

    @Test
    void shouldRejectInvalidJson() {
        assertThatThrownBy(() -> codec.fromJson("{not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
