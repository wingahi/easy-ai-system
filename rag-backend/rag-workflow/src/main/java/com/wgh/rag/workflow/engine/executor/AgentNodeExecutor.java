package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.constant.WorkflowConstants;

import com.wgh.rag.agent.StepExecutor;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.PlanDTO;
import com.wgh.rag.agent.planner.Planner;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * AGENT 节点 —— Plan-and-Execute 轻量版。
 * <p>
 * 与 AgentOrchestrator 不同，此处不需要持久化 plan/step，不需要 SSE 事件，不需要 HITL 暂停/恢复。
 * 流程：Planner 生成 Plan → 顺序 StepExecutor 执行 → 收集最后一步 finalText 作为 text 输出。
 * <p>
 * 配置项（GraphNode.config）：
 * <ul>
 *   <li>{@code goal} (String, 必填, 支持变量渲染) — Agent 目标</li>
 *   <li>{@code modelId} (Long, 可选) — 执行模型，不传则 rag-config 默认</li>
 *   <li>{@code plannerModelId} (Long, 可选) — Planner 使用的模型，默认同 modelId</li>
 *   <li>{@code maxSteps} (int, 默认 5) — 最大计划步数</li>
 *   <li>{@code timeoutMs} (int, 默认 60000) — 整体执行超时</li>
 *   <li>{@code kbIds} (List<Long>, 可选) — 关联知识库（用于 RAG 步骤）</li>
 * </ul>
 * <p>
 * 输出写入 {@link RunContext#nodeOutputs}：{ text, planSummary, stepCount, status }，
 * 其中 text 字段会被 WorkflowEngine 自动写入 {@code ctx.vars["lastOutput"]} 供下游引用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeExecutor implements NodeExecutor {

    private final Planner planner;
    private final StepExecutor stepExecutor;
    private final VariableResolver variableResolver;

    @Override
    public String supportedType() {
        return WorkflowConstants.NODE_AGENT;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) throws Exception {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        String goalRaw = NodeConfigSupport.str(cfg.get("goal"));
        if (goalRaw.isBlank()) goalRaw = NodeConfigSupport.str(cfg.get("prompt"));
        if (goalRaw.isBlank()) {
            // fallback: 取上一步输出
            Object lastOut = ctx.getVars().get("lastOutput");
            if (lastOut != null && !String.valueOf(lastOut).isBlank()) {
                goalRaw = String.valueOf(lastOut);
            } else {
                throw new IllegalArgumentException("AGENT 节点缺少 goal / prompt 配置");
            }
        }

        String goal = variableResolver.render(goalRaw, ctx);
        Long modelId = NodeConfigSupport.asLong(cfg.get("modelId"));
        Long rawPlannerModelId = NodeConfigSupport.asLong(cfg.get("plannerModelId"));
        final Long plannerModelId = rawPlannerModelId != null ? rawPlannerModelId : modelId;
        int maxSteps = NodeConfigSupport.asInt(cfg.get("maxSteps"), 5);
        int timeoutMs = NodeConfigSupport.asInt(cfg.get("timeoutMs"), 60_000);
        List<Long> kbIds = NodeConfigSupport.asLongList(cfg.get("kbIds"));
        boolean hasKb = !kbIds.isEmpty();

        ExecutorService timeoutPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "wf-agent-" + ThreadLocalRandom.current().nextInt()));
        try {
            Future<NodeResult> future = timeoutPool.submit(() -> doExecute(goal, modelId, plannerModelId, maxSteps, hasKb, kbIds));
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("AGENT 节点超时 goal={} timeoutMs={}", goal, timeoutMs);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("text", "（超时：Agent 在 " + timeoutMs + "ms 内未完成）");
                out.put("status", "TIMEOUT");
                out.put("planSummary", "");
                out.put("stepCount", 0);
                return NodeResult.builder().success(false).output(out).error("Agent timeout").build();
            }
        } finally {
            timeoutPool.shutdownNow();
        }
    }

    private NodeResult doExecute(String goal, Long modelId, Long plannerModelId,
                                 int maxSteps, boolean hasKb, List<Long> kbIds) {
        // 1. Planner 生成计划
        PlanDTO plan;
        try {
            plan = planner.generate(plannerModelId, goal, List.of(), hasKb, maxSteps);
        } catch (Exception e) {
            log.error("AGENT 节点 Planner 失败 goal={}", goal, e);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", "");
            out.put("planSummary", "");
            out.put("stepCount", 0);
            out.put("status", "PLAN_FAILED");
            return NodeResult.builder().success(false).output(out).error("Planner failed: " + e.getMessage()).build();
        }
        log.info("AGENT 节点计划生成: {} 步骤, summary={}", plan.getSteps().size(), plan.getSummary());

        // 2. 顺序执行（简化版：无工具）
        AgentStepResult lastResult = null;
        StringBuilder contextBuilder = new StringBuilder();
        int executedCount = 0;
        for (PlanDTO.StepDTO step : plan.getSteps()) {
            try {
                executedCount++;
                String stepContext = contextBuilder.isEmpty() ? "" :
                        "【前序步骤结果】\n" + contextBuilder.toString() + "\n";
                String stepPrompt = stepContext + "## 目标\n" + goal + "\n\n## 当前步骤\n- " + step.getTitle()
                        + "\n" + (step.getDescription() != null ? step.getDescription() : "")
                        + "\n\n请执行此步骤并给出结果。";

                lastResult = stepExecutor.execute(
                        modelId,
                        stepPrompt,
                        List.of(),         // toolSpecs — 简化版不传工具
                        Map.of(),          // toolRegistry
                        modelId,
                        3,                 // maxToolRounds
                        Boolean.TRUE.equals(step.getRequiresRAG()),
                        step.getDescription() != null ? step.getDescription() : goal,
                        kbIds
                );
                if (lastResult.getResultSummary() != null && !lastResult.getResultSummary().isBlank()) {
                    contextBuilder.append("【").append(step.getTitle()).append("】\n")
                            .append(lastResult.getResultSummary()).append("\n\n");
                }
                if (WorkflowConstants.STATUS_FAILED.equals(lastResult.getStatus())) {
                    log.warn("AGENT 步骤失败 title={} err={}", step.getTitle(), lastResult.getErrorMessage());
                    break;
                }
            } catch (Exception e) {
                log.error("AGENT 步骤异常 title={}", step.getTitle(), e);
                break;
            }
        }

        // 3. 组装输出
        String text = lastResult != null ? (lastResult.getFinalText() != null ? lastResult.getFinalText() : "") : "";
        String status = lastResult != null ? lastResult.getStatus() : "NO_RESULT";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("planSummary", plan.getSummary() != null ? plan.getSummary() : "");
        out.put("stepCount", executedCount);
        out.put("status", status);
        out.put("finalStepTitle", plan.getSteps().isEmpty() ? "" : plan.getSteps().get(plan.getSteps().size() - 1).getTitle());

        boolean success = "SUCCESS".equals(status);
        return NodeResult.builder().success(success).output(out).build();
    }
}
