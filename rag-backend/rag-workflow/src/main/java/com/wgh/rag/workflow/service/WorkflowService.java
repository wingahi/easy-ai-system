package com.wgh.rag.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.PauseSignal;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 工作流服务 —— CRUD、运行生命周期（创建 / 暂停 / 恢复 / 取消）、SSE 推送。
 */
@Slf4j
@Service
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final GraphValidator graphValidator;
    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor workflowExecutor;

    /** runId → 活跃 SSE 通道（进程内） */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public WorkflowService(WorkflowMapper workflowMapper,
                           WorkflowRunMapper runMapper,
                           WorkflowNodeRunMapper nodeRunMapper,
                           GraphValidator graphValidator,
                           WorkflowEngine engine,
                           ObjectMapper objectMapper,
                           @Qualifier("workflowExecutor") ThreadPoolTaskExecutor workflowExecutor) {
        this.workflowMapper = workflowMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.graphValidator = graphValidator;
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.workflowExecutor = workflowExecutor;
    }

    // ==================== CRUD ====================

    public PageResult<Workflow> page(Long page, Long size, String keyword, Integer status) {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Workflow::getName, keyword).or().like(Workflow::getDescription, keyword);
        }
        if (status != null) wrapper.eq(Workflow::getStatus, status);
        wrapper.orderByDesc(Workflow::getUpdateTime);
        Page<Workflow> result = workflowMapper.selectPage(Page.of(page, size), wrapper);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public List<Workflow> listEnabled() {
        return workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getStatus, 1)
                .orderByDesc(Workflow::getUpdateTime));
    }

    public Workflow getById(Long id) {
        Workflow wf = workflowMapper.selectById(id);
        if (wf == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "工作流不存在");
        return wf;
    }

    @Transactional
    public Workflow create(Workflow workflow) {
        if (workflow.getName() == null || workflow.getName().isBlank()) {
            throw new BusinessException("工作流名称不能为空");
        }
        WorkflowGraph graph = parseGraph(workflow.getGraphJson());
        assertValid(graph);

        workflow.setStatus(workflow.getStatus() != null ? workflow.getStatus() : 1);
        workflow.setVersion(1);
        workflow.setGraphJson(toJson(graph));
        workflowMapper.insert(workflow);
        return workflow;
    }

    @Transactional
    public Workflow update(Long id, Workflow payload) {
        Workflow existing = getById(id);
        WorkflowGraph graph = parseGraph(payload.getGraphJson());
        assertValid(graph);

        existing.setName(payload.getName() != null ? payload.getName() : existing.getName());
        existing.setDescription(payload.getDescription());
        existing.setGraphJson(toJson(graph));
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        if (payload.getStatus() != null) existing.setStatus(payload.getStatus());
        existing.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(existing);
        return existing;
    }

    public List<String> validateGraph(Long id) {
        Workflow wf = getById(id);
        return graphValidator.validate(parseGraph(wf.getGraphJson()));
    }

    @Transactional
    public void delete(Long id) {
        workflowMapper.deleteById(id);
    }

    @Transactional
    public void setStatus(Long id, int status) {
        Workflow wf = getById(id);
        wf.setStatus(status);
        wf.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(wf);
    }

    // ==================== 运行 ====================

    /** 启动一次运行，返回 SSE 通道。 */
    public SseEmitter run(Long workflowId, String input) {
        Workflow wf = getById(workflowId);
        if (wf.getStatus() != null && wf.getStatus() == 0) {
            throw new BusinessException("工作流已停用，无法运行");
        }
        WorkflowGraph graph = parseGraph(wf.getGraphJson());
        assertValid(graph);

        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(wf.getId());
        run.setWorkflowVersion(wf.getVersion());
        run.setInputText(input == null ? "" : input);
        run.setStatus("RUNNING");
        run.setGraphSnapshot(wf.getGraphJson());
        run.setExecutedNodes(0);
        run.setTotalNodes(graph.getNodes().size());
        run.setStartTime(LocalDateTime.now());
        runMapper.insert(run);

        SseEmitter emitter = createEmitter(run.getId());
        BiConsumer<String, Map<String, Object>> sink = sinkFor(run.getId());

        workflowExecutor.execute(() -> {
            try {
                sink.accept("workflow_start", Map.of(
                        "runId", run.getId(),
                        "workflowId", wf.getId(),
                        "workflowName", wf.getName(),
                        "totalNodes", graph.getNodes().size()));
                engine.run(run, graph, input, sink);
            } catch (PauseSignal pause) {
                log.info("工作流 {} 在节点暂停，等待人工确认", run.getId());
            } catch (Exception e) {
                log.error("工作流运行失败 runId={}", run.getId(), e);
                markRunFailed(run.getId(), e.getMessage());
                sink.accept("workflow_failed", Map.of(
                        "runId", run.getId(),
                        "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
            } finally {
                completeEmitter(run.getId());
            }
        });

        return emitter;
    }

    /** HITL 确认后从断点续跑，返回新的 SSE 通道。 */
    public SseEmitter hitlConfirm(Long runId, String userInput) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException("运行记录不存在");
        if (!"PAUSED".equals(run.getStatus())) throw new BusinessException("运行未处于暂停状态，无法确认");

        WorkflowNodeRun pending = nodeRunMapper.selectOne(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId)
                .eq(WorkflowNodeRun::getStatus, "HITL_PENDING")
                .orderByDesc(WorkflowNodeRun::getId)
                .last("LIMIT 1"));
        if (pending == null) throw new BusinessException("没有待确认的 HITL 节点");

        String confirmInput = userInput == null ? "" : userInput;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", confirmInput);

        pending.setStatus("SUCCESS");
        pending.setOutputJson(toJson(output));
        pending.setEndTime(LocalDateTime.now());
        nodeRunMapper.updateById(pending);

        RunContext ctx = engine.parseContext(run.getContextJson());
        ctx.getNodeOutputs().put(pending.getNodeId(), output);
        ctx.getVars().put("lastOutput", confirmInput);

        run.setStatus("RUNNING");
        run.setContextJson(toJson(ctx));
        run.setUpdateTime(LocalDateTime.now());
        runMapper.updateById(run);

        WorkflowGraph graph;
        try {
            graph = objectMapper.readValue(run.getGraphSnapshot(), WorkflowGraph.class);
        } catch (Exception e) {
            throw new BusinessException("运行图快照解析失败：" + e.getMessage());
        }

        SseEmitter emitter = createEmitter(runId);
        BiConsumer<String, Map<String, Object>> sink = sinkFor(runId);

        workflowExecutor.execute(() -> {
            try {
                engine.resume(run, graph, ctx, sink);
            } catch (PauseSignal pause) {
                log.info("工作流 {} 再次暂停", runId);
            } catch (Exception e) {
                log.error("工作流恢复执行失败 runId={}", runId, e);
                markRunFailed(runId, e.getMessage());
                sink.accept("workflow_failed", Map.of(
                        "runId", runId, "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
            } finally {
                completeEmitter(runId);
            }
        });

        return emitter;
    }

    /** 取消运行。 */
    @Transactional
    public void cancel(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException("运行记录不存在");
        if ("COMPLETED".equals(run.getStatus()) || "FAILED".equals(run.getStatus())) {
            throw new BusinessException("运行已结束，无法取消");
        }
        run.setStatus("CANCELLED");
        run.setFinishTime(LocalDateTime.now());
        run.setDurationMs(run.getStartTime() == null ? 0L
                : java.time.Duration.between(run.getStartTime(), LocalDateTime.now()).toMillis());
        runMapper.updateById(run);

        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId)
                .in(WorkflowNodeRun::getStatus, "RUNNING", "PENDING", "HITL_PENDING")
                .set(WorkflowNodeRun::getStatus, "SKIPPED"));
    }

    // ==================== 查询 ====================

    public Map<String, Object> getRunState(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException("运行记录不存在");

        List<WorkflowNodeRun> nodes = nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId)
                .orderByAsc(WorkflowNodeRun::getId));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("runId", run.getId());
        state.put("workflowId", run.getWorkflowId());
        state.put("status", run.getStatus());
        state.put("input", run.getInputText());
        state.put("output", run.getOutputText());
        state.put("error", run.getErrorMessage());
        state.put("executedNodes", run.getExecutedNodes());
        state.put("totalNodes", run.getTotalNodes());
        state.put("startTime", run.getStartTime());
        state.put("finishTime", run.getFinishTime());
        state.put("durationMs", run.getDurationMs());
        state.put("nodes", nodes.stream().map(this::nodeRunView).collect(Collectors.toList()));
        return state;
    }

    public PageResult<WorkflowRun> pageRuns(Long workflowId, Long page, Long size) {
        Page<WorkflowRun> result = runMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowRun::getId));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public List<Map<String, Object>> listNodeRuns(Long runId) {
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getRunId, runId)
                        .orderByAsc(WorkflowNodeRun::getId))
                .stream().map(this::nodeRunView).collect(Collectors.toList());
    }

    private Map<String, Object> nodeRunView(WorkflowNodeRun n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("nodeId", n.getNodeId());
        m.put("nodeType", n.getNodeType());
        m.put("nodeName", n.getNodeName());
        m.put("status", n.getStatus());
        m.put("iteration", n.getIteration());
        m.put("input", n.getInputJson());
        m.put("output", n.getOutputJson());
        m.put("error", n.getErrorMessage());
        m.put("startTime", n.getStartTime());
        m.put("endTime", n.getEndTime());
        m.put("durationMs", n.getDurationMs());
        return m;
    }

    // ==================== 内部工具 ====================

    private SseEmitter createEmitter(Long runId) {
        SseEmitter old = emitters.remove(runId);
        if (old != null) {
            try {
                old.complete();
            } catch (Exception ignored) {
                // 旧通道已断开
            }
        }
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.onTimeout(() -> emitters.remove(runId));
        emitter.onError(e -> emitters.remove(runId));
        emitter.onCompletion(() -> emitters.remove(runId));
        emitters.put(runId, emitter);
        return emitter;
    }

    private void completeEmitter(Long runId) {
        SseEmitter emitter = emitters.remove(runId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }
        }
    }

    private BiConsumer<String, Map<String, Object>> sinkFor(Long runId) {
        return (event, payload) -> {
            SseEmitter emitter = emitters.get(runId);
            if (emitter == null) return;
            try {
                emitter.send(SseEmitter.event()
                        .name(event)
                        .data(toJson(payload), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 页面刷新/断开导致的推送失败不影响后端执行（与 Agent 模块保持一致）
                log.warn("SSE 推送失败 runId={} event={}: {}", runId, event, e.getMessage());
            }
        };
    }

    private void markRunFailed(Long runId, String error) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) return;
        run.setStatus("FAILED");
        run.setErrorMessage(error);
        run.setFinishTime(LocalDateTime.now());
        run.setDurationMs(run.getStartTime() == null ? 0L
                : java.time.Duration.between(run.getStartTime(), LocalDateTime.now()).toMillis());
        runMapper.updateById(run);
    }

    private void assertValid(WorkflowGraph graph) {
        List<String> errors = graphValidator.validate(graph);
        if (!errors.isEmpty()) {
            throw new BusinessException("工作流校验失败：" + String.join("；", errors));
        }
    }

    private WorkflowGraph parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            throw new BusinessException("工作流图不能为空");
        }
        try {
            return objectMapper.readValue(graphJson, WorkflowGraph.class);
        } catch (Exception e) {
            throw new BusinessException("工作流图 JSON 解析失败：" + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败", e);
            return "{}";
        }
    }
}
