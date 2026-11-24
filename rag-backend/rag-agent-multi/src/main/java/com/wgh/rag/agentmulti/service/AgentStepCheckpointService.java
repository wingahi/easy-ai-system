package com.wgh.rag.agentmulti.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstanceResult;
import com.wgh.rag.agentmulti.dto.AgentResultCard;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 多 Agent 步骤检查点：worker 开始/完成落库、恢复时按 (runId, stepKey) 跳过已完成步骤。
 *
 * <p>stepKey/nodeId/iteration 等恢复元数据写入现有 {@code wgh_m_agent_step.input_context} JSONB，
 * 不新增表或列。CONDITION/LOOP 的决策也作为内部轨迹行（agent_id=__TRACE__）持久化，
 * 对外步骤查询会过滤这些行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStepCheckpointService {

    public static final String TRACE_AGENT_ID = "__TRACE__";

    private final MultiAgentStepMapper stepMapper;
    private final ObjectMapper objectMapper;

    /** 加载某次运行的全部步骤（含内部轨迹行），构建 stepKey 索引。 */
    @Transactional(readOnly = true)
    public CheckpointSession open(String runId) {
        List<MultiAgentStep> steps = stepMapper.selectList(new LambdaQueryWrapper<MultiAgentStep>()
                .eq(MultiAgentStep::getRunId, runId)
                .orderByAsc(MultiAgentStep::getStepIndex));
        return new CheckpointSession(runId, steps, objectMapper);
    }

    /**
     * 步骤开始：已完成返回 skip；未完成复用原行并置 RUNNING；不存在则插入 RUNNING。
     */
    @Transactional
    public StepDecision begin(CheckpointSession session, String stepKey, String nodeId, String agentId,
                              String role, Integer round, String input) {
        MultiAgentStep existing = session.byKey.get(stepKey);
        if (existing != null) {
            if (isSuccess(existing)) {
                return new StepDecision(existing, true);
            }
            existing.setStatus("RUNNING");
            existing.setStartTime(LocalDateTime.now());
            existing.setEndTime(null);
            existing.setDurationMs(null);
            stepMapper.updateById(existing);
            return new StepDecision(existing, false);
        }

        MultiAgentStep step = new MultiAgentStep();
        step.setRunId(session.runId);
        step.setAgentId(agentId);
        step.setStepIndex(session.nextIndex.getAndIncrement());
        step.setStatus("RUNNING");
        step.setRole(role);
        step.setRound(round);
        step.setInputContext(toJson(meta(stepKey, nodeId, agentId, round, input)));
        step.setStartTime(LocalDateTime.now());
        stepMapper.insert(step);
        session.byKey.put(stepKey, step);
        session.steps.add(step);
        return new StepDecision(step, false);
    }

    /** 步骤完成：按主键更新，不新增行。 */
    @Transactional
    public void complete(CheckpointSession session, MultiAgentStep step, AgentInstanceResult result) {
        if (step == null || result == null) return;
        step.setStatus("SUCCESS");
        step.setOutputText(result.getOutput());
        step.setThought(result.getThought());
        step.setToolCallsJson(toJson(result.getToolInvocations()));
        step.setRagSourcesJson(toJson(result.getRagSources()));
        step.setReflectionScore(result.getReflectionScore());
        step.setReflectionFeedback(result.getReflectionFeedback());
        step.setTokenCount(result.getTokenCount());
        step.setEndTime(LocalDateTime.now());
        step.setDurationMs(result.getDurationMs() != null ? result.getDurationMs()
                : step.getStartTime() == null ? 0L
                : Duration.between(step.getStartTime(), step.getEndTime()).toMillis());
        if (result.getCard() != null) {
            mergeCardIntoContext(step, result.getCard());
        }
        stepMapper.updateById(step);
    }

    /** 步骤失败：按主键更新 FAILED，不新增行。 */
    @Transactional
    public void fail(CheckpointSession session, MultiAgentStep step, String error) {
        if (step == null) return;
        step.setStatus("FAILED");
        step.setResultSummary(error == null ? "执行失败" : "执行中断: " + error);
        step.setEndTime(LocalDateTime.now());
        if (step.getStartTime() != null) {
            step.setDurationMs(Duration.between(step.getStartTime(), step.getEndTime()).toMillis());
        }
        stepMapper.updateById(step);
    }

    /** 读取已持久化的决策轨迹；不存在返回 null。 */
    public Map<String, Object> traceDecision(CheckpointSession session, String stepKey) {
        MultiAgentStep step = session.byKey.get(stepKey);
        if (step == null || !TRACE_AGENT_ID.equals(step.getAgentId())) return null;
        Map<String, Object> ctx = parse(step.getInputContext());
        Object decision = ctx.get("decision");
        return decision instanceof Map<?, ?> m
                ? m.entrySet().stream().collect(Collectors.toMap(
                        e -> String.valueOf(e.getKey()), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new))
                : null;
    }

    /** 写入决策轨迹（内部行，不参与对外步骤展示）；已存在则不重复插入。 */
    @Transactional
    public void saveTrace(CheckpointSession session, String stepKey, String nodeId, String kind,
                          Integer round, Map<String, Object> decision) {
        if (session.byKey.containsKey(stepKey)) return;
        MultiAgentStep step = new MultiAgentStep();
        step.setRunId(session.runId);
        step.setAgentId(TRACE_AGENT_ID);
        step.setStepIndex(session.nextIndex.getAndIncrement());
        step.setStatus("SUCCESS");
        step.setRole(kind);
        step.setRound(round);
        Map<String, Object> ctx = meta(stepKey, nodeId, null, round, null);
        ctx.put("kind", kind);
        ctx.put("decision", decision);
        step.setInputContext(toJson(ctx));
        step.setStartTime(LocalDateTime.now());
        step.setEndTime(step.getStartTime());
        step.setDurationMs(0L);
        stepMapper.insert(step);
        session.byKey.put(stepKey, step);
        session.steps.add(step);
    }

    /**
     * 用已完成步骤回填 MultiAgentContext：nodeId.text / agentId.text / lastOutput / 卡片。
     * 只回填 SUCCESS 业务行，内部轨迹行不进入上下文。
     */
    public void restoreContext(CheckpointSession session, MultiAgentContext ctx) {
        String lastOutput = null;
        for (MultiAgentStep step : session.steps) {
            if (TRACE_AGENT_ID.equals(step.getAgentId()) || !isSuccess(step)) continue;
            Map<String, Object> meta = parse(step.getInputContext());
            String output = step.getOutputText();
            if (output == null) output = "";
            String nodeId = str(meta.get("nodeId"));
            String agentId = str(meta.get("agentId"));
            String stepKey = str(meta.get("stepKey"));
            if (nodeId != null) ctx.put(nodeId + ".text", output);
            if (agentId != null) ctx.put(agentId + ".text", output);
            if (stepKey != null && stepKey.startsWith("stage:manager_plan")) {
                ctx.blackboard().put("plan.manager", output);
            }
            Object cardObj = meta.get("card");
            if (cardObj != null) {
                AgentResultCard card = objectMapper.convertValue(cardObj, AgentResultCard.class);
                if (agentId != null) {
                    ctx.blackboard().put("card." + agentId, card);
                    ctx.put(agentId + ".card",
                            com.wgh.rag.agentmulti.support.AgentResultCardBuilder.format(card));
                }
            }
            if (!output.isBlank()) lastOutput = output;
        }
        if (lastOutput != null) ctx.setVars("lastOutput", lastOutput);
    }

    public boolean isCompleted(CheckpointSession session, String stepKey) {
        MultiAgentStep step = session.byKey.get(stepKey);
        return step != null && isSuccess(step);
    }

    private boolean isSuccess(MultiAgentStep step) {
        return "SUCCESS".equals(step.getStatus());
    }

    private Map<String, Object> meta(String stepKey, String nodeId, String agentId,
                                     Integer round, String input) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepKey", stepKey);
        if (nodeId != null) m.put("nodeId", nodeId);
        if (agentId != null) m.put("agentId", agentId);
        if (round != null) m.put("round", round);
        if (input != null) m.put("input", input);
        return m;
    }

    private void mergeCardIntoContext(MultiAgentStep step, AgentResultCard card) {
        Map<String, Object> ctx = parse(step.getInputContext());
        ctx.put("card", card);
        step.setInputContext(toJson(ctx));
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("检查点 JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("检查点 JSON 解析失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record StepDecision(MultiAgentStep step, boolean skip) {
    }

    /** 一次运行的检查点索引；并行执行时由 ConcurrentHashMap + AtomicInteger 保证线程安全。 */
    public static final class CheckpointSession {
        private final String runId;
        private final Map<String, MultiAgentStep> byKey = new ConcurrentHashMap<>();
        private final List<MultiAgentStep> steps = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger nextIndex;

        private CheckpointSession(String runId, List<MultiAgentStep> steps, ObjectMapper objectMapper) {
            this.runId = runId;
            this.steps.addAll(steps);
            int max = 0;
            for (MultiAgentStep step : steps) {
                Map<String, Object> ctx = parseStepKey(step, objectMapper);
                Object key = ctx.get("stepKey");
                if (key != null) byKey.put(String.valueOf(key), step);
                if (step.getStepIndex() != null) max = Math.max(max, step.getStepIndex());
            }
            this.nextIndex = new AtomicInteger(max + 1);
        }

        private static Map<String, Object> parseStepKey(MultiAgentStep step, ObjectMapper objectMapper) {
            if (step.getInputContext() == null || step.getInputContext().isBlank()) return Map.of();
            try {
                return objectMapper.readValue(step.getInputContext(),
                        new TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                return Map.of();
            }
        }

        public String runId() {
            return runId;
        }

        public List<MultiAgentStep> steps() {
            return steps;
        }

        public MultiAgentStep get(String stepKey) {
            return byKey.get(stepKey);
        }

        public int nextIndex() {
            return nextIndex.getAndIncrement();
        }
    }
}
