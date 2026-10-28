package com.wgh.rag.agentmulti.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.entity.MultiAgentCrew;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentCrewMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import com.wgh.rag.agentmulti.runtime.MultiAgentRuntime;
import com.wgh.rag.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 多 Agent 协作 REST API —— 运行控制（创建/查询/恢复/取消） + Crew CRUD。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-multi")
@RequiredArgsConstructor
public class MultiAgentController {

    private final MultiAgentRuntime runtime;
    private final MultiAgentRunMapper runMapper;
    private final MultiAgentStepMapper stepMapper;
    private final MultiAgentCrewMapper crewMapper;
    private final ObjectMapper objectMapper;

    // ==================== Run API ====================

    /** 创建多 Agent 运行并返回 runId（SSE 需另开 /runs/{runId}/stream 或由前端自行订阅） */
    @PostMapping("/runs")
    public Result<Map<String, Object>> createRun(@RequestBody Map<String, Object> body) {
        String sessionId = body.get("sessionId") != null ? String.valueOf(body.get("sessionId")) : null;
        String chatWindowId = body.get("chatWindowId") != null ? String.valueOf(body.get("chatWindowId")) : null;
        String executionMode = String.valueOf(body.getOrDefault("executionMode", "CREW_SEQUENTIAL"));
        String crewId = body.get("crewId") != null ? String.valueOf(body.get("crewId")) : null;
        String input = body.get("input") != null ? String.valueOf(body.get("input")) : "";
        String userGoal = body.get("userGoal") != null ? String.valueOf(body.get("userGoal")) : input;
        String agentsJson = body.get("agentsJson") != null
                ? (body.get("agentsJson") instanceof String s ? s : toJson(body.get("agentsJson")))
                : null;
        String crewConfigJson = body.get("crewConfigJson") != null
                ? (body.get("crewConfigJson") instanceof String s ? s : toJson(body.get("crewConfigJson")))
                : null;

        String runId = runtime.newRunId();

        // 若提供 crewId，从 Crew 模板补齐 agentsJson / crewConfigJson
        MultiAgentRun run = new MultiAgentRun();
        run.setRunId(runId);
        run.setSessionId(sessionId != null ? parseLong(sessionId) : null);
        run.setChatWindowId(chatWindowId != null ? parseLong(chatWindowId) : 0L);
        run.setExecutionMode(executionMode);
        run.setUserGoal(userGoal);
        run.setInputText(input);
        run.setStatus("PLANNING");
        run.setCurrentRound(0);
        run.setMaxRounds(body.get("maxRounds") != null ? ((Number) body.get("maxRounds")).intValue() : 10);
        run.setStartTime(LocalDateTime.now());

        if (crewId != null) {
            MultiAgentCrew crew = crewMapper.selectById(parseLong(crewId));
            if (crew != null) {
                run.setCrewConfigJson(crew.getCrewConfigJson() != null ? crew.getCrewConfigJson() : crew.getGraphJson());
                run.setAgentsJson(crew.getAgentsJson());
                if (crew.getExecutionMode() != null) run.setExecutionMode(crew.getExecutionMode());
            }
        }

        if (crewConfigJson != null) run.setCrewConfigJson(crewConfigJson);
        if (agentsJson != null) run.setAgentsJson(agentsJson);

        // 必须有默认值（DB NOT NULL）
        if (run.getCrewConfigJson() == null) run.setCrewConfigJson("{}");
        if (run.getAgentsJson() == null) run.setAgentsJson("[]");

        runMapper.insert(run);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", runId);
        resp.put("status", "PLANNING");
        return Result.success(resp);
    }

    /** 启动执行（SSE） —— 返回 SseEmitter，后端线程异步执行 run */
    @GetMapping("/runs/{runId}/stream")
    public SseEmitter streamRun(@PathVariable String runId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 min
        emitter.onCompletion(() -> log.info("SSE runId={} completed", runId));
        emitter.onTimeout(() -> log.warn("SSE runId={} timeout", runId));
        emitter.onError(e -> log.warn("SSE runId={} error: {}", runId, e.getMessage()));

        new Thread(() -> {
            try {
                runtime.execute(runId, emitter);
                emitter.complete();
            } catch (Exception e) {
                log.error("runId={} 执行异常", runId, e);
                emitter.completeWithError(e);
            }
        }, "multi-agent-run-" + runId).start();

        return emitter;
    }

    /** 查询 Run 状态 */
    @GetMapping("/runs/{runId}/state")
    public Result<Map<String, Object>> getRunState(@PathVariable String runId) {
        return Result.success(runtime.getRunState(runId));
    }

