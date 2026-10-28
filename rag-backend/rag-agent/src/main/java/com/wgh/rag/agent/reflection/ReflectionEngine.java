package com.wgh.rag.agent.reflection;

import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-step Reflection 评估引擎。
 * 评估维度：step 产出是否满足 step 的 stated goal（而非最终答案的 Faithfulness）。
 * 输出：分数 0-10 + 改进反馈。
 *
 * 不依赖 rag-chat 模块，底层复用 ModelCacheManager + ChatLanguageModel。
 * 与 rag-chat 的 LlmJudgeService 是两个独立的评估层（LlmJudgeService 评估最终答案，ReflectionEngine 评估单步骤）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionEngine {

    private final ModelCacheManager modelCacheManager;

    /**
     * 对一个步骤的产出做 Reflection 评估。
     * @param stepGoal 步骤目标标题（Planner 生成）
     * @param stepOutput StepExecutor 实际产出的 finalText
     * @return Pair<分数 0-10, 反馈文本>
     */
    public Map.Entry<Double, String> evaluate(String stepGoal, String stepOutput) {
        return evaluate(stepGoal, stepOutput, null);
    }

    public Map.Entry<Double, String> evaluate(String stepGoal, String stepOutput, Long criticModelId) {
        // null criticModelId → 无法评估，降级为默认通过（而非假 8 分 + "评估异常"）
        if (criticModelId == null) {
            log.warn("Reflection criticModelId 未配置，跳过 stepId [{}] 的反思评估，默认通过",
                    stepGoal.length() > 40 ? stepGoal.substring(0, 40) : stepGoal);
            return Map.entry(7.0, "未配置评估模型（criticModelId 为空），默认通过");
        }
        try {
            ChatModel model = modelCacheManager.getChatModel(criticModelId);
            if (stepOutput == null || stepOutput.isBlank()) {
                return Map.entry(0.0, "步骤产出为空，完全没有完成任务");
            }

            String prompt = """
                    你是一个 AI Agent 自我反思组件。请评估一个 Agent 步骤的产出质量。

                    ## 步骤目标
                    %s

                    ## 步骤产出（Agent 实际回答）
                    %s

                    ## 评估维度
                    1. 目标覆盖度 (0-3)：产出是否回答了步骤目标要求的内容
                    2. 准确性 (0-3)：产出中的事实/数据/结论是否正确（若无法判断则给 2 分）
                    3. 完整性 (0-2)：是否覆盖了目标的所有要点
                    4. 可用性 (0-2)：产出格式/内容能否被后续步骤直接使用

                    ## 输出格式（严格 JSON）
                    {"score": <0-10>, "feedback": "具体改进建议（1-2 句）"}

                    评分标准：
                    - 9-10: 完美覆盖目标，准确无错，格式清晰
                    - 7-8: 基本完成，有小瑕疵但不影响后续
                    - 4-6: 部分完成，有明显缺失或错误，需要改进
                    - 0-3: 严重偏离或完全错误，必须重试
                    """.formatted(
                            stepGoal.length() > 500 ? stepGoal.substring(0, 500) + "..." : stepGoal,
                            stepOutput.length() > 2000 ? stepOutput.substring(0, 2000) + "..." : stepOutput);

            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build());
            String raw = response.aiMessage().text();
            Map.Entry<Double, String> parsed = parseScore(raw);
            log.info("Reflection: goal={}, score={}", stepGoal.substring(0, Math.min(40, stepGoal.length())), parsed.getKey());
            return parsed;

        } catch (Exception e) {
            // 打完整 stack trace，下次一眼看出根因（不再吞异常只打 message）
            log.error("Reflection 评估异常, criticModelId={}, stepGoal={}",
                    criticModelId,
                    stepGoal.length() > 60 ? stepGoal.substring(0, 60) + "..." : stepGoal,
                    e);
            return Map.entry(5.0, "反思评估异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private Map.Entry<Double, String> parseScore(String raw) {
        if (raw == null) return Map.entry(5.0, null);

        // 尝试找 JSON
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        String json = (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;

        try {
            // 简单正则提取 score 和 feedback
            Pattern scoreP = Pattern.compile("\"score\"\\s*:\\s*([\\d.]+)");
            Pattern fbP = Pattern.compile("\"feedback\"\\s*:\\s*\"([^\"]*?)\"");
            Matcher sm = scoreP.matcher(json);
            Matcher fm = fbP.matcher(json);

            double score = sm.find() ? Double.parseDouble(sm.group(1)) : 5.0;
            String feedback = fm.find() ? fm.group(1) : null;

            score = Math.max(0, Math.min(10, score));
            return Map.entry(score, feedback);
        } catch (Exception e) {
            // 解析失败 → 用正则直接找数字
            Matcher m = Pattern.compile("([0-9]|10)\\s*分|score.*?([0-9]|10)", Pattern.CASE_INSENSITIVE).matcher(raw);
            if (m.find()) {
                double score = Double.parseDouble(m.group(1) != null ? m.group(1) : m.group(2));
                return Map.entry(score, raw.length() > 100 ? raw.substring(0, 100) : raw);
            }
            return Map.entry(5.0, null);
        }
    }
}
