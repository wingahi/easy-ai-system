package com.wgh.rag.chat.service.prompt;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.util.MapReduceChunkSummarizer;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.llm.util.TokenEstimator;
import com.wgh.rag.llm.util.ToolResultBudgeter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天上下文预算：检索片段裁剪、单条 chunk 饱和时的 map-reduce 摘要、工具结果裁剪与 Prompt Token 估算。
 *
 * <p>预算规则（分数降序保留、单条最小 Token、硬上限与模型窗口比例）保持不变，
 * 只是从聊天服务中独立出来，便于单独验证裁剪结果。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextBudgetService {

    /** 拼接上下文模板（"已知知识库内容：xxx 用户问题：xxx …"）的固定 Token 开销 */
    private static final int RAG_PROMPT_TEMPLATE_TOKENS = 64;

    private final RagProperties ragProperties;
    private final ModelCacheManager modelCacheManager;
    private final RagMetrics metrics;

    /**
     * 按模型上下文窗口对检索结果做确定性裁剪，并把结果写回原列表，
     * 保证 prompt 拼接、引用来源、正确率计算使用同一批 chunk。
     */
    public RagContextBudgeter.Result applyRagBudget(List<EmbeddingMatch<TextSegment>> matches,
                                                    List<ChatMessage> messages, LlmModel model) {
        RagProperties.Context props = ragProperties.getContext();
        int promptTokens = estimatePromptTokens(messages) + RAG_PROMPT_TEMPLATE_TOKENS;
        int cap = props != null && props.getContextMaxTokens() != null
                ? props.getContextMaxTokens() : RagContextBudgeter.DEFAULT_MAX_CONTEXT_TOKENS;
        double ratio = props != null && props.getContextWindowRatio() != null
                ? props.getContextWindowRatio() : RagContextBudgeter.DEFAULT_INPUT_WINDOW_RATIO;
        int minChunk = props != null && props.getMinChunkTokens() != null
                ? props.getMinChunkTokens() : RagContextBudgeter.DEFAULT_MIN_CHUNK_TOKENS;
        Integer window = model.getMaxContextTokens() != null && model.getMaxContextTokens() > 0
                ? model.getMaxContextTokens()
                : (props != null ? props.getFallbackWindowTokens() : null);

        int budget = RagContextBudgeter.computeBudget(window, promptTokens, cap, ratio, minChunk);
        applyMapReduceIfSaturated(matches, budget, model, props);
        RagContextBudgeter.Result result = RagContextBudgeter.trim(matches, budget, minChunk);

        metrics.recordRagContextBudget(result.budgetTokens(), result.originalTokens(),
                result.keptTokens(), result.dropped().size(), result.truncated());
        log.info("RAG 上下文预算: model={}, window={}, promptTokens={}, budget={}, 原始 {} 条/{} tokens, "
                        + "保留 {} 条/{} tokens, 丢弃 {} 条, 截断={}",
                model.getModelName(), window, promptTokens, result.budgetTokens(),
                result.originalCount(), result.originalTokens(),
                result.keptCount(), result.keptTokens(), result.dropped().size(), result.truncated());
        for (RagContextBudgeter.DroppedChunk dropped : result.dropped()) {
            log.info("RAG 上下文丢弃 chunk: {}", dropped);
        }
        if (result.singleChunkSaturated()) {
            log.warn("RAG 检索上下文已撑满预算（保留 {} 条、丢弃 {} 条、截断={}）："
                            + "若已启用分段摘要仍饱和，建议调大预算或缩小单条 chunk",
                    result.keptCount(), result.dropped().size(), result.truncated());
        }

        matches.clear();
        matches.addAll(result.kept());
        return result;
    }

    /** 对消息中的工具结果做「单条 + 多轮累计」Token 裁剪（原地替换）。 */
    public void applyToolResultBudget(List<ChatMessage> messages) {
        RagProperties.Context props = ragProperties.getContext();
        int perResult = props != null && props.getToolResultMaxTokens() != null
                ? props.getToolResultMaxTokens() : ToolResultBudgeter.DEFAULT_TOOL_RESULT_MAX_TOKENS;
        int roundBudget = props != null && props.getToolRoundHistoryMaxTokens() != null
                ? props.getToolRoundHistoryMaxTokens() : ToolResultBudgeter.DEFAULT_TOOL_ROUND_HISTORY_MAX_TOKENS;
        ToolResultBudgeter.Result result = ToolResultBudgeter.trim(messages, perResult, roundBudget);
        if (!result.changed()) {
            return;
        }
        messages.clear();
        messages.addAll(result.messages());
        log.info("工具结果上下文预算: 单条上限={}, 累计上限={}, 原始 {} tokens, 保留 {} tokens, 截断 {} 条, 折叠 {} 条",
                perResult, roundBudget, result.originalTokens(), result.keptTokens(),
                result.truncatedCount(), result.collapsedCount());
    }

    /** 估算已构建消息（system + 历史 + 当前问题）占用的 Tokens。 */
    public int estimatePromptTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                total += TokenEstimator.estimate(systemMessage.text());
            } else if (message instanceof AiMessage aiMessage) {
                total += TokenEstimator.estimate(aiMessage.text());
            } else if (message instanceof UserMessage userMessage) {
                for (Content content : userMessage.contents()) {
                    if (content instanceof TextContent textContent) {
                        total += TokenEstimator.estimate(textContent.text());
                    }
                }
            } else if (message instanceof ToolExecutionResultMessage toolMessage) {
                total += TokenEstimator.estimate(toolMessage.text());
            }
        }
        return total;
    }

    /**
     * 单条 chunk 撑满预算时，先用 map-reduce 分段摘要压缩该 chunk，再走常规预算裁剪。
     * 摘要失败或未启用时保持原样，由 {@link RagContextBudgeter} 的截断逻辑兜底。
     */
    private void applyMapReduceIfSaturated(List<EmbeddingMatch<TextSegment>> matches, int budget,
                                           LlmModel model, RagProperties.Context props) {
        if (matches == null || matches.isEmpty() || model == null) {
            return;
        }
        boolean enabled = props == null || !Boolean.FALSE.equals(props.getMapReduceEnabled());
        if (!enabled) {
            return;
        }
        int topIndex = 0;
        for (int i = 1; i < matches.size(); i++) {
            double a = matches.get(i).score() == null ? Double.NEGATIVE_INFINITY : matches.get(i).score();
            double b = matches.get(topIndex).score() == null ? Double.NEGATIVE_INFINITY : matches.get(topIndex).score();
            if (a > b) topIndex = i;
        }
        EmbeddingMatch<TextSegment> top = matches.get(topIndex);
        int chunkTokens = RagContextBudgeter.chunkTokens(top);
        if (!MapReduceChunkSummarizer.shouldApply(chunkTokens, budget)) {
            return;
        }
        int maxSegments = props != null && props.getMapReduceMaxSegments() != null
                ? props.getMapReduceMaxSegments() : 4;
        int segmentTokens = props != null && props.getMapReduceSegmentOutputTokens() != null
                ? props.getMapReduceSegmentOutputTokens() : 200;
        try {
            MapReduceChunkSummarizer.Result reduced = MapReduceChunkSummarizer.summarize(
                    top.embedded().text(), modelCacheManager.getChatModel(model.getId()),
                    maxSegments, segmentTokens, budget);
            if (!reduced.reduced()) {
                log.warn("分段摘要未生效（fallback 到截断）: model={}, chunkTokens={}, budget={}",
                        model.getModelName(), chunkTokens, budget);
                return;
            }
            TextSegment segment = TextSegment.from(reduced.text(), top.embedded().metadata());
            matches.set(topIndex, new EmbeddingMatch<>(top.score(), top.embeddingId(), top.embedding(), segment));
            metrics.recordRagContextBudget(budget, chunkTokens,
                    RagContextBudgeter.chunkTokens(matches.get(topIndex)), 0, false);
            log.info("✓ 分段摘要生效: model={}, 原文 {} tokens, 摘要 {} tokens, 分段 {}",
                    model.getModelName(), chunkTokens,
                    RagContextBudgeter.chunkTokens(matches.get(topIndex)), reduced.segments());
        } catch (Exception e) {
            log.warn("分段摘要失败（fallback 到截断）: model={}, reason={}", model.getModelName(), e.getMessage());
        }
    }
}
