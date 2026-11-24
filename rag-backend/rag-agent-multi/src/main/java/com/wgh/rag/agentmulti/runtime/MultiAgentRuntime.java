package com.wgh.rag.agentmulti.runtime;

import com.wgh.rag.agentmulti.constant.MultiAgentConstants;

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
import com.wgh.rag.agentmulti.service.AgentStepCheckpointService;
import com.wgh.rag.agentmulti.service.AgentStepCheckpointService.CheckpointSession;
import com.wgh.rag.agentmulti.service.MultiAgentService;
import com.wgh.rag.execution.error.ExecutionCancelledException;
import com.wgh.rag.execution.error.LeaseLostException;
import com.wgh.rag.execution.run.RunLifecycle;
import com.wgh.rag.agentmulti.support.SseEventSender;
import com.wgh.rag.agentmulti.crew.CrewCoordinator;
import com.wgh.rag.agentmulti.graph.GraphCoordinator;
import com.wgh.rag.agentmulti.groupchat.GroupChatCoordinator;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.quota.service.QuotaChargeTracker;
import com.wgh.rag.quota.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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
    private final AgentStepCheckpointService checkpointService;
    private final QuotaService quotaService;
    private final QuotaChargeTracker chargeTracker;

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
        return runInternal(run, emitter, checkpointService.open(runId), false, RunLifecycle.NOOP);
    }

    /**
     * 崩溃恢复：从 (runId, stepKey) 检查点继续。已完成步骤只回填上下文，不重新执行、不重发卡片。
     */
    public String resumeFromCheckpoint(String runId, SseEmitter emitter) {
        return resumeFromCheckpoint(runId, emitter, RunLifecycle.NOOP);
    }

    /**
     * 后台作业续跑：事件出口可为 {@code null}（静默），并在步骤边界续租/响应取消。
     */
    public String resumeFromCheckpoint(String runId, SseEmitter emitter, RunLifecycle lifecycle) {
        MultiAgentRun run = loadRun(runId);
        if (run == null) {
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, Map.of("runId", runId, "error", "Run 不存在"));
            return runId;
        }
        if (!MultiAgentConstants.STATUS_RUNNING.equals(run.getStatus())
                && !"PLANNING".equals(run.getStatus())) {
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR,
                    Map.of("runId", runId, "error", "Run 非可恢复状态: " + run.getStatus()));
            return runId;
        }
        return runInternal(run, emitter, checkpointService.open(runId), true,
                lifecycle == null ? RunLifecycle.NOOP : lifecycle);
    }

    private String runInternal(MultiAgentRun run, SseEmitter emitter,
                               CheckpointSession session, boolean resume, RunLifecycle lifecycle) {
        lifecycle.throwIfCancelled();
        String runId = run.getRunId();
        List<AgentInstance> agents = parseAgents(run.getAgentsJson());

        sse.send(emitter, resume ? MultiAgentEvents.MULTI_AGENT_RESUME : MultiAgentEvents.MULTI_AGENT_START,
                "runId", run.getRunId(),
                "executionMode", run.getExecutionMode(),
                "agentCount", agents.size(),
                "userGoal", run.getUserGoal());

        // 状态置为 RUNNING
        run.setStatus(MultiAgentConstants.STATUS_RUNNING);
        run.setStartTime(run.getStartTime() != null ? run.getStartTime() : LocalDateTime.now());
        runMapper.updateById(run);

        // 构建 ctx
        MultiAgentContext ctx = new MultiAgentContext(objectMapper);
        ctx.put("runId", run.getRunId());
        ctx.put("sessionId", run.getSessionId());
        ctx.put("input", run.getUserGoal() == null ? "" : run.getUserGoal());
        ctx.setVars("input", run.getUserGoal() == null ? "" : run.getUserGoal());
        if (resume) {
            checkpointService.restoreContext(session, ctx);
        }

        try {
            switch (run.getExecutionMode() == null ? "" : run.getExecutionMode()) {
                case "CREW_SEQUENTIAL", "CREW_HIERARCHICAL", "CREW_PARALLEL" -> {
                    crewCoordinator.execute(run, agents, ctx, emitter, session, lifecycle);
                }
                case "GRAPH_ROUTED" -> {
                    DAGGraph graph = parseGraphFromCrewConfig(run);
                    graphCoordinator.execute(run, agents, graph, ctx, emitter, session, lifecycle);
                }
                case "GROUP_CHAT_ROUND_ROBIN", "GROUP_CHAT_DYNAMIC_ROUTER" -> {
                    groupChatCoordinator.execute(run, agents, ctx, emitter, session, lifecycle);
                }
                default -> throw new IllegalStateException("未知 executionMode: " + run.getExecutionMode());
            }

            // 兜底：如果 Coordinator 没设置终态；写终态前再确认租约
            lifecycle.throwIfCancelled();
            lifecycle.heartbeat();
            MultiAgentRun latest = runMapper.selectById(run.getId());
            if (latest != null && MultiAgentConstants.STATUS_RUNNING.equals(latest.getStatus())) {
                finishRun(latest, MultiAgentConstants.STATUS_COMPLETED, null);
            }
        } catch (PauseSignal pause) {
            // HITL —— run.status 已由 coordinator 置为 PAUSED
            log.info("run {} 进入 HITL 暂停: {}", run.getRunId(), pause.getMessage());
        } catch (LeaseLostException | ExecutionCancelledException stop) {
            // 租约丢失/作业取消：不写业务终态，交给调度层重试或人工观测
            throw stop;
        } catch (Exception e) {
            log.error("run {} 执行异常", run.getRunId(), e);
            MultiAgentRun latest = runMapper.selectById(run.getId());
            if (latest != null) finishRun(latest, MultiAgentConstants.STATUS_FAILED, e.getMessage());
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR,
                    "runId", run.getRunId(), "error", e.getMessage());
        }

        return run.getRunId();
    }

    /** 从 HITL 断点恢复 */
    /**
     * HITL 确认的「状态推进 + 扣费」事务边界。
     *
     * <p>先校验 PAUSED，再用条件 UPDATE 原子推进；计数与流水同事务提交。
     * 同一运行再次暂停后再次确认时，复用未决流水，不叠加扣费。</p>
     */
    @Transactional
    public HitlResumeClaim prepareHitlResume(String runId) {
        MultiAgentRun run = loadRun(runId);
        if (run == null) {
            throw new BusinessException("Run 不存在");
        }
        if (!MultiAgentConstants.STATUS_PAUSED.equals(run.getStatus())) {
            throw new BusinessException("Run 非 PAUSED 状态");
        }
        if (runMapper.claimPausedForResume(runId) == 0) {
            throw new BusinessException("该 HITL 确认已处理，请刷新运行状态");
        }
        run.setStatus(MultiAgentConstants.STATUS_RUNNING);

        QuotaService.ResumeChargeReservation reservation = quotaService.reserveResumeCharge(
                SecurityUtils.currentUsername(), run.getChatWindowId(), "hitl:multi-agent:" + runId, null, null);
        quotaService.attachSession(reservation.charge(), run.getSessionId());
        log.info("多 Agent HITL 确认计费: runId={}, alreadySettled={}, charge={}", runId,
                reservation.alreadySettled(),
                reservation.charge() == null ? null : reservation.charge().chargeId());
        return new HitlResumeClaim(run, chargeTracker.track(reservation.charge()));
    }

    /** 确认结果：已推进的运行与计费句柄。 */
    public record HitlResumeClaim(MultiAgentRun run, QuotaChargeTracker.Handle billing) {
    }

    /** 绑定产出观察者：输出类事件成功下发后结算扣费。 */
    public void bindOutputObserver(SseEmitter emitter, Runnable observer) {
        sse.bindOutputObserver(emitter, observer);
    }

    /** 解绑产出观察者。 */
    public void unbindOutputObserver(SseEmitter emitter) {
        sse.unbindOutputObserver(emitter);
    }

    /** 发送 SSE 错误事件（保持既有非 PAUSED 场景的协议语义）。 */
    public void sendSseError(SseEmitter emitter, String runId, String error) {
        sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, Map.of("runId", runId, "error", error));
    }

    /**
     * HITL 恢复（SSE）。
     *
     * @param claim 已由 {@link #prepareHitlResume(String)} 完成状态推进与扣费的凭据；
     *              为 {@code null} 时沿用旧语义（校验并推进 PAUSED）。
     */
    public String resumeFromHitl(String runId, String userInput, SseEmitter emitter, HitlResumeClaim claim) {
        MultiAgentRun run = loadRun(runId);
        if (run == null) {
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, Map.of("runId", runId, "error", "Run 不存在"));
            return runId;
        }
        if (claim == null) {
            if (!MultiAgentConstants.STATUS_PAUSED.equals(run.getStatus())) {
                sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, Map.of("runId", runId, "error", "Run 非 PAUSED 状态"));
                return runId;
            }
            run.setStatus(MultiAgentConstants.STATUS_RUNNING);
            runMapper.updateById(run);
        }

        List<AgentInstance> agents = parseAgents(run.getAgentsJson());
        MultiAgentContext ctx = new MultiAgentContext(objectMapper);
        ctx.put("runId", run.getRunId());
        ctx.put("sessionId", run.getSessionId());
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
            if (latest != null) finishRun(latest, MultiAgentConstants.STATUS_FAILED, e.getMessage());
            sse.send(emitter, MultiAgentEvents.MULTI_AGENT_ERROR, "runId", runId, "error", e.getMessage());
        }

        return run.getRunId();
    }

    /** 兼容旧调用：未预扣费的恢复入口。 */
    public String resumeFromHitl(String runId, String userInput, SseEmitter emitter) {
        return resumeFromHitl(runId, userInput, emitter, null);
    }

    /** 取消运行 */
    public void cancelRun(String runId) {
        MultiAgentRun run = loadRun(runId);
        if (run == null) return;
        run.setStatus(MultiAgentConstants.STATUS_CANCELLED);
        run.setFinishTime(LocalDateTime.now());
        runMapper.updateById(run);

        // 未执行 step 置 SKIPPED
        List<MultiAgentStep> steps = stepMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultiAgentStep>()
                        .eq(MultiAgentStep::getRunId, runId)
                        .in(MultiAgentStep::getStatus, MultiAgentConstants.STATUS_PENDING,
                                MultiAgentConstants.STATUS_HITL_PENDING, MultiAgentConstants.STATUS_RUNNING));
        for (MultiAgentStep s : steps) {
            s.setStatus(MultiAgentConstants.STATUS_SKIPPED);
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
