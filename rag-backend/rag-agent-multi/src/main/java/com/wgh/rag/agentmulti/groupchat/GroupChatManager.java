package com.wgh.rag.agentmulti.groupchat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.workflow.engine.ConditionEvaluator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * GroupChatManager —— GroupChat 模式下负责下一个说话 Agent 选择 / 终止判断。
 * <p>
 * pickNextSpeaker 支持 Round Robin 和 Dynamic Router。
 * shouldTerminate 支持：maxRounds / 含 "TERMINATE" / ConditionEvaluator / 连续空回复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupChatManager {

    private final ModelCacheManager modelCacheManager;
    private final ConditionEvaluator conditionEvaluator;
    private final ObjectMapper objectMapper;

    /**
     * 选择下一个说话的 agentId。
     */
    public String pickNextSpeaker(MultiAgentRun run, List<AgentInstance> agents,
                                    List<MessageBus.ChatMessage> globalHistory) {
        if (agents == null || agents.isEmpty()) return null;

        String mode = run.getExecutionMode();
        if ("GROUP_CHAT_ROUND_ROBIN".equals(mode)) {
            int round = run.getCurrentRound() != null ? run.getCurrentRound() : 0;
            int idx = round % agents.size();
            return agents.get(idx).getAgentId();
        }

        // Dynamic Router —— 用 routerModelId 决策；为 null 时 fallback 到第一个 agent.chatModelId
        Long routerModelId = run.getDynamicRouterModelId();
        if (routerModelId == null) {
            routerModelId = agents.get(0).getChatModelId();
        }

        ChatModel model = modelCacheManager.getChatModel(routerModelId);

        StringBuilder agentList = new StringBuilder();
        for (AgentInstance a : agents) {
            agentList.append("- ").append(a.getAgentId()).append(" (").append(a.getName())
                    .append(", role=").append(a.getRole()).append(", goal=")
                    .append(a.getGoal()).append(")\n");
        }

        StringBuilder hist = new StringBuilder();
        int startIdx = Math.max(0, globalHistory.size() - 20);
        for (int i = startIdx; i < globalHistory.size(); i++) {
            MessageBus.ChatMessage m = globalHistory.get(i);
            hist.append("[").append(m.displayName()).append("] ").append(m.content).append("\n");
        }

        String prompt = """
                你是 GroupChat 协调器。
                以下是参与 Agent 列表：
                %s
                
                以下是本轮对话历史（最近 20 条）：
                %s
                
                当前轮次：%d / %d
                请选择下一个应该发言的 Agent。
                返回严格 JSON：{"agentId": "<string>", "reason": "<string>"}
                """.formatted(agentList, hist,
                run.getCurrentRound() == null ? 0 : run.getCurrentRound(),
                run.getMaxRounds() == null ? 10 : run.getMaxRounds());

        try {
            ChatResponse resp = model.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("你是严格的 GroupChat 协调器，只返回 JSON。"),
                            UserMessage.from(prompt)))
                    .build());
            String raw = resp.aiMessage() != null ? resp.aiMessage().text() : "";
            // 解析 JSON
            int left = raw.indexOf('{');
            int right = raw.lastIndexOf('}');
            if (left >= 0 && right > left) {
                Map<String, Object> parsed = objectMapper.readValue(
                        raw.substring(left, right + 1), new TypeReference<>() {});
                Object aid = parsed.get("agentId");
                if (aid != null) {
                    String agentId = String.valueOf(aid);
                    // 校验 agentId 真实存在，LLM 幻觉返回不存在的 id 时降级 Round Robin
                    boolean exists = agents.stream().anyMatch(a -> agentId.equals(a.getAgentId()));
                    if (exists) return agentId;
                    log.warn("Dynamic Router 返回不存在的 agentId={}，fallback 到 Round Robin", agentId);
                }
            }
        } catch (Exception e) {
            log.warn("Dynamic Router 解析失败，fallback 到 Round Robin", e);
        }

        // Fallback —— Round Robin
        int round = run.getCurrentRound() != null ? run.getCurrentRound() : 0;
        return agents.get(round % agents.size()).getAgentId();
    }

    /**
     * 判断是否应该终止 GroupChat。
     */
    public boolean shouldTerminate(MultiAgentRun run, List<MessageBus.ChatMessage> globalHistory) {
        // (1) maxRounds
        int maxRounds = run.getMaxRounds() != null ? run.getMaxRounds() : 10;
        int curRound = run.getCurrentRound() != null ? run.getCurrentRound() : 0;
        if (curRound >= maxRounds) {
            log.info("达到 maxRounds={}，终止", maxRounds);
            return true;
        }

        // (2) 最后一条含 TERMINATE
        if (!globalHistory.isEmpty()) {
            MessageBus.ChatMessage last = globalHistory.get(globalHistory.size() - 1);
            if (last.content != null && last.content.contains("TERMINATE")) {
                log.info("最后一条消息含 TERMINATE，终止");
                return true;
            }
        }

        // (3) terminationCriteria ConditionEvaluator
        if (run.getTerminationCriteria() != null && !run.getTerminationCriteria().isBlank()) {
            try {
                // 把最后几条消息拼成简单的 text 来判断
                String combined = globalHistory.stream()
                        .map(m -> "[" + m.displayName() + "] " + m.content)
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                // ConditionEvaluator 不接受模板变量，我们把变量注入靠字符串匹配
                String expr = run.getTerminationCriteria()
                        .replace("{{lastOutput}}", combined.length() > 500 ? combined.substring(0, 500) + "..." : combined);
                if (conditionEvaluator.evaluate(expr)) {
                    log.info("terminationCriteria 满足，终止");
                    return true;
                }
            } catch (Exception e) {
                log.warn("terminationCriteria 求值异常", e);
            }
        }

        // (4) 最近 3 条都是重复或空
        if (globalHistory.size() >= 3) {
            List<String> last3 = new ArrayList<>();
            for (int i = globalHistory.size() - 3; i < globalHistory.size(); i++) {
                MessageBus.ChatMessage m = globalHistory.get(i);
                last3.add(m.content == null ? "" : m.content.trim());
            }
            boolean allEmpty = last3.stream().allMatch(String::isBlank);
            boolean allSame = last3.stream().distinct().count() == 1 && !last3.get(0).isBlank();
            if (allEmpty || allSame) {
                log.info("最近 3 轮回复全部相同或空，终止");
                return true;
            }
        }

        return false;
    }
}
