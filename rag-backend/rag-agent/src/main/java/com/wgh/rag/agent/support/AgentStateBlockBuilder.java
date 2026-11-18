package com.wgh.rag.agent.support;

import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.context.ContextAssembler;
import com.wgh.rag.context.ContextSegment;
import com.wgh.rag.context.SegmentType;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.llm.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 结构化状态块构建器。
 *
 * <p>把「已完成步骤」从平铺的 StringBuilder 拼接改为结构化状态块：
 * 每步保留 stepId / 标题 / 一句话结论，并交给 {@link ContextAssembler} 在
 * {@code wgh.agent.agent-step-context-max-tokens} 预算内按「最近优先」分配，
 * 预算不足时整步丢弃更早的步骤，而不是截断最近步骤。
 */
@Component
@RequiredArgsConstructor
public class AgentStateBlockBuilder {

    static final String HEADER = "## 已完成步骤（结构化状态）\n";
    private static final String ENTRY_TEMPLATE = "【步骤 %s - %s】\n%s\n\n";
    private static final String SUMMARY_TRIM_MARK = "…（步骤结论已截断）";
    /** 最近步骤优先级基数（数字越小越优先） */
    private static final int RECENT_PRIORITY_BASE = 1_000;

    private final AgentProperties properties;

    /** 已完成步骤条目。 */
    public record Entry(String stepId, String title, String summary) {
    }

    /**
     * 构建状态块；无有效条目时返回空串。
     */
    public String build(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        List<Entry> valid = entries.stream()
                .filter(e -> e != null && e.summary() != null && !e.summary().isBlank())
                .toList();
        if (valid.isEmpty()) {
            return "";
        }

        int budget = Math.max(properties.getAgentStepContextMaxTokens(), 1);
        List<ContextSegment> segments = new ArrayList<>();
        segments.add(new ContextSegment(-1, SegmentType.AGENT_STATE, HEADER, false));
        for (int i = 0; i < valid.size(); i++) {
            Entry entry = valid.get(i);
            String content = ENTRY_TEMPLATE.formatted(entry.stepId(), entry.title(),
                    clipSummary(entry.summary()));
            // 越靠后的步骤优先级越高，预算不足时先丢弃更早的步骤
            int priority = RECENT_PRIORITY_BASE + (valid.size() - i);
            segments.add(new ContextSegment(i, SegmentType.AGENT_STATE, content, false, priority));
        }

        ContextAssembler.Result result = ContextAssembler.assemble(
                budget, ContextAssembler.Options.of(1.0, budget, 0),
                segments, TokenEstimator::estimate, (text, maxTokens) -> null);
        if (result.kept().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContextAssembler.KeptSegment kept : result.kept()) {
            sb.append(kept.content());
        }
        return sb.toString();
    }

    private String clipSummary(String summary) {
        int maxTokens = Math.max(properties.getAgentStepSummaryMaxTokens(), 1);
        if (TokenEstimator.estimate(summary) <= maxTokens) {
            return summary;
        }
        int markTokens = TokenEstimator.estimate(SUMMARY_TRIM_MARK);
        return RagContextBudgeter.clip(summary, Math.max(maxTokens - markTokens, 1)) + SUMMARY_TRIM_MARK;
    }
}
