package com.wgh.rag.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.event.DomainEventPublisher;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.workflow.constant.WorkflowEvents;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.PauseSignal;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.entity.WorkflowVersion;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowVersionMapper;
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
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final GraphValidator graphValidator;
    private final WorkflowEngine engine;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor workflowExecutor;

    /** runId → 活跃 SSE 通道（进程内） */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public WorkflowService(WorkflowMapper workflowMapper,
                           WorkflowVersionMapper versionMapper,
                           WorkflowRunMapper runMapper,
                           WorkflowNodeRunMapper nodeRunMapper,
                           GraphValidator graphValidator,
                           WorkflowEngine engine,
                           ObjectMapper objectMapper,
                           @Qualifier("workflowExecutor") ThreadPoolTaskExecutor workflowExecutor) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
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
        snapshotVersion(workflow);
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
        snapshotVersion(existing);
        return existing;
    }

    public List<String> validateGraph(Long id) {
        Workflow wf = getById(id);
        return graphValidator.validate(parseGraph(wf.getGraphJson())).errors;
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

    // ==================== 分享运行页 ====================

    /** 开启分享：工作流必须为启用状态；首次开启时生成访问令牌。 */
    @Transactional
    public Workflow enableShare(Long id) {
        Workflow wf = getById(id);
        if (wf.getStatus() != null && wf.getStatus() == 0) {
            throw new BusinessException("工作流已停用，无法开启分享");
        }
        wf.setShareEnabled(true);
        if (wf.getShareToken() == null || wf.getShareToken().isBlank()) {
            wf.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        }
        wf.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(wf);
        return wf;
    }

    /** 关闭分享：链接立即失效（令牌保留，重新开启后恢复原链接）。 */
    @Transactional
    public Workflow disableShare(Long id) {
        Workflow wf = getById(id);
        wf.setShareEnabled(false);
        wf.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(wf);
        return wf;
    }

    /** 重新生成分享令牌：旧链接立即失效。 */
    @Transactional
    public Workflow regenerateShareToken(Long id) {
        Workflow wf = getById(id);
        if (!Boolean.TRUE.equals(wf.getShareEnabled())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "工作流未开启分享，无法重新生成链接");
        }
        wf.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        wf.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(wf);
        return wf;
    }

    /** 根据分享令牌查找可用工作流（分享运行页用）：需开启分享且工作流处于启用状态。 */
    public Workflow getByShareToken(String shareToken) {
        Workflow wf = workflowMapper.selectOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getShareToken, shareToken)
                .eq(Workflow::getShareEnabled, true)
                .eq(Workflow::getStatus, 1));
        if (wf == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "分享链接已失效或工作流已关闭分享");
        return wf;
    }

    /** 校验运行记录属于指定工作流（分享接口的越权防护，不暴露他人运行）。 */
    public void assertRunOfWorkflow(Long workflowId, Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null || !workflowId.equals(run.getWorkflowId())) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "运行记录不存在");
        }
    }

    // ==================== 版本管理 ====================

    /** 保存当前定义为一条版本快照（版本只增不改，回滚也会生成新版本）。 */
    private void snapshotVersion(Workflow wf) {
        WorkflowVersion v = new WorkflowVersion();
        v.setWorkflowId(wf.getId());
        v.setVersion(wf.getVersion());
        v.setName(wf.getName());
        v.setDescription(wf.getDescription());
        v.setGraphJson(wf.getGraphJson());
        versionMapper.insert(v);
    }

    /** 版本列表（按版本号倒序） */
    public List<WorkflowVersion> listVersions(Long workflowId) {
        getById(workflowId);
        return versionMapper.selectList(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getWorkflowId, workflowId)
                .orderByDesc(WorkflowVersion::getVersion));
    }

    /** 单个版本详情 */
    public WorkflowVersion getVersion(Long workflowId, Integer version) {
        WorkflowVersion v = versionMapper.selectOne(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getWorkflowId, workflowId)
                .eq(WorkflowVersion::getVersion, version));
        if (v == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "版本不存在");
        return v;
    }

    /** 回滚到指定版本：恢复名称/描述/图定义，版本号 +1 并生成新快照。 */
    @Transactional
    public Workflow rollback(Long workflowId, Integer version) {
        Workflow existing = getById(workflowId);
        WorkflowVersion snapshot = getVersion(workflowId, version);

        existing.setName(snapshot.getName());
        existing.setDescription(snapshot.getDescription());
        existing.setGraphJson(snapshot.getGraphJson());
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        existing.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(existing);
        snapshotVersion(existing);
        return existing;
    }

    // ==================== 运行 ====================

    /** 启动一次运行，返回 SSE 通道。 */
    public SseEmitter run(Long workflowId, String input) {
        return run(workflowId, input, false);
    }

    /**
     * 启动一次运行（可指定安全模式），返回 SSE 通道。
     * safeMode=true 时工具节点拒绝有写操作副作用的 HTTP / MCP 调用（分享运行页使用）。
     */
    public SseEmitter run(Long workflowId, String input, boolean safeMode) {
        Workflow wf = getById(workflowId);
        WorkflowGraph graph = parseGraph(wf.getGraphJson());
        GraphValidator.GraphValidationResult vr = graphValidator.validate(graph);
        assertValid(vr);

        WorkflowRun run = createRun(wf, graph, input, null);

        SseEmitter emitter = createEmitter(run.getId());
        BiConsumer<String, Map<String, Object>> sink = sinkFor(run.getId());

        workflowExecutor.execute(() -> {
            try {
                DomainEventPublisher.emit(sink, WorkflowEvents.WORKFLOW_START, Map.of(
                        "runId", run.getId(),
                        "workflowId", wf.getId(),
                        "workflowName", wf.getName(),
                        "totalNodes", graph.getNodes().size()));
                engine.run(run, graph, input, vr, safeMode, sink);
            } catch (PauseSignal pause) {
                log.info("工作流 {} 在节点暂停，等待人工确认", run.getId());
            } catch (Exception e) {
                log.error("工作流运行失败 runId={}", run.getId(), e);
                markRunFailed(run.getId(), e.getMessage());
                DomainEventPublisher.emit(sink, WorkflowEvents.WORKFLOW_FAILED, Map.of(
                        "runId", run.getId(),
                        "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
            } finally {
                completeEmitter(run.getId());
            }
        });

        return emitter;
    }

    /**
     * 聊天窗口触发的工作流运行：事件通过调用方提供的 sink 输出（映射为聊天 SSE 协议）。
     * 返回已创建的运行记录（执行异步进行）。
     *
     * @param sessionId 关联聊天会话（用于刷新恢复定位运行）
     * @param safeMode  分享态安全模式：禁止写操作类 HTTP / MCP 工具节点
     */
    public WorkflowRun runForChat(Long workflowId, String input, Long sessionId,
                                  boolean safeMode, BiConsumer<String, Map<String, Object>> sink) {
        Workflow wf = getById(workflowId);
        WorkflowGraph graph = parseGraph(wf.getGraphJson());
        GraphValidator.GraphValidationResult vr = graphValidator.validate(graph);
        assertValid(vr);

        WorkflowRun run = createRun(wf, graph, input, sessionId);

        workflowExecutor.execute(() -> {
            try {
                DomainEventPublisher.emit(sink, WorkflowEvents.WORKFLOW_START, Map.of(
                        "runId", run.getId(),
                        "workflowId", wf.getId(),
                        "workflowName", wf.getName(),
                        "totalNodes", graph.getNodes().size()));
                engine.run(run, graph, input, vr, safeMode, sink);
            } catch (PauseSignal pause) {
                log.info("聊天工作流 {} 在节点暂停，等待人工确认", run.getId());
            } catch (Exception e) {
                log.error("聊天工作流运行失败 runId={}", run.getId(), e);
                markRunFailed(run.getId(), e.getMessage());
                DomainEventPublisher.emit(sink, WorkflowEvents.WORKFLOW_FAILED, Map.of(
                        "runId", run.getId(),
                        "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
        return run;
    }

    /** 创建工作流运行实例（统一设置版本快照与节点计数）。 */
    private WorkflowRun createRun(Workflow wf, WorkflowGraph graph, String input, Long sessionId) {
        if (wf.getStatus() != null && wf.getStatus() == 0) {
            throw new BusinessException("工作流已停用，无法运行");
        }
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(wf.getId());
        run.setSessionId(sessionId);
        run.setWorkflowVersion(wf.getVersion());
        run.setInputText(input == null ? "" : input);
        run.setStatus("RUNNING");
        run.setGraphSnapshot(wf.getGraphJson());
        run.setExecutedNodes(0);
        run.setTotalNodes(graph.getNodes().size());
        run.setStartTime(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    /** 无 SSE 的静默运行（定时 / Webhook 触发使用），返回运行记录（异步执行中）。 */
    public WorkflowRun runHeadless(Long workflowId, String input) {
        Workflow wf = getById(workflowId);
        if (wf.getStatus() != null && wf.getStatus() == 0) {
            throw new BusinessException("工作流已停用，无法运行");
        }
        WorkflowGraph graph = parseGraph(wf.getGraphJson());
        GraphValidator.GraphValidationResult vr = graphValidator.validate(graph);
        assertValid(vr);

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

        BiConsumer<String, Map<String, Object>> sink = (event, payload) -> { /* 静默触发，无 SSE 订阅 */ };
        workflowExecutor.execute(() -> {
            try {
                engine.run(run, graph, input, vr, sink);
            } catch (PauseSignal pause) {
                log.info("触发运行 {} 在 HITL 节点暂停，等待人工确认", run.getId());
            } catch (Exception e) {
                log.error("触发运行失败 runId={}", run.getId(), e);
                markRunFailed(run.getId(), e.getMessage());
            }
        });
        return run;
    }

    /** HITL 确认后从断点续跑，返回新的 SSE 通道。 */
    public SseEmitter hitlConfirm(Long runId, String userInput) {
        HitlResumeState rs = prepareHitlResume(runId, userInput);

        SseEmitter emitter = createEmitter(runId);
        BiConsumer<String, Map<String, Object>> sink = sinkFor(runId);

        workflowExecutor.execute(() -> executeHitlResume(rs, sink, true));
        return emitter;
    }

    /**
     * 聊天窗口触发的 HITL 确认：事件通过调用方提供的 sink 输出（映射为聊天 SSE 协议），
     * 不创建独立 SSE 通道。返回更新后的运行记录（续跑异步进行）。
     */
    public WorkflowRun resumeForChat(Long runId, String userInput,
                                     BiConsumer<String, Map<String, Object>> sink) {
        HitlResumeState rs = prepareHitlResume(runId, userInput);
        workflowExecutor.execute(() -> executeHitlResume(rs, sink, false));
        return rs.run;
    }

    /** 校验断点并准备好续跑所需的上下文（确认输入写入 HITL 节点输出）。 */
    private HitlResumeState prepareHitlResume(Long runId, String userInput) {
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
        GraphValidator.GraphValidationResult vr = graphValidator.validate(graph);
        return new HitlResumeState(run, graph, vr, ctx);
    }

    private void executeHitlResume(HitlResumeState rs,
                                   BiConsumer<String, Map<String, Object>> sink,
                                   boolean completeEmitter) {
        try {
            engine.resume(rs.run, rs.graph, rs.validation, rs.ctx, sink);
        } catch (PauseSignal pause) {
            log.info("工作流 {} 再次暂停", rs.run.getId());
        } catch (Exception e) {
            log.error("工作流恢复执行失败 runId={}", rs.run.getId(), e);
            markRunFailed(rs.run.getId(), e.getMessage());
            DomainEventPublisher.emit(sink, WorkflowEvents.WORKFLOW_FAILED, Map.of(
                    "runId", rs.run.getId(),
                    "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
        } finally {
            if (completeEmitter) completeEmitter(rs.run.getId());
        }
    }

    /** HITL 续跑所需的运行状态快照 */
    private record HitlResumeState(WorkflowRun run, WorkflowGraph graph,
                                   GraphValidator.GraphValidationResult validation,
                                   RunContext ctx) {
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

    public WorkflowRun getRun(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException("运行记录不存在");
        return run;
    }

    /** 按聊天会话查询最近一次工作流运行状态（页面刷新恢复用），无记录返回 null。 */
    public Map<String, Object> getRunStateBySession(Long sessionId) {
        if (sessionId == null) return null;
        WorkflowRun run = runMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getSessionId, sessionId)
                .orderByDesc(WorkflowRun::getId)
                .last("LIMIT 1"));
        if (run == null) return null;
        return getRunState(run.getId());
    }

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
        assertValid(graphValidator.validate(graph));
    }

    private void assertValid(GraphValidator.GraphValidationResult vr) {
        if (vr.hasErrors()) {
            throw new BusinessException("工作流校验失败：" + String.join("；", vr.errors));
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