    /** HITL 恢复（SSE） */
    @PostMapping("/runs/{runId}/hitl-confirm")
    public SseEmitter hitlConfirm(@PathVariable String runId,
                                   @RequestBody(required = false) Map<String, Object> body) {
        String userInput = body != null && body.get("userInput") != null
                ? String.valueOf(body.get("userInput")) : "";

        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        emitter.onCompletion(() -> log.info("SSE resume runId={} completed", runId));

        new Thread(() -> {
            try {
                runtime.resumeFromHitl(runId, userInput, emitter);
                emitter.complete();
            } catch (Exception e) {
                log.error("runId={} resume 异常", runId, e);
                emitter.completeWithError(e);
            }
        }, "multi-agent-resume-" + runId).start();

        return emitter;
    }

    /** 取消 Run */
    @PostMapping("/runs/{runId}/cancel")
    public Result<Void> cancelRun(@PathVariable String runId) {
        runtime.cancelRun(runId);
        return Result.success(null);
    }

    /** 查询 Run 的 Steps 列表 */
    @GetMapping("/runs/{runId}/steps")
    public Result<List<MultiAgentStep>> listSteps(@PathVariable String runId) {
        List<MultiAgentStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<MultiAgentStep>()
                        .eq(MultiAgentStep::getRunId, runId)
                        .orderByAsc(MultiAgentStep::getStepIndex));
        return Result.success(steps);
    }

    // ==================== Crew API ====================

    @GetMapping("/crews")
    public Result<List<MultiAgentCrew>> listCrews() {
        List<MultiAgentCrew> crews = crewMapper.selectList(
                new LambdaQueryWrapper<MultiAgentCrew>()
                        .orderByDesc(MultiAgentCrew::getCreateTime));
        return Result.success(crews);
    }

    @GetMapping("/crews/{id}")
    public Result<MultiAgentCrew> getCrew(@PathVariable Long id) {
        return Result.success(crewMapper.selectById(id));
    }

    @PostMapping("/crews")
    public Result<MultiAgentCrew> createCrew(@RequestBody MultiAgentCrew crew) {
        crew.setStatus(crew.getStatus() != null ? crew.getStatus() : 1);
        crewMapper.insert(crew);
        return Result.success(crew);
    }

    @PutMapping("/crews/{id}")
    public Result<MultiAgentCrew> updateCrew(@PathVariable Long id, @RequestBody MultiAgentCrew crew) {
        crew.setId(id);
        crewMapper.updateById(crew);
        return Result.success(crew);
    }

    @DeleteMapping("/crews/{id}")
    public Result<Void> deleteCrew(@PathVariable Long id) {
        crewMapper.deleteById(id);
        return Result.success(null);
    }

    /** 校验 Crew 的 agentsJson / graphJson 合法性 */
    @PostMapping("/crews/{id}/validate")
    public Result<Map<String, Object>> validateCrew(@PathVariable Long id) {
        MultiAgentCrew crew = crewMapper.selectById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        List<String> errors = new ArrayList<>();

        if (crew == null) {
            errors.add("Crew 不存在");
        } else {
            // agentsJson 必须能解析成 List<AgentInstance>
            if (crew.getAgentsJson() != null && !crew.getAgentsJson().isBlank()) {
                try {
                    List<AgentInstance> agents = objectMapper.readValue(
                            crew.getAgentsJson(), new TypeReference<>() {});
                    if (agents.isEmpty()) errors.add("agentsJson 为空");
                    else {
                        Set<String> ids = new HashSet<>();
                        for (AgentInstance a : agents) {
                            if (a.getAgentId() == null || a.getAgentId().isBlank()) errors.add("agentId 为空");
                            if (!ids.add(a.getAgentId())) errors.add("agentId 重复：" + a.getAgentId());
                        }
                    }
                } catch (Exception e) {
                    errors.add("agentsJson 解析失败：" + e.getMessage());
                }
            }
            // graphJson 必须能解析成 DAGGraph
            if (crew.getGraphJson() != null && !crew.getGraphJson().isBlank()) {
                try {
                    objectMapper.readValue(crew.getGraphJson(),
                            com.wgh.rag.agentmulti.dto.DAGGraph.class);
                } catch (Exception e) {
                    errors.add("graphJson 解析失败：" + e.getMessage());
                }
            }
        }

        if (!errors.isEmpty()) result.put("valid", false);
        result.put("errors", errors);
        return Result.success(result);
    }

    // ==================== 工具方法 ====================

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String toJson(Object v) {
        try { return objectMapper.writeValueAsString(v); }
        catch (Exception e) { return "{}"; }
    }
}
