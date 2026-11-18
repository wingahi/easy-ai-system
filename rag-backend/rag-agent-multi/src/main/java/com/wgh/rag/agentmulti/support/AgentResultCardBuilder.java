package com.wgh.rag.agentmulti.support;

import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.dto.AgentResultCard;
import com.wgh.rag.context.ContextAssembler;
import com.wgh.rag.context.ContextSegment;
import com.wgh.rag.context.SegmentType;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.llm.util.TokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结果卡片构建与合并（纯函数）。
 */
public final class AgentResultCardBuilder {

    private static final int MAX_EVIDENCE = 5;
    private static final String TRIM_MARK = "…（卡片结论已截断）";

    private AgentResultCardBuilder() {
    }

    /**
     * 从一次 Agent 执行结果构建卡片。
     */
    public static AgentResultCard build(String agentId, String agentName, String output,
                                        List<AgentInstanceResult.RagSource> ragSources,
                                        List<AgentInstanceResult.ToolInvocation> toolInvocations,
                                        Double reflectionScore, int conclusionMaxTokens) {
        List<String> evidence = new ArrayList<>();
        if (ragSources != null) {
            ragSources.stream()
                    .map(AgentInstanceResult.RagSource::getFileName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .limit(MAX_EVIDENCE)
                    .forEach(name -> evidence.add("检索: " + name));
        }
        if (toolInvocations != null) {
            Set<String> names = new LinkedHashSet<>();
            toolInvocations.stream()
                    .filter(AgentInstanceResult.ToolInvocation::isSuccess)
                    .map(AgentInstanceResult.ToolInvocation::getToolName)
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(names::add);
            names.stream().limit(MAX_EVIDENCE).forEach(name -> evidence.add("工具: " + name));
        }
        Double confidence = reflectionScore == null ? null
                : Math.max(0d, Math.min(1d, reflectionScore / 100d));
        String text = output == null ? "" : output;
        return AgentResultCard.builder()
                .agentId(agentId)
                .agentName(agentName)
                .conclusion(clipConclusion(text, conclusionMaxTokens))
                .evidence(evidence)
                .confidence(confidence)
                .outputChars(text.length())
                .build();
    }

    /**
     * 把多张卡片合并为汇总输入，受总预算约束；超预算优先裁剪靠后的卡片。
     */
    public static String merge(List<AgentResultCard> cards, int budgetTokens) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        List<ContextSegment> segments = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            segments.add(new ContextSegment(i, SegmentType.AGENT_STATE, format(cards.get(i)), false));
        }
        int budget = Math.max(budgetTokens, 1);
        ContextAssembler.Result result = ContextAssembler.assemble(
                budget, ContextAssembler.Options.of(1.0, budget, 0),
                segments, TokenEstimator::estimate, (text, maxTokens) -> null);
        StringBuilder sb = new StringBuilder();
        for (ContextAssembler.KeptSegment kept : result.kept()) {
            sb.append(kept.content()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 单张卡片文本。 */
    public static String format(AgentResultCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(card.getAgentName() != null ? card.getAgentName() : card.getAgentId()).append('\n');
        sb.append("结论：").append(card.getConclusion() == null ? "" : card.getConclusion()).append('\n');
        if (card.getEvidence() != null && !card.getEvidence().isEmpty()) {
            sb.append("证据：").append(String.join("；", card.getEvidence())).append('\n');
        }
        if (card.getConfidence() != null) {
            sb.append("置信度：").append(String.format("%.2f", card.getConfidence()));
        }
        return sb.toString().stripTrailing();
    }

    private static String clipConclusion(String text, int maxTokens) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int limit = Math.max(maxTokens, 1);
        if (TokenEstimator.estimate(text) <= limit) {
            return text;
        }
        int markTokens = TokenEstimator.estimate(TRIM_MARK);
        return RagContextBudgeter.clip(text, Math.max(limit - markTokens, 1)) + TRIM_MARK;
    }
}
