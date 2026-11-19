package com.wgh.rag.chat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.config.property.RagEvaluationProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * LLM-as-Judge 评估服务
 * 并行调用多个不同厂商的 LLM 作为 judge，评估生成侧指标，并报告 judge 间一致性，避免单次 LLM 打分偏差。
 *
 * <p>指标口径（与 RAGAS 对齐，便于和离线脚本/后续 CI 对比）：
 * <ul>
 *   <li>Faithfulness：回答的原子声明被检索上下文支持的比例</li>
 *   <li>Answer Relevancy：回答对问题的切题程度</li>
 *   <li>Context Precision@K：rank-weighted 平均精度
 *       {@code Σ_{k=1..K}(Precision@k × rel_k) / topK 内相关 chunk 数}，
 *       {@code rel_k} 由 judge 逐 chunk 判定；topK 内无相关 chunk 时为 0（有参考答案时的有效结论）</li>
 *   <li>Context Recall：参考答案的原子声明中，能在检索上下文中找到依据的比例</li>
 * </ul>
 *
 * <p>不可计算约定：无参考答案时 Context Precision/Recall 返回 {@code null} 并标记
 * {@link ContextMetricStatus#NOT_COMPUTABLE_NO_REFERENCE}；参考答案未拆出声明时为
 * {@link ContextMetricStatus#NOT_COMPUTABLE_NO_CLAIMS}；judge 全部失败为
 * {@link ContextMetricStatus#NOT_COMPUTABLE_JUDGE_FAILED}。任何情况下都不以 0 分冒充不可计算。
 */
@Slf4j
@Service
public class LlmJudgeService {

    private final ModelCacheManager modelCacheManager;
    private final ObjectMapper objectMapper;
    private final RagEvaluationProperties properties;

    /** judge 线程池（独立池，避免占用主线程） */
    private final Executor judgeExecutor;

    /** 单次 judge 的上下文 chunk 上限与截断长度，避免超长输入 */
    private static final int MAX_CONTEXT_CHUNKS = 20;
    private static final int MAX_CHUNK_CHARS = 800;
    private static final int MAX_CONTEXT_CHARS = 3000;
    private static final int MAX_ANSWER_CHARS = 2000;
    private static final int MAX_QUERY_CHARS = 500;

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_CONCURRENCY = 4;

    public LlmJudgeService(ModelCacheManager modelCacheManager, ObjectMapper objectMapper,
                           RagEvaluationProperties properties) {
        this.modelCacheManager = modelCacheManager;
        this.objectMapper = objectMapper;
        this.properties = properties;
        int concurrency = properties != null && properties.getJudgeConcurrency() != null
                && properties.getJudgeConcurrency() > 0
                ? properties.getJudgeConcurrency() : DEFAULT_CONCURRENCY;
        // 评测在独立线程池运行，包装后每个任务继承提交线程的 MDC（requestId/traceId）
        this.judgeExecutor = com.wgh.rag.common.log.MdcContext.wrap(
                Executors.newFixedThreadPool(concurrency, r -> {
                    Thread t = new Thread(r, "llm-judge-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                }));
    }

    /**
     * 上下文指标的可用状态：用于区分「真的 0 分」与「不可计算」。
     */
    public enum ContextMetricStatus {
        /** 已计算 */
        COMPUTED,
        /** 未提供参考答案，不可计算 */
        NOT_COMPUTABLE_NO_REFERENCE,
        /** 未提供检索上下文（无 chunk），不可计算 */
        NOT_COMPUTABLE_NO_CONTEXT,
        /** judge 未能从参考答案中拆出任何原子声明，不可计算 */
        NOT_COMPUTABLE_NO_CLAIMS,
        /** judge 调用/解析全部失败，不可计算 */
        NOT_COMPUTABLE_JUDGE_FAILED
    }

    /**
     * 单个 judge 的评分结果（context 指标为 null 表示该 judge 维度不可计算）
     */
    public record JudgeScore(Long modelId, String modelName, String provider,
                             Double faithfulness, Double answerRelevancy,
                             Double contextPrecision, Double contextRecall,
                             String reason, String error) {}

    /**
     * 聚合结果
     */
    public record JudgeResult(Double faithfulness, Double answerRelevancy,
                              Double judgeAgreement, List<JudgeScore> details,
                              Double contextPrecision, Double contextRecall,
                              ContextMetricStatus contextMetricStatus) {}

    /**
     * 异步执行 LLM-as-Judge 评估（无参考答案：Context Precision/Recall 标记为不可计算）
     *
     * @param judgeModels judge 模型列表
     * @param query       用户问题
     * @param context     检索上下文（拼接文本）
     * @param answer      LLM 回答
     */
    public CompletableFuture<JudgeResult> evaluateAsync(List<LlmModel> judgeModels,
                                                        String query, String context, String answer) {
        return evaluateInternal(judgeModels, query, context, null, answer, null);
    }

    /**
     * 异步执行 LLM-as-Judge 评估（带参考答案时额外计算 Context Precision / Context Recall）
     *
     * @param judgeModels     judge 模型列表
     * @param query           用户问题
     * @param contextChunks   检索到的上下文分块（按检索排名顺序，用于逐 chunk 判定相关性）
     * @param answer          LLM 回答
     * @param referenceAnswer 标准答案/参考答案；为空时上下文指标不可计算
     */
    public CompletableFuture<JudgeResult> evaluateWithReferenceAsync(List<LlmModel> judgeModels, String query,
                                                                     List<String> contextChunks, String answer,
                                                                     String referenceAnswer) {
        String context = contextChunks == null ? "" : contextChunks.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        return evaluateInternal(judgeModels, query, context, contextChunks, answer, referenceAnswer);
    }

    private CompletableFuture<JudgeResult> evaluateInternal(List<LlmModel> judgeModels, String query,
                                                            String context, List<String> contextChunks,
                                                            String answer, String referenceAnswer) {
        boolean referenceProvided = referenceAnswer != null && !referenceAnswer.isBlank();
        boolean contextAvailable = contextChunks != null && !contextChunks.isEmpty();
        if (judgeModels == null || judgeModels.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new JudgeResult(null, null, null, List.of(), null, null,
                            !referenceProvided ? ContextMetricStatus.NOT_COMPUTABLE_NO_REFERENCE
                                    : !contextAvailable ? ContextMetricStatus.NOT_COMPUTABLE_NO_CONTEXT
                                    : ContextMetricStatus.NOT_COMPUTABLE_JUDGE_FAILED));
        }

        int timeoutSeconds = judgeTimeoutSeconds();
        List<CompletableFuture<JudgeScore>> futures = judgeModels.stream()
                .map(m -> CompletableFuture.supplyAsync(
                                () -> callJudgeWithRepeats(m, query, context, contextChunks, answer, referenceAnswer),
                                judgeExecutor)
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("Judge {} 调用失败: {}", m.getName(), errorMessage(ex));
                            return errorScore(m, errorMessage(ex));
                        }))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<JudgeScore> scores = futures.stream()
                            .map(CompletableFuture::join)
                            .toList();
                    return aggregate(scores, referenceProvided, contextAvailable);
                });
    }

    /**
     * 调用单个 judge；配置的重复次数 >1 时多次调用并取均值（降低单次打分离散度）
     */
    private JudgeScore callJudgeWithRepeats(LlmModel model, String query, String context,
                                            List<String> contextChunks, String answer, String referenceAnswer) {
        int repeat = properties != null && properties.getRepeatCount() != null
                ? Math.max(1, properties.getRepeatCount()) : 1;
        if (repeat == 1) {
            return callJudge(model, query, context, contextChunks, answer, referenceAnswer);
        }
        List<JudgeScore> runs = new ArrayList<>();
        JudgeScore last = null;
        for (int i = 0; i < repeat; i++) {
            last = callJudge(model, query, context, contextChunks, answer, referenceAnswer);
            if (last.error() == null) {
                runs.add(last);
            }
        }
        if (runs.isEmpty()) {
            return last;
        }
        return averageScores(model, runs);
    }

    /**
     * 调用单个 judge 模型：先评估 Faithfulness / Answer Relevancy；
     * 有参考答案且上下文可用时再评估 Context Precision / Context Recall。
     */
    private JudgeScore callJudge(LlmModel model, String query, String context,
                                 List<String> contextChunks, String answer, String referenceAnswer) {
        try {
            ChatModel chatModel = modelCacheManager.getChatModel(model.getId());
            String raw = chatModel.chat(buildJudgePrompt(query, context, answer));
            JudgeScore base = parseJudgeResponse(model, raw);

            if (!hasText(referenceAnswer) || contextChunks == null || contextChunks.isEmpty()) {
                return base;
            }
            List<String> chunks = limitChunks(contextChunks);
            try {
                String contextRaw = chatModel.chat(buildContextJudgePrompt(query, chunks, referenceAnswer));
                ContextScore contextScore = parseContextResponse(contextRaw, chunks.size());
                return new JudgeScore(base.modelId(), base.modelName(), base.provider(),
                        base.faithfulness(), base.answerRelevancy(),
                        contextScore.precision(), contextScore.recall(),
                        base.reason(), base.error());
            } catch (Exception e) {
                // 上下文指标失败不影响 Faithfulness / Answer Relevancy，也不影响主链路
                log.warn("Judge {} 的上下文指标评估失败: {}", model.getName(), e.getMessage());
                return base;
            }
        } catch (Exception e) {
            log.warn("Judge {} 调用异常: {}", model.getName(), errorMessage(e));
            return errorScore(model, errorMessage(e));
        }
    }

    /**
     * 统一 judge prompt：一次调用同时评估 Faithfulness 与 Answer Relevancy
     */
    private String buildJudgePrompt(String query, String context, String answer) {
        String ctx = truncate(context, MAX_CONTEXT_CHARS);
        String ans = truncate(answer, MAX_ANSWER_CHARS);
        String q = truncate(query, MAX_QUERY_CHARS);

        return """
                你是 RAG 系统的资深评估专家。请基于给定的【问题】【检索上下文】【回答】，从两个维度进行客观评分。
                
                【评分维度】
                1. faithfulness（忠实度）：将回答拆分为原子声明，判断每条声明是否被上下文支持。取值 0-1。
                   - 1.0 = 所有声明均被上下文明确支持
                   - 0.5 = 部分声明被支持，部分存在臆造或超出上下文
                   - 0.0 = 回答完全脱离上下文，或承认"不知道"但上下文实际有答案
                2. answerRelevancy（答案相关性）：回答是否切题、是否直接回应了用户问题。取值 0-1。
                   - 1.0 = 完全切题、直接回答了问题
                   - 0.5 = 部分切题，存在答非所问或冗余
                   - 0.0 = 完全答非所问
                
                【输出要求】
                仅输出 JSON，不要输出其他内容。格式：
                {"faithfulness": 0.85, "answerRelevancy": 0.92, "reason": "一句话说明评分依据"}
                
                【问题】
                %s
                
                【检索上下文】
                %s
                
                【回答】
                %s
                """.formatted(q, ctx, ans);
    }

    /**
     * 上下文指标 judge prompt：逐 chunk 判定相关性 + 从参考答案拆出原子声明并判定是否被上下文支撑
     */
    private String buildContextJudgePrompt(String query, List<String> chunks, String referenceAnswer) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ctx.append('[').append(i + 1).append("] ")
                    .append(truncate(chunks.get(i), MAX_CHUNK_CHARS)).append('\n');
        }
        String ctxText = ctx.length() > MAX_CONTEXT_CHARS
                ? ctx.substring(0, MAX_CONTEXT_CHARS) + "..." : ctx.toString();
        String q = truncate(query, MAX_QUERY_CHARS);
        String ref = truncate(referenceAnswer, MAX_ANSWER_CHARS);

        return """
                你是 RAG 系统的检索上下文评估专家。请判断【检索上下文】对回答【问题】、支撑【参考答案】的价值。
                
                【评估要求】
                1. chunkRelevant：与上面每个带编号的上下文 chunk 一一对应，1=该 chunk 对回答问题或支撑参考答案有用，0=无关。
                2. claims：把【参考答案】拆分为原子声明（每条只包含一个事实），supported=true 表示该声明能在上下文中找到依据。
                3. 不要臆造上下文中不存在的事实。
                
                【输出要求】
                仅输出 JSON，不要输出其他内容。格式：
                {"chunkRelevant": [1, 0, 1], "claims": [{"claim": "声明1", "supported": true}, {"claim": "声明2", "supported": false}], "reason": "一句话说明"}
                
                【问题】
                %s
                
                【参考答案】
                %s
                
                【检索上下文】
                %s
                """.formatted(q, ref, ctxText);
    }

    /**
     * 解析 judge 输出（容错 JSON 提取）
     */
    private JudgeScore parseJudgeResponse(LlmModel model, String raw) {
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            Double faith = node.hasNonNull("faithfulness") ? node.get("faithfulness").asDouble() : null;
            Double rel = node.hasNonNull("answerRelevancy") ? node.get("answerRelevancy").asDouble() : null;
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : "";
            if (faith != null) faith = clamp01(faith);
            if (rel != null) rel = clamp01(rel);
            return new JudgeScore(model.getId(), model.getName(), model.getProvider(),
                    faith, rel, null, null, reason, null);
        } catch (Exception e) {
            log.warn("解析 judge {} 输出失败: {}，raw={}", model.getName(), e.getMessage(),
                    raw == null ? "null" : (raw.length() > 200 ? raw.substring(0, 200) : raw));
            return errorScore(model, "解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析上下文指标 judge 输出，并计算 Context Precision / Context Recall
     */
    private ContextScore parseContextResponse(String raw, int chunkCount) throws Exception {
        JsonNode node = objectMapper.readTree(extractJson(raw));

        List<Boolean> relevantFlags = new ArrayList<>(chunkCount);
        JsonNode relArr = node.get("chunkRelevant");
        for (int i = 0; i < chunkCount; i++) {
            boolean rel = relArr != null && relArr.isArray() && i < relArr.size()
                    && isPositive(relArr.get(i));
            relevantFlags.add(rel);
        }

        int totalClaims = 0;
        int supportedClaims = 0;
        JsonNode claims = node.get("claims");
        if (claims != null && claims.isArray()) {
            for (JsonNode claim : claims) {
                totalClaims++;
                if (isPositive(claim.get("supported"))) supportedClaims++;
            }
        }

        return new ContextScore(calculateContextPrecision(relevantFlags),
                calculateContextRecall(supportedClaims, totalClaims));
    }

    /**
     * Context Precision@K（RAGAS rank-weighted 平均精度）：
     * {@code Σ_{k=1..K}(Precision@k × rel_k) / topK 内相关 chunk 数}。
     *
     * <p>flags 为空返回 null（不可计算）；有 flags 但无相关 chunk 返回 0（有效结论）。
     */
    static Double calculateContextPrecision(List<Boolean> relevantFlags) {
        if (relevantFlags == null || relevantFlags.isEmpty()) return null;
        int relevantCount = 0;
        double sum = 0.0;
        int hits = 0;
        for (int i = 0; i < relevantFlags.size(); i++) {
            if (!Boolean.TRUE.equals(relevantFlags.get(i))) continue;
            relevantCount++;
            hits++;
            sum += hits / (double) (i + 1);
        }
        return relevantCount == 0 ? 0.0 : sum / relevantCount;
    }

    /**
     * Context Recall = 被上下文支撑的声明数 / 声明总数；声明总数为 0 时不可计算返回 null。
     */
    static Double calculateContextRecall(int supportedClaims, int totalClaims) {
        if (totalClaims <= 0) return null;
        return Math.min(1.0, Math.max(0, supportedClaims) / (double) totalClaims);
    }

    /**
     * 从原始文本提取 JSON（处理 ```json ... ``` 包裹、前后多余文本）
     */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.replaceAll("```json", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /**
     * 聚合多个 judge 的分数：各指标均值 + judge 一致性。
     * 一致性 = 1 - 所有有值指标两两绝对差的均值，范围 0-1；只有一个有效 judge 时记为 1。
     */
    private JudgeResult aggregate(List<JudgeScore> scores, boolean referenceProvided, boolean contextAvailable) {
        Double faithAvg = average(scores, JudgeScore::faithfulness);
        Double relAvg = average(scores, JudgeScore::answerRelevancy);
        Double contextPrecision = average(scores, JudgeScore::contextPrecision);
        Double contextRecall = average(scores, JudgeScore::contextRecall);

        List<Double> diffs = new ArrayList<>();
        collectDiffs(scores, JudgeScore::faithfulness, diffs);
        collectDiffs(scores, JudgeScore::answerRelevancy, diffs);
        collectDiffs(scores, JudgeScore::contextPrecision, diffs);
        collectDiffs(scores, JudgeScore::contextRecall, diffs);
        double agreement = diffs.isEmpty() ? 1.0
                : Math.max(0.0, 1.0 - diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        ContextMetricStatus status;
        if (!referenceProvided) {
            status = ContextMetricStatus.NOT_COMPUTABLE_NO_REFERENCE;
        } else if (!contextAvailable) {
            status = ContextMetricStatus.NOT_COMPUTABLE_NO_CONTEXT;
        } else if (contextPrecision != null && contextRecall != null) {
            status = ContextMetricStatus.COMPUTED;
        } else if (contextPrecision != null) {
            status = ContextMetricStatus.NOT_COMPUTABLE_NO_CLAIMS;
        } else {
            status = ContextMetricStatus.NOT_COMPUTABLE_JUDGE_FAILED;
        }

        return new JudgeResult(faithAvg, relAvg, agreement, scores,
                contextPrecision, contextRecall, status);
    }

    private static Double average(List<JudgeScore> scores, Function<JudgeScore, Double> getter) {
        List<Double> values = scores.stream().map(getter).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static void collectDiffs(List<JudgeScore> scores, Function<JudgeScore, Double> getter,
                                     List<Double> out) {
        List<Double> values = scores.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.size() < 2) return;
        for (int i = 0; i < values.size(); i++) {
            for (int j = i + 1; j < values.size(); j++) {
                out.add(Math.abs(values.get(i) - values.get(j)));
            }
        }
    }

    private JudgeScore averageScores(LlmModel model, List<JudgeScore> runs) {
        String reason = runs.stream().map(JudgeScore::reason)
                .filter(LlmJudgeService::hasText).findFirst().orElse("");
        return new JudgeScore(model.getId(), model.getName(), model.getProvider(),
                average(runs, JudgeScore::faithfulness),
                average(runs, JudgeScore::answerRelevancy),
                average(runs, JudgeScore::contextPrecision),
                average(runs, JudgeScore::contextRecall),
                reason, null);
    }

    private JudgeScore errorScore(LlmModel model, String error) {
        return new JudgeScore(model.getId(), model.getName(), model.getProvider(),
                null, null, null, null, null, error);
    }

    /** 异常信息兜底：TimeoutException 等可能没有 message */
    private static String errorMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private int judgeTimeoutSeconds() {
        Integer configured = properties != null ? properties.getJudgeTimeoutSeconds() : null;
        return configured != null && configured > 0 ? configured : DEFAULT_TIMEOUT_SECONDS;
    }

    private static List<String> limitChunks(List<String> chunks) {
        List<String> limited = chunks.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        return limited.size() > MAX_CONTEXT_CHUNKS
                ? limited.subList(0, MAX_CONTEXT_CHUNKS) : limited;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() > maxChars ? text.substring(0, maxChars) + "..." : text;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static boolean isPositive(JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asDouble() > 0;
        String text = node.asText("").trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** 单次上下文指标解析结果 */
    private record ContextScore(Double precision, Double recall) {}
}
