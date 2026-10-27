package com.wgh.rag.chat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * LLM-as-Judge 评估服务
 * 并行调用多个不同厂商的 LLM 作为 judge，评估 Faithfulness 与 Answer Relevancy，
 * 并报告 judge 间一致性，避免单次 LLM 打分偏差。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmJudgeService {

    private final ModelCacheManager modelCacheManager;
    private final ObjectMapper objectMapper;

    /** judge 线程池（独立池，避免占用主线程） */
    private final ExecutorService judgeExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "llm-judge-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    private static final int JUDGE_TIMEOUT_SECONDS = 30;

    /**
     * 单个 judge 的评分结果
     */
    public record JudgeScore(Long modelId, String modelName, String provider,
                             Double faithfulness, Double answerRelevancy,
                             String reason, String error) {}

    /**
     * 聚合结果
     */
    public record JudgeResult(Double faithfulness, Double answerRelevancy,
                              Double judgeAgreement, List<JudgeScore> details) {}

    /**
     * 异步执行 LLM-as-Judge 评估
     *
     * @param judgeModels judge 模型列表
     * @param query       用户问题
     * @param context     检索上下文
     * @param answer      LLM 回答
     * @return CompletableFuture，完成时返回聚合结果；若全部失败返回空 details
     */
    public CompletableFuture<JudgeResult> evaluateAsync(List<LlmModel> judgeModels,
                                                         String query, String context, String answer) {
        if (judgeModels == null || judgeModels.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new JudgeResult(null, null, null, List.of()));
        }

        List<CompletableFuture<JudgeScore>> futures = judgeModels.stream()
                .map(m -> CompletableFuture.supplyAsync(
                        () -> callJudge(m, query, context, answer), judgeExecutor)
                        .orTimeout(JUDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("Judge {} 调用失败: {}", m.getName(), ex.getMessage());
                            return new JudgeScore(m.getId(), m.getName(), m.getProvider(),
                                    null, null, null, ex.getMessage());
                        }))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<JudgeScore> scores = futures.stream()
                            .map(CompletableFuture::join)
                            .toList();
                    return aggregate(scores);
                });
    }

    /**
     * 调用单个 judge 模型
     */
    private JudgeScore callJudge(LlmModel model, String query, String context, String answer) {
        String prompt = buildJudgePrompt(query, context, answer);
        try {
            ChatModel chatModel = modelCacheManager.getChatModel(model.getId());
            String raw = chatModel.chat(prompt);
            return parseJudgeResponse(model, raw);
        } catch (Exception e) {
            log.warn("Judge {} 调用异常: {}", model.getName(), e.getMessage());
            return new JudgeScore(model.getId(), model.getName(), model.getProvider(),
                    null, null, null, e.getMessage());
        }
    }

    /**
     * 统一 judge prompt：一次调用同时评估 Faithfulness 与 Answer Relevancy
     */
    private String buildJudgePrompt(String query, String context, String answer) {
        // 限制长度，避免超长 context 打爆 judge
        String ctx = context.length() > 3000 ? context.substring(0, 3000) + "..." : context;
        String ans = answer.length() > 2000 ? answer.substring(0, 2000) + "..." : answer;
        String q = query.length() > 500 ? query.substring(0, 500) + "..." : query;

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
     * 解析 judge 输出（容错 JSON 提取）
     */
    private JudgeScore parseJudgeResponse(LlmModel model, String raw) {
        try {
            // 从原始输出中提取 JSON 块
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            Double faith = node.hasNonNull("faithfulness") ? node.get("faithfulness").asDouble() : null;
            Double rel = node.hasNonNull("answerRelevancy") ? node.get("answerRelevancy").asDouble() : null;
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : "";
            // 裁剪到 0-1
            if (faith != null) faith = Math.max(0, Math.min(1, faith));
            if (rel != null) rel = Math.max(0, Math.min(1, rel));
            return new JudgeScore(model.getId(), model.getName(), model.getProvider(),
                    faith, rel, reason, null);
        } catch (Exception e) {
            log.warn("解析 judge {} 输出失败: {}，raw={}", model.getName(), e.getMessage(),
                    raw.length() > 200 ? raw.substring(0, 200) : raw);
            return new JudgeScore(model.getId(), model.getName(), model.getProvider(),
                    null, null, null, "解析失败: " + e.getMessage());
        }
    }

    /**
     * 从原始文本提取 JSON（处理 ```json ... ``` 包裹、前后多余文本）
     */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        // 去除 markdown 代码块标记
        String s = raw.replaceAll("```json", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /**
     * 聚合多个 judge 的分数：平均值 + judge 一致性
     * 一致性 = 1 - 平均绝对差（两两 |diff| 的均值），范围 0-1
     */
    private JudgeResult aggregate(List<JudgeScore> scores) {
        List<JudgeScore> valid = scores.stream()
                .filter(s -> s.faithfulness() != null && s.answerRelevancy() != null)
                .toList();
        if (valid.isEmpty()) {
            return new JudgeResult(null, null, null, scores);
        }

        double faithAvg = valid.stream().mapToDouble(JudgeScore::faithfulness).average().orElse(0);
        double relAvg = valid.stream().mapToDouble(JudgeScore::answerRelevancy).average().orElse(0);

        // 计算 judge 一致性：faithfulness 与 answerRelevancy 各自的 |diff| 均值，再取平均
        double agreement = 1.0;
        if (valid.size() >= 2) {
            List<Double> diffs = new ArrayList<>();
            for (int i = 0; i < valid.size(); i++) {
                for (int j = i + 1; j < valid.size(); j++) {
                    diffs.add(Math.abs(valid.get(i).faithfulness() - valid.get(j).faithfulness()));
                    diffs.add(Math.abs(valid.get(i).answerRelevancy() - valid.get(j).answerRelevancy()));
                }
            }
            double avgDiff = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            agreement = Math.max(0.0, 1.0 - avgDiff);
        }

        return new JudgeResult(faithAvg, relAvg, agreement, scores);
    }
}
