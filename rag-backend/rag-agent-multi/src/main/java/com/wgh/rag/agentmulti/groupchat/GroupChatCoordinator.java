package com.wgh.rag.agentmulti.groupchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.constant.MultiAgentEvents;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.agentmulti.support.AgentInstanceExecutor;
import com.wgh.rag.agentmulti.support.SseEventSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GroupChatCoordinator —— 多 Agent 轮次对话协调。
 * <p>
 * userProxy 先广播用户 Goal → 循环（pickNextSpeaker → execute → broadcast）→ 可选 summarizer 汇总 → 产出最终答复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupChatCoordinator {

    private final MultiAgentService service;
    private final AgentInstanceExecutor agentExecutor;
    private final GroupChatManager manager;
    private final SseEventSender sse;
    private final ObjectMapper objectMapper;

    public void execute(MultiAgentRun run, List<AgentInstance> agents,
                        MultiAgentContext ctx, SseEmitter emitter) {
        MessageBus bus = new MessageBus(agents, emitter);

        // userProxy 先广播用户 Goal
        bus.broadcast("user", MessageBus.ChatMessage.user(run.getUserGoal()));

        int maxRounds = run.getMaxRounds() != null ? run.getMaxRounds() : 10;
        int round = run.getCurrentRound() != null ? run.getCurrentRound() : 0;

        try {
            while (round < maxRounds && !"CANCELLED".equals(run.getStatus())) {
                run.setCurrentRound(round);

                // 终止判断
                if (manager.shouldTerminate(run, bus.getAllHistory())) break;

                // 选下一个发言 Agent
                String nextAgentId = manager.pickNextSpeaker(run, agents, bus.getAllHistory());
                if (nextAgentId == null) break;
                AgentInstance speaker = bus.getAgent(nextAgentId);
                if (speaker == null) break;

                // HITL
                if (speaker.isHitlRequired()) {
                    String hitlPrompt = speaker.getHitlPrompt() != null
                            ? speaker.getHitlPrompt()
                            : "请确认或补充输入给 " + speaker.getName();
                    MultiAgentStep step = new MultiAgentStep();
                    step.setRunId(run.getRunId());
                    step.setAgentId(speaker.getAgentId());
                    step.setStepIndex(round);
                    step.setStatus("HITL_PENDING");
                    step.setHitlExpectedInput(hitlPrompt);
                    step.setStartTime(LocalDateTime.now());
                    service.insertStep(step);

                    run.setStatus("PAUSED");
                    service.saveRun(run);
                    sse.send(emitter, MultiAgentEvents.AGENT_HITL_PENDING,
                            "runId", run.getRunId(),
                            "agentId", speaker.getAgentId(),
                            "prompt", hitlPrompt);
                    throw new MultiAgentRuntime.PauseSignal("HITL 暂停（GroupChat speaker=" + nextAgentId + "）");
                }

                sse.send(emitter, MultiAgentEvents.AGENT_TURN_START,
                        "runId", run.getRunId(),
                        "agentId", speaker.getAgentId(),
                        "agentName", speaker.getName(),
                        "round", round);

                // 用 bus 里该 agent 的历史拼对话上下文（简化：直接 userGoal 作为输入）
                AgentInstanceResult result = agentExecutor.execute(
                        speaker,
                        buildGroupChatInput(speaker, run.getUserGoal(), bus.getHistory(nextAgentId)),
                        ctx, emitter);

                bus.broadcast(speaker.getAgentId(),
                        MessageBus.ChatMessage.assistant(speaker.getAgentId(), result.getOutput()));

                ctx.put(speaker.getAgentId() + ".text", result.getOutput());
                persistStep(run, speaker, result, round);

                sse.send(emitter, MultiAgentEvents.AGENT_TURN_END,
                        "runId", run.getRunId(),
                        "agentId", speaker.getAgentId(),
                        "round", round,
                        "output", result.getOutput());

                round++;
                run.setCurrentRound(round);
                service.saveRun(run);
            }
        } catch (MultiAgentRuntime.PauseSignal pause) {
            throw pause;
        } catch (Exception e) {
            log.error("GroupChat 执行异常", e);
            service.finishRun(run, "FAILED", e.getMessage());
            return;
        }

        // Summarizer 汇总
        String finalAnswer;
        if (run.getSummarizerAgentId() != null && !run.getSummarizerAgentId().isBlank()) {
            AgentInstance summarizer = bus.getAgent(run.getSummarizerAgentId());
            if (summarizer != null) {
                String summaryInput = """
                        请汇总以上 GroupChat 对话，给出一份面向用户的最终答复。
                        对话历史摘要：
                        %s
                        """.formatted(buildHistorySummary(bus.getAllHistory()));
                AgentInstanceResult summaryResult = agentExecutor.execute(
                        summarizer, summaryInput, ctx, emitter);
                finalAnswer = summaryResult.getOutput();
            } else {
                finalAnswer = extractLastAgentMessage(bus.getAllHistory());
            }
        } else {
            finalAnswer = extractLastAgentMessage(bus.getAllHistory());
        }

        run.setOutputText(finalAnswer);
        service.finishRun(run, "COMPLETED", null);
        sse.send(emitter, MultiAgentEvents.MULTI_AGENT_COMPLETE,
                "runId", run.getRunId(),
                "output", finalAnswer,
                "rounds", round,
                "durationMs", run.getDurationMs());
    }

    /** GroupChat 的 HITL 恢复 —— 把 userInput 广播成一条用户消息再继续 */
    public void resume(MultiAgentRun run, List<AgentInstance> agents,
                       MultiAgentContext ctx, String userInput, SseEmitter emitter) {
        MessageBus bus = new MessageBus(agents, emitter);
        // 广播用户回复
        bus.broadcast("user", MessageBus.ChatMessage.user(userInput));
        execute(run, agents, ctx, emitter);
    }

    // ==================== 辅助 ====================

    private String buildGroupChatInput(AgentInstance speaker, String userGoal,
                                        List<MessageBus.ChatMessage> agentHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 会话上下文\n");
        sb.append("原始用户目标：").append(userGoal == null ? "" : userGoal).append("\n\n");
        if (agentHistory != null && !agentHistory.isEmpty()) {
            sb.append("## 对话历史（最近 10 条）\n");
            int start = Math.max(0, agentHistory.size() - 10);
            for (int i = start; i < agentHistory.size(); i++) {
                MessageBus.ChatMessage m = agentHistory.get(i);
                sb.append("[").append(m.displayName()).append("] ").append(m.content).append("\n");
            }
            sb.append("\n");
        }
        sb.append("## 你的角色\n");
        sb.append("你是 ").append(speaker.getName()).append(" (").append(speaker.getRole()).append(")。\n");
        if (speaker.getGoal() != null) sb.append("你的具体目标：").append(speaker.getGoal()).append("\n");
        sb.append("\n请给出你的下一步发言（面向其他 Agent 或用户）。");
        return sb.toString();
    }

    private String buildHistorySummary(List<MessageBus.ChatMessage> history) {
        if (history == null || history.isEmpty()) return "";
        int start = Math.max(0, history.size() - 30);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            MessageBus.ChatMessage m = history.get(i);
            sb.append("[").append(m.displayName()).append("] ").append(m.content).append("\n");
        }
        return sb.toString();
    }

    private String extractLastAgentMessage(List<MessageBus.ChatMessage> history) {
        if (history == null) return "";
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageBus.ChatMessage m = history.get(i);
            if (m.role == MessageBus.ChatMessage.Role.ASSISTANT
                    && m.fromAgentId != null && !"user".equals(m.fromAgentId)) {
                return m.content != null ? m.content : "";
            }
        }
        return "";
    }

    private void persistStep(MultiAgentRun run, AgentInstance agent,
                              AgentInstanceResult result, int round) {
        MultiAgentStep step = new MultiAgentStep();
        step.setRunId(run.getRunId());
        step.setAgentId(agent.getAgentId());
        step.setStepIndex(round);
        step.setRound(round);
        step.setStatus("SUCCESS");
        step.setRole(agent.getRole());
        step.setOutputText(result.getOutput());
        step.setToolCallsJson(toJson(result.getToolInvocations()));
        step.setRagSourcesJson(toJson(result.getRagSources()));
        step.setReflectionScore(result.getReflectionScore());
        step.setReflectionFeedback(result.getReflectionFeedback());
        step.setThought(result.getThought());
        step.setResultSummary(result.getOutput() != null && result.getOutput().length() > 500
                ? result.getOutput().substring(0, 500) + "..." : result.getOutput());
        step.setStartTime(LocalDateTime.now().minusNanos(
                (result.getDurationMs() != null ? result.getDurationMs() : 0) * 1_000_000));
        step.setEndTime(LocalDateTime.now());
        step.setDurationMs(result.getDurationMs());
        step.setTokenCount(result.getTokenCount());
        service.insertStep(step);
    }

    private String toJson(Object v) {
        try { return objectMapper.writeValueAsString(v); }
        catch (Exception e) { return "[]"; }
    }
}
