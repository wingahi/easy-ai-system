package com.wgh.rag.llm.util;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果 Token 预算器（纯函数，无状态）。
 *
 * <p>现状是「单条 4000 字符硬截断 + 不参与总预算」，工具密集任务（多轮 × 多工具）
 * 会远超模型窗口。本类规则：
 * <ol>
 *   <li><b>单条上限</b>：超过 {@code perResultMaxTokens} 时按句界裁剪并追加显式截断标记，
 *       保留消息 id 与工具名（tool_call / tool_result 仍需成对）；</li>
 *   <li><b>累计上限</b>：全部工具结果的累计 Token 超过 {@code roundMaxTokens} 时，
 *       从最旧的结果开始替换为占位提示，直到回到预算内；最新一条优先保持完整；</li>
 *   <li>未超限时保持原文本，{@link Result#changed()} 为 false。</li>
 * </ol>
 */
public final class ToolResultBudgeter {

    /** 单条工具结果进入 prompt 的默认 Token 上限 */
    public static final int DEFAULT_TOOL_RESULT_MAX_TOKENS = 1200;
    /** 多轮工具结果累计的默认 Token 上限 */
    public static final int DEFAULT_TOOL_ROUND_HISTORY_MAX_TOKENS = 3000;

    /** 单条截断标记：显式可见，避免模型把残缺结果当成完整数据 */
    static final String TRUNCATE_MARK = "\n…（工具结果超出上下文预算，已截断）";
    /** 更早结果的占位提示 */
    static final String COLLAPSED_PLACEHOLDER =
            "…（更早的工具结果已裁剪以节省上下文，如需完整内容请重新调用）";

    private static final int TRUNCATE_MARK_TOKENS = TokenEstimator.estimate(TRUNCATE_MARK);

    /**
     * 裁剪结果。
     *
     * @param messages       替换后的新列表（调用方如需原地生效需自行 clear/addAll）
     * @param originalTokens 裁剪前全部工具结果的 Tokens
     * @param keptTokens     裁剪后全部工具结果的 Tokens
     * @param truncatedCount 被单条截断的条数
     * @param collapsedCount 被折叠为占位提示的条数
     */
    public record Result(List<ChatMessage> messages, int originalTokens, int keptTokens,
                         int truncatedCount, int collapsedCount) {
        public boolean changed() {
            return truncatedCount > 0 || collapsedCount > 0;
        }
    }

    private ToolResultBudgeter() {
    }

    /**
     * 按「单条 + 累计」预算裁剪消息中的工具结果。
     *
     * @param messages           完整消息列表（只处理 {@link ToolExecutionResultMessage}）
     * @param perResultMaxTokens 单条上限（非正数使用默认 1200）
     * @param roundMaxTokens     累计上限（非正数使用默认 3000）
     */
    public static Result trim(List<ChatMessage> messages, int perResultMaxTokens, int roundMaxTokens) {
        int perMax = perResultMaxTokens > 0 ? perResultMaxTokens : DEFAULT_TOOL_RESULT_MAX_TOKENS;
        int roundMax = roundMaxTokens > 0 ? roundMaxTokens : DEFAULT_TOOL_ROUND_HISTORY_MAX_TOKENS;
        if (messages == null || messages.isEmpty()) {
            return new Result(List.of(), 0, 0, 0, 0);
        }

        List<ChatMessage> result = new ArrayList<>(messages);
        List<Integer> toolIndexes = new ArrayList<>();
        int originalTokens = 0;
        int truncated = 0;

        // 1. 单条上限
        for (int i = 0; i < result.size(); i++) {
            if (!(result.get(i) instanceof ToolExecutionResultMessage tool)) {
                continue;
            }
            toolIndexes.add(i);
            String text = tool.text() != null ? tool.text() : "";
            int tokens = TokenEstimator.estimate(text);
            originalTokens += tokens;
            if (tokens > perMax) {
                int textBudget = Math.max(perMax - TRUNCATE_MARK_TOKENS, 1);
                String clipped = RagContextBudgeter.clip(text, textBudget);
                result.set(i, new ToolExecutionResultMessage(
                        tool.id(), tool.toolName(), clipped + TRUNCATE_MARK));
                truncated++;
            }
        }

        int keptTokens = sumToolTokens(result, toolIndexes);
        int collapsed = 0;

        // 2. 累计上限：保留最新一条完整结果，从最旧开始折叠
        for (int k = 0; k < toolIndexes.size() - 1 && keptTokens > roundMax; k++) {
            int idx = toolIndexes.get(k);
            ToolExecutionResultMessage tool = (ToolExecutionResultMessage) result.get(idx);
            if (COLLAPSED_PLACEHOLDER.equals(tool.text())) {
                continue;
            }
            result.set(idx, new ToolExecutionResultMessage(
                    tool.id(), tool.toolName(), COLLAPSED_PLACEHOLDER));
            keptTokens = sumToolTokens(result, toolIndexes);
            collapsed++;
        }

        return new Result(result, originalTokens, keptTokens, truncated, collapsed);
    }

    private static int sumToolTokens(List<ChatMessage> messages, List<Integer> toolIndexes) {
        int total = 0;
        for (int idx : toolIndexes) {
            ToolExecutionResultMessage tool = (ToolExecutionResultMessage) messages.get(idx);
            total += TokenEstimator.estimate(tool.text());
        }
        return total;
    }
}
