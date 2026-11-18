package com.wgh.rag.tool.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具结果结构化信封测试：分页游标、摘要抽取、状态分类。
 */
@DisplayName("工具结果信封")
class ToolResultNormalizerTest {

    @Test
    @DisplayName("短结果 - 不分页、成功状态")
    void shortResultNoPaging() {
        ToolResultEnvelope envelope = ToolResultNormalizer.firstPage("t", "{\"ok\":true}", 100, 50);

        assertThat(envelope.success()).isTrue();
        assertThat(envelope.failureKind()).isNull();
        assertThat(envelope.truncated()).isFalse();
        assertThat(envelope.hasMore()).isFalse();
        assertThat(envelope.content()).isEqualTo("{\"ok\":true}");
        assertThat(envelope.totalChars()).isEqualTo(11);
    }

    @Test
    @DisplayName("长结果 - 首屏截断并给下一页游标")
    void longResultPages() {
        String text = "a".repeat(250);

        ToolResultEnvelope page1 = ToolResultNormalizer.firstPage("t", text, 100, 50);

        assertThat(page1.content()).hasSize(100);
        assertThat(page1.truncated()).isTrue();
        assertThat(page1.nextCursor()).isEqualTo("100");
        assertThat(page1.totalChars()).isEqualTo(250);

        ToolResultEnvelope page2 = ToolResultNormalizer.page("t", text, 100, 100, 50);
        assertThat(page2.content()).hasSize(100);
        assertThat(page2.nextCursor()).isEqualTo("200");

        ToolResultEnvelope page3 = ToolResultNormalizer.page("t", text, 200, 100, 50);
        assertThat(page3.content()).hasSize(50);
        assertThat(page3.hasMore()).isFalse();
    }

    @Test
    @DisplayName("越界游标 - 返回空页且无下一页")
    void offsetBeyondEnd() {
        ToolResultEnvelope envelope = ToolResultNormalizer.page("t", "abc", 99, 100, 50);

        assertThat(envelope.content()).isEmpty();
        assertThat(envelope.hasMore()).isFalse();
        assertThat(envelope.totalChars()).isEqualTo(3);
    }

    @Test
    @DisplayName("JSON 对象摘要 - 字段清单")
    void jsonObjectSummary() {
        String summary = ToolResultNormalizer.summarize("{\"id\":1,\"name\":\"x\",\"items\":[]}", 200);

        assertThat(summary).contains("JSON 对象").contains("id").contains("name")
                .contains("items").contains("共 3 个");
    }

    @Test
    @DisplayName("JSON 数组摘要 - 条目数 + 首项")
    void jsonArraySummary() {
        String summary = ToolResultNormalizer.summarize("[{\"a\":1},{\"a\":2}]", 200);

        assertThat(summary).startsWith("JSON 数组，共 2 项").contains("首项");
    }

    @Test
    @DisplayName("摘要超长 - 按字符截断")
    void summaryTruncated() {
        String summary = ToolResultNormalizer.summarize("x".repeat(500), 20);

        assertThat(summary).hasSize(21).endsWith("…");
    }

    @Test
    @DisplayName("错误结果 - 失败状态与分类")
    void errorEnvelope() {
        ToolResultEnvelope envelope = ToolResultNormalizer.firstPage("t", "HTTP 401 - unauthorized", 100, 50);

        assertThat(envelope.success()).isFalse();
        assertThat(envelope.failureKind()).isEqualTo(ToolFailureKind.AUTH);
    }

    @Test
    @DisplayName("非法 JSON - 退回文本摘要")
    void invalidJsonFallsBackToText() {
        String summary = ToolResultNormalizer.summarize("{not-json", 200);
        assertThat(summary).isEqualTo("{not-json");
    }
}
