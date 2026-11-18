package com.wgh.rag.agent.support;

import com.wgh.rag.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 结构化状态块测试：结构、预算丢弃顺序、结论裁剪。
 */
@DisplayName("Agent 结构化状态块")
class AgentStateBlockBuilderTest {

    private final AgentProperties properties = new AgentProperties();
    private final AgentStateBlockBuilder builder = new AgentStateBlockBuilder(properties);

    @Test
    @DisplayName("结构化输出 - 表头 + 按时间顺序的步骤")
    void buildsStructuredBlock() {
        String block = builder.build(List.of(
                new AgentStateBlockBuilder.Entry("s1", "OLD", "第一步结论"),
                new AgentStateBlockBuilder.Entry("s2", "NEW", "第二步结论")));

        assertThat(block).startsWith("## 已完成步骤（结构化状态）");
        assertThat(block).contains("【步骤 s1 - OLD】").contains("第一步结论");
        assertThat(block).contains("【步骤 s2 - NEW】").contains("第二步结论");
        assertThat(block.indexOf("s1")).isLessThan(block.indexOf("s2"));
    }

    @Test
    @DisplayName("预算不足 - 保留最近步骤，丢弃更早步骤")
    void dropsOldestWhenOverBudget() {
        properties.setAgentStepContextMaxTokens(80);
        String longSummary = "x".repeat(200);

        String block = builder.build(List.of(
                new AgentStateBlockBuilder.Entry("s1", "OLD", longSummary),
                new AgentStateBlockBuilder.Entry("s2", "NEW", longSummary)));

        assertThat(block).contains("NEW");
        assertThat(block).doesNotContain("OLD");
        assertThat(block).startsWith("## 已完成步骤（结构化状态）");
    }

    @Test
    @DisplayName("单步结论超预算 - 按句界裁剪并标注")
    void clipsLongSummary() {
        properties.setAgentStepSummaryMaxTokens(20);

        String block = builder.build(List.of(
                new AgentStateBlockBuilder.Entry("s1", "T", "y".repeat(500))));

        assertThat(block).contains("已截断");
        assertThat(block.length()).isLessThan(500);
    }

    @Test
    @DisplayName("空条目 - 返回空串")
    void emptyEntries() {
        assertThat(builder.build(List.of())).isEmpty();
        assertThat(builder.build(List.of(
                new AgentStateBlockBuilder.Entry("s1", "T", "  ")))).isEmpty();
        assertThat(builder.build(null)).isEmpty();
    }
}
