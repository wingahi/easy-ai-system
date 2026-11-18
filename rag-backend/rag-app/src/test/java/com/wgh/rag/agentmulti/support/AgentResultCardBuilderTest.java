package com.wgh.rag.agentmulti.support;

import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.dto.AgentResultCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果卡片构建与合并测试。
 */
@DisplayName("多 Agent 结果卡片")
class AgentResultCardBuilderTest {

    private static AgentInstanceResult.RagSource source(String name) {
        return AgentInstanceResult.RagSource.builder().fileName(name).build();
    }

    private static AgentInstanceResult.ToolInvocation tool(String name, boolean success) {
        return AgentInstanceResult.ToolInvocation.builder().toolName(name).success(success).build();
    }

    @Test
    @DisplayName("卡片包含结论、证据与置信度")
    void buildsCard() {
        AgentResultCard card = AgentResultCardBuilder.build("a1", "研究员",
                "结论正文", List.of(source("a.pdf"), source("a.pdf")),
                List.of(tool("search", true), tool("fail", false)), 85d, 200);

        assertThat(card.getConclusion()).isEqualTo("结论正文");
        assertThat(card.getEvidence()).containsExactly("检索: a.pdf", "工具: search");
        assertThat(card.getConfidence()).isEqualTo(0.85);
        assertThat(card.getOutputChars()).isEqualTo(4);
    }

    @Test
    @DisplayName("置信度越界 - 收敛到 0~1")
    void clampsConfidence() {
        assertThat(AgentResultCardBuilder.build("a", "n", "o", null, null, 150d, 200)
                .getConfidence()).isEqualTo(1.0);
        assertThat(AgentResultCardBuilder.build("a", "n", "o", null, null, -20d, 200)
                .getConfidence()).isZero();
        assertThat(AgentResultCardBuilder.build("a", "n", "o", null, null, null, 200)
                .getConfidence()).isNull();
    }

    @Test
    @DisplayName("超长结论按预算裁剪")
    void clipsConclusion() {
        AgentResultCard card = AgentResultCardBuilder.build("a", "n", "y".repeat(1000),
                null, null, null, 20);

        assertThat(card.getConclusion()).contains("已截断");
        assertThat(card.getConclusion().length()).isLessThan(500);
    }

    @Test
    @DisplayName("合并受预算约束 - 超预算丢弃靠后卡片")
    void mergeRespectsBudget() {
        AgentResultCard first = AgentResultCard.builder().agentId("a").agentName("A")
                .conclusion("x".repeat(200)).build();
        AgentResultCard second = AgentResultCard.builder().agentId("b").agentName("B")
                .conclusion("y".repeat(200)).build();

        String merged = AgentResultCardBuilder.merge(List.of(first, second), 60);

        assertThat(merged).contains("## A").doesNotContain("## B");
    }

    @Test
    @DisplayName("空列表合并 - 返回空串")
    void mergeEmpty() {
        assertThat(AgentResultCardBuilder.merge(List.of(), 100)).isEmpty();
        assertThat(AgentResultCardBuilder.merge(null, 100)).isEmpty();
    }
}
