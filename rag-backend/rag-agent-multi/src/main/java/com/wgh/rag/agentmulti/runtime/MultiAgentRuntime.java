package com.wgh.rag.agentmulti.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.constant.MultiAgentEvents;
import com.wgh.rag.agentmulti.context.MultiAgentContext;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.dto.DAGGraph;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.agentmulti.support.SseEventSender;
import com.wgh.rag.agentmulti.crew.CrewCoordinator;
import com.wgh.rag.agentmulti.graph.GraphCoordinator;
import com.wgh.rag.agentmulti.groupchat.GroupChatCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 多 Agent 运行时主入口 —— 创建 / 路由 / 恢复 / 取消多 Agent 协作。
 * <p>
 * 按 executionMode 分发到不同 Coordinator：
 * <ul>
 *   <li>CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL → CrewCoordinator</li>
 *   <li>GRAPH_ROUTED → GraphCoordinator</li>
 *   <li>GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER → GroupChatCoordinator</li>
 * </ul>
 * <p>
 * 持久化操作委托给 {@link MultiAgentService}，避免与 Coordinator 的循环依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiAgentRuntime {

    private final MultiAgentRunMapper runMapper;
    private final MultiAgentStepMapper stepMapper;
    private final ObjectMapper objectMapper;
    private final SseEventSender sse;

    private final MultiAgentService multiAgentService;

    private final CrewCoordinator crewCoordinator;
    private final GraphCoordinator graphCoordinator;
    private final GroupChatCoordinator groupChatCoordinator;

    /**
     * 执行一次多 Agent 运行。返回 runId。
     */
    public String execute(String runId, SseEmitter emitter) {
        MultiAgentRun run = loadRun(runId);
        if (run == null) {
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, Map.of("runId", runId, "error", "Run 不存在"));
            return runId;
        }

        List<AgentInstance> agents = parseAgents(run.getAgentsJson());

        sse.send(emitter, MultiAgentEvents.MULTI_AGENT_START,
                "runId", run.getRunId(),
                "executionMode", run.getExecutionMode(),
                "agentCount", agents.size(),
                "userGoal", run.getUserGoal());

        // 状态置为 RUNNING
        run.setStatus("RUNNING");
        run.setStartTime(run.getStartTime() != null ? run.getStartTime() : LocalDateTime.now());
        runMapper.updateById(run);

        // 构建 ctx
        MultiAgentContext ctx = new MultiAgentContext(objectMapper);
        ctx.put("runId", run.getRunId());
        ctx.put("input", run.getUserGoal() == null ? "" : run.getUserGoal());
        ctx.setVars("input", run.getUserGoal() == null ? "" : run.getUserGoal());

        try {
            switch (run.getExecutionMode() == null ? "" : run.getExecutionMode()) {
                case "CREW_SEQUENTIAL", "CREW_HIERARCHICAL", "CREW_PARALLEL" -> {
                    crewCoordinator.execute(run, agents, ctx, emitter);
                }
                case "GRAPH_ROUTED" -> {
                    DAGGraph graph = parseGraphFromCrewConfig(run);
                    graphCoordinator.execute(run, agents, graph, ctx, emitter);
                }
                case "GROUP_CHAT_ROUND_ROBIN", "GROUP_CHAT_DYNAMIC_ROUTER" -> {
                    groupChatCoordinator.execute(run, agents, ctx, emitter);
                }
                default -> throw new IllegalStateException("未知 executionMode: " + run.getExecutionMode());
            }

            // 兜底：如果 Coordinator 没设置终态
            if (runMapper.selectById(run.getId()) != null) {
                MultiAgentRun latest = runMapper.selectById(run.getId());
                if ("RUNNING".equals(latest.getStatus())) {
                    finishRun(latest, "COMPLETED", null);
                }
            }
        } catch (PauseSignal pause) {
            // HITL —— run.status 已由 coordinator 置为 PAUSED
            log.info("run {} 进入 HITL 暂停: {}", run.getRunId(), pause.getMessage());
        } catch (Exception e) {
            log.error("run {} 执行异常", run.getRunId(), e);
            MultiAgentRun latest = runMapper.selectById(run.getId());
            if (latest != null) finishRun(latest, "FAILED", e.getMessage());
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR,
                    "runId", run.getRunId(), "error", e.getMessage());
        }

        return run.getRunId();
    }

    /** 从 HITL 断点恢复 */
    public String resumeFromHitl(String runId, String userInput, SseEmitter emitter) {
        MultiAgentRun run = loadRun(runId);
        if (run == null) {
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, Map.of("runId", runId, "error", "Run 不存在"));
            return runId;
        }
        if (!"PAUSED".equals(run.getStatus())) {
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, Map.of("runId", runId, "error", "Run 非 PAUSED 状态"));
            return runId;
        }

        run.setStatus("RUNNING");
        runMapper.updateById(run);

        List<AgentInstance> agents = parseAgents(run.getAgentsJson());
        MultiAgentContext ctx = new MultiAgentContext(objectMapper);
        ctx.put("runId", run.getRunId());
        ctx.put("input", run.getUserGoal());
        ctx.setVars("input", run.getUserGoal());

        sse.send(emitter, MultiAgentEvents.MULTI_AGENT_RESUME, Map.of(
                "runId", run.getRunId(), "userInput", userInput));

        try {
            // 续跑仍按原 mode 入口（Coordinator 内部负责从最后一个 HITL 恢复）
            switch (run.getExecutionMode() == null ? "" : run.getExecutionMode()) {
                case "CREW_SEQUENTIAL", "CREW_HIERARCHICAL", "CREW_PARALLEL" -> {
                    crewCoordinator.resume(run, agents, ctx, userInput, emitter);
                }
                case "GRAPH_ROUTED" -> {
                    DAGGraph graph = parseGraphFromCrewConfig(run);
                    graphCoordinator.resume(run, agents, graph, ctx, userInput, emitter);
                }
                case "GROUP_CHAT_ROUND_ROBIN", "GROUP_CHAT_DYNAMIC_ROUTER" -> {
                    groupChatCoordinator.resume(run, agents, ctx, userInput, emitter);
                }
                default -> throw new IllegalStateException("未知 executionMode: " + run.getExecutionMode());
            }
        } catch (PauseSignal pause) {
            // 续跑过程中再次命中 HITL —— 保持 PAUSED，不能标记 FAILED
            log.info("run {} 续跑再次进入 HITL 暂停: {}", run.getRunId(), pause.getMessage());
        } catch (Exception e) {
            log.error("run {} 恢复执行异常", run.getRunId(), e);
            MultiAgentRun latest = runMapper.selectById(run.getId());
            if (latest != null) finishRun(latest, "FAILED", e.getMessage());
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, "runId", runId, "error", e.getMessage());
        }

        return run.getRunId();
    }

    /** 取消运行 */
    public void cancelRun(String runId) {
        MultiAgentRun run = loadRun(runId);
        if (run == null) return;
        run.setStatus("CANCELLED");
        run.setFinishTime(LocalDateTime.now());
        runMapper.updateById(run);

        // 未执行 step 置 SKIPPED
        List<MultiAgentStep> steps = stepMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultiAgentStep>()
                        .eq(MultiAgentStep::getRunId, runId)
                        .in(MultiAgentStep::getStatus, "PENDING", "HITL_PENDING", "RUNNING"));
        for (MultiAgentStep s : steps) {
            s.setStatus("SKIPPED");
            stepMapper.updateById(s);
        }
    }

    /** 查询运行状态 —— 委托给 Service */
    public Map<String, Object> getRunState(String runId) {
        return multiAgentService.getRunState(runId);
    }

    // ==================== 持久化 & 解析（委托给 Service） ====================

    public void saveRun(MultiAgentRun run) {
        multiAgentService.saveRun(run);
    }

    public void insertStep(MultiAgentStep step) {
        multiAgentService.insertStep(step);
    }

    public void updateStep(MultiAgentStep step) {
        multiAgentService.updateStep(step);
    }

    public MultiAgentRun loadRun(String runId) {
        return multiAgentService.loadRun(runId);
    }

    public MultiAgentRun getRunById(Long id) {
        return multiAgentService.getRunById(id);
    }

    public void finishRun(MultiAgentRun run, String status, String error) {
        multiAgentService.finishRun(run, status, error);
    }

    public List<AgentInstance> parseAgents(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("agentsJson 解析失败", e);
            return new ArrayList<>();
        }
    }

    /** 从 crewConfigJson 或 graphJson 取 DAGGraph */
    public DAGGraph parseGraphFromCrewConfig(MultiAgentRun run) {
        // 优先 graphJson，再看 crewConfigJson.graph
        String graphJson = run.getCrewConfigJson();
        if (graphJson != null && !graphJson.isBlank()) {
            try {
                Map<String, Object> crewCfg = objectMapper.readValue(graphJson, new TypeReference<>() {});
                Object g = crewCfg.get("graph");
                if (g != null) {
                    return objectMapper.convertValue(g, DAGGraph.class);
                }
            } catch (Exception ignore) {}
            try {
                return objectMapper.readValue(graphJson, DAGGraph.class);
            } catch (Exception ignore) {}
        }
        return new DAGGraph(new ArrayList<>(), new ArrayList<>());
    }

    /** 供 controller 使用：生成唯一 runId */
    public String newRunId() {
        return "mag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** HITL 暂停信号（供 coordinators 抛出） */
    public static class PauseSignal extends RuntimeException {
        public PauseSignal(String message) {
            super(message, null, false, false);
        }
    }
}
