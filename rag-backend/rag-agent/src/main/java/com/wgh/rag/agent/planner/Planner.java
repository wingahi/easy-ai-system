package com.wgh.rag.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.dto.PlanDTO;
import com.wgh.rag.agent.exception.AgentException;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plan-and-Execute 模式的 Planner。
 * 用 LLM 生成结构化的 Plan JSON（无向无环步骤图），由 AgentOrchestrator 消费。
 * 不走 LangChain4j Agentic 模块（不在 1.19.0 稳定版），直接结构化输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Planner {

    private final ModelCacheManager modelCacheManager;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成计划。
     * @param plannerModelId Planner 使用的 LLM（空则继承 ragProperties 默认）
     * @param userGoal 用户原始输入
     * @param toolNames 窗口可用工具名列表
     * @param hasKb 是否关联知识库（影响 requiresRAG 提示）
     * @param maxSteps 计划最大步骤数
     * @return 解析后的 PlanDTO
     */
    public PlanDTO generate(Long plannerModelId, String userGoal,
                            List<String> toolNames, boolean hasKb, int maxSteps) {

        ChatModel model = modelCacheManager.getChatModel(plannerModelId);

        String prompt = buildPrompt(userGoal, toolNames, hasKb, maxSteps);

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build();
            ChatResponse response = model.chat(request);
            String raw = response.aiMessage().text();
            log.info("Planner 原始输出（{} chars）: {}", raw.length(), raw.length() < 500 ? raw : raw.substring(0, 500) + "...");

            // 清理可能的 markdown 代码块标记
            String jsonBody = extractJson(raw);
            PlanDTO plan = objectMapper.readValue(jsonBody, PlanDTO.class);

            // 基本校验
            if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
                throw AgentException.planParseFailed(raw);
            }
            if (plan.getSteps().size() > maxSteps) {
                log.warn("Planner 生成了 {} 步骤，超过 maxSteps={}，截断", plan.getSteps().size(), maxSteps);
                plan.setSteps(plan.getSteps().subList(0, maxSteps));
            }
            plan.setPlanId(plan.getPlanId() != null ? plan.getPlanId() : "plan_" + System.currentTimeMillis());
            return plan;

        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Planner LLM 调用异常", e);
            throw AgentException.planParseFailed(e.getMessage());
        }
    }

    public PlanDTO replan(Long plannerModelId, String userGoal, PlanDTO existingPlan,
                           List<String> toolNames, boolean hasKb, int maxSteps,
                           String remainingStepsHint, String failureContext) {
        ChatModel model = modelCacheManager.getChatModel(plannerModelId);
        String prompt = buildReplanPrompt(userGoal, existingPlan, remainingStepsHint,
                failureContext, toolNames, hasKb, maxSteps);
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build();
            ChatResponse response = model.chat(request);
            String raw = response.aiMessage().text();
            PlanDTO plan = objectMapper.readValue(extractJson(raw), PlanDTO.class);
            if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
                throw AgentException.planParseFailed(raw);
            }
            plan.setPlanId("replan_" + System.currentTimeMillis());
            return plan;
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Planner re-plan 异常", e);
            throw AgentException.planParseFailed(e.getMessage());
        }
    }

    private String buildPrompt(String userGoal, List<String> toolNames, boolean hasKb, int maxSteps) {
        String toolsHint = (toolNames == null || toolNames.isEmpty())
                ? "当前窗口未配置任何工具（toolNames 为空）"
                : "可用工具: " + String.join(", ", toolNames);
        String kbHint = hasKb
                ? "当前窗口已关联知识库，你可以让某些步骤 requiresRAG=true 来检索知识库作为 Observation"
                : "当前窗口未关联知识库，不要使用 requiresRAG=true";

        return """
                你是一个 AI Planner，需要将用户的复杂目标拆解为多步执行计划（Plan-and-Execute 模式）。
                你的输出必须是严格的 JSON 对象，不能有任何 markdown 标记、解释文字或代码块。

                ## 用户目标
                %s

                ## 可用能力
                - %s
                - %s
                - 最多 %d 个步骤（超出会被截断）

                ## 输出格式（严格 JSON）
                {
                  "planId": "plan_时间戳",
                  "summary": "一句话总结计划",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "stepIndex": 1,
                      "title": "步骤标题（简洁）",
                      "description": "详细说明",
                      "dependsOn": [],
                      "requiresTools": ["工具名"],
                      "requiresRAG": false,
                      "outputType": "DATA | ANALYSIS | REPORT | ACTION | FINAL_ANSWER",
                      "hitl": false,
                      "expectedInput": "该步骤需要从前序步骤获得什么（可选）"
                    }
                  ]
                }

                ## 规划原则
                1. 步骤间必须有清晰依赖，不要环路
                2. 最终一个步骤 outputType 必须是 FINAL_ANSWER
                3. 如果步骤需要调用工具，列在 requiresTools
                4. 如果步骤需要人工介入（删除数据、发送邮件、付费等有副作用的操作），hitl=true 并在 description 中说明理由
                5. 步骤保持原子性，一个步骤只做一件事
                """.formatted(userGoal, toolsHint, kbHint, maxSteps);
    }

    private String buildReplanPrompt(String userGoal, PlanDTO existingPlan, String remainingHint,
                                      String failureContext, List<String> toolNames,
                                      boolean hasKb, int maxSteps) {
        String toolsHint = (toolNames == null || toolNames.isEmpty())
                ? "无可用工具" : "可用工具: " + String.join(", ", toolNames);
        return """
                用户目标: %s

                原计划已完成部分:
                %s

                失败/新信息上下文:
                %s

                请重新规划剩余步骤（JSON 格式同首次规划），
                以 FINAL_ANSWER 结尾，最多 %d 步骤。

                %s
                %s
                """.formatted(userGoal, remainingHint, failureContext, maxSteps, toolsHint,
                hasKb ? "已关联知识库" : "未关联知识库");
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        // 去除可能的 markdown 代码块
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        // 找第一个 { 和最后一个 }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }
}
