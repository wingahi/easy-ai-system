package com.wgh.rag.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.event.DomainEventPublisher;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.data.scope.DataScopeGuard;
import com.wgh.rag.data.scope.ResourceVisibility;
import com.wgh.rag.execution.error.ExecutionCancelledException;
import com.wgh.rag.execution.error.LeaseLostException;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.run.JobRunSupport;
import com.wgh.rag.quota.service.QuotaChargeTracker;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.workflow.constant.WorkflowConstants;
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
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private final QuotaService quotaService;
    private final QuotaChargeTracker chargeTracker;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** HITL 续跑的异步任务最多等待外层确认事务提交的时间。 */
    private static final long COMMIT_GATE_TIMEOUT_SECONDS = 10L;

    /** runId → 活跃 SSE 通道（进程内） */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 单步调试开关（默认关闭）。关闭时调试接口直接拒绝，普通运行链路不受任何影响。
     * 可通过配置项 {@code wgh.workflow.debug.enabled} 或环境变量 {@code WGH_WORKFLOW_DEBUG_ENABLED} 开启。
     */
    @org.springframework.beans.factory.annotation.Value("${wgh.workflow.debug.enabled:false}")
    private boolean debugStepEnabled = false;

    public WorkflowService(WorkflowMapper workflowMapper,
                           WorkflowVersionMapper versionMapper,
                           WorkflowRunMapper runMapper,
                           WorkflowNodeRunMapper nodeRunMapper,
                           GraphValidator graphValidator,
                           WorkflowEngine engine,
                           ObjectMapper objectMapper,
                           @Qualifier("workflowExecutor") ThreadPoolTaskExecutor workflowExecutor,
                           QuotaService quotaService,
                           QuotaChargeTracker chargeTracker,
                           org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.graphValidator = graphValidator;
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.workflowExecutor = workflowExecutor;
        this.quotaService = quotaService;
        this.chargeTracker = chargeTracker;
        this.transactionManager = transactionManager;
    }

    // ==================== CRUD ====================

    @Transactional(readOnly = true)
    public PageResult<Workflow> page(Long page, Long size, String keyword, Integer status) {
        return DataScopeContext.runWithScope(() -> {
            LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
            if (keyword != null && !keyword.isBlank()) {
                wrapper.like(Workflow::getName, keyword).or().like(Workflow::getDescription, keyword);
            }
            if (status != null) wrapper.eq(Workflow::getStatus, status);
            wrapper.orderByDesc(Workflow::getUpdateTime);
            Page<Workflow> result = workflowMapper.selectPage(Page.of(page, size), wrapper);
            return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
        });
    }

    @Transactional(readOnly = true)
    public List<Workflow> listEnabled() {
        return DataScopeContext.runWithScope(() -> workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getStatus, 1)
                .orderByDesc(Workflow::getUpdateTime)));
    }

    @Transactional(readOnly = true)
    public Workflow getById(Long id) {
        Workflow wf = workflowMapper.selectById(id);
        if (wf == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "工作流不存在");
        return wf;
    }

    /** 详情可见性校验：PRIVATE 他人工作流按不存在处理 */
    @Transactional(readOnly = true)
    public Workflow getVisible(Long id) {
        Workflow wf = getById(id);
        DataScopeGuard.assertReadable(wf.getCreateBy(), wf.getVisibility(), "工作流");
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
        workflow.setVisibility(ResourceVisibility.normalize(workflow.getVisibility()));
        workflow.setVersion(1);
        workflow.setGraphJson(toJson(graph));
        workflowMapper.insert(workflow);
        snapshotVersion(workflow);
        return workflow;
    }

    @Transactional
    public Workflow update(Long id, Workflow payload) {
        Workflow existing = getById(id);
        DataScopeGuard.assertWritable(existing.getCreateBy(), existing.getVisibility(), "工作流");
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
        return validateGraph(id, null);
    }

    /** 校验工作流图：graphJson 非空时校验前端草稿（不落库），为空时校验已保存定义。 */
    public List<String> validateGraph(Long id, String graphJson) {
        Workflow wf = getById(id);
        WorkflowGraph graph = parseGraph(graphJson == null || graphJson.isBlank() ? wf.getGraphJson() : graphJson);
        return graphValidator.validate(graph).errors;
    }

    @Transactional
    public void delete(Long id) {
        Workflow existing = workflowMapper.selectById(id);
        if (existing == null) {
            return;
        }
        DataScopeGuard.assertWritable(existing.getCreateBy(), existing.getVisibility(), "工作流");
        workflowMapper.deleteById(id);
    }

    @Transactional
    public void setStatus(Long id, int status) {
        Workflow wf = getById(id);
        DataScopeGuard.assertWritable(wf.getCreateBy(), wf.getVisibility(), "工作流");
        wf.setStatus(status);
        wf.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(wf);
    }

    // ==================== 分享运行页 ====================

    /** 开启分享：工作流必须为启用状态；首次开启时生成访问令牌。 */
    @Transactional
    public Workflow enableShare(Long id) {
        Workflow wf = getById(id);
        DataScopeGuard.assertWritable(wf.getCreateBy(), wf.getVisibility(), "工作流");
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
        DataScopeGuard.assertWritable(wf.getCreateBy(), wf.getVisibility(), "工作流");
        wf.setShareEnabled(false);
        wf.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(wf);
        return wf;
    }

    /** 重新生成分享令牌：旧链接立即失效。 */
    @Transactional
    public Workflow regenerateShareToken(Long id) {
        Workflow wf = getById(id);
        DataScopeGuard.assertWritable(wf.getCreateBy(), wf.getVisibility(), "工作流");
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
                .eq(Workflow::getShareEnabled, 1)
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

    /** 修改可见性：仅所有者/ADMIN，PRIVATE / TEAM / PUBLIC */
    @Transactional
    public Workflow updateVisibility(Long id, String visibility) {
        Workflow wf = getById(id);
        DataScopeGuard.assertWritable(wf.getCreateBy(), wf.getVisibility(), "工作流");
        String normalized = ResourceVisibility.normalize(visibility);
        workflowMapper.update(null, new UpdateWrapper<Workflow>()
                .eq("id", id)
                .set("visibility", normalized));
        wf.setVisibility(normalized);
        return wf;
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
        DataScopeGuard.assertWritable(existing.getCreateBy(), existing.getVisibility(), "工作流");
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
        ChatRunPlan plan = prepareChatRun(workflowId, input, sessionId);
        workflowExecutor.execute(() -> executeChatRun(plan, safeMode, sink));
        return plan.run();
    }

    /**
     * 聊天工作流阻塞执行（后台作业使用）：在作业线程内同步跑完，不占用请求线程池。
     *
     * <p>事件通过 sink 输出，由作业适配器写入执行事件表，前端 attach 重放。
     */
    public WorkflowRun runForChatBlocking(Long workflowId, String input, Long sessionId,
                                          boolean safeMode, BiConsumer<String, Map<String, Object>> sink) {
        ChatRunPlan plan = prepareChatRun(workflowId, input, sessionId);
        executeChatRun(plan, safeMode, sink);
        return plan.run();
    }

    private ChatRunPlan prepareChatRun(Long workflowId, String input, Long sessionId) {
        Workflow wf = getById(workflowId);
        WorkflowGraph graph = parseGraph(wf.getGraphJson());
        GraphValidator.GraphValidationResult vr = graphValidator.validate(graph);
        assertValid(vr);
        WorkflowRun run = createRun(wf, graph, input, sessionId);
        return new ChatRunPlan(wf, graph, vr, run, input);
    }

    private void executeChatRun(ChatRunPlan plan, boolean safeMode, BiConsumer<String, Map<String, Object>> sink) {
        Workflow wf = plan.workflow();
        WorkflowRun run = plan.run();
        try {
            DomainEventPublisher.emit(sink, WorkflowEvents.WORKFLOW_START, Map.of(
                    "runId", run.getId(),
                    "workflowId", wf.getId(),
                    "workflowName", wf.getName(),
                    "totalNodes", plan.graph().getNodes().size()));
            engine.run(run, plan.graph(), plan.input(), plan.validation(), safeMode, sink);
        } catch (PauseSignal pause) {
            log.info("聊天工作流 {} 在节点暂停，等待人工确认", run.getId());
        } catch (Exception e) {
            log.error("聊天工作流运行失败 runId={}", run.getId(), e);
            markRunFailed(run.getId(), e.getMessage());
            DomainEventPublisher.emit(sink, WorkflowEvents.WORKFLOW_FAILED, Map.of(
                    "runId", run.getId(),
                    "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    private record ChatRunPlan(Workflow workflow, WorkflowGraph graph,
                               GraphValidator.GraphValidationResult validation, WorkflowRun run, String input) {
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
        run.setStatus(WorkflowConstants.STATUS_RUNNING);
        run.setCreateBy(SecurityUtils.currentUsername());
        run.setGraphSnapshot(wf.getGraphJson());
        run.setExecutedNodes(0);
        run.setTotalNodes(graph.getNodes().size());
        run.setStartTime(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    /** 无 SSE 的静默运行（定时 / Webhook 触发使用），返回运行记录（异步执行中）。 */
    public WorkflowRun runHeadless(Long workflowId, String input) {
        HeadlessRun headless = prepareHeadlessRun(workflowId, input);
        workflowExecutor.execute(() -> executeHeadless(headless, false));
        return headless.run();
    }

    /**
     * 无 SSE 的同步运行（调度层后台作业使用）：执行完成后返回运行记录。
     *
     * <p>与 {@link #runHeadless(Long, String)} 共用准备与执行逻辑，区别只在调用线程：
     * 本方法不切线程，作业的租约、超时与取消由调度层负责。
     */
    public WorkflowRun runHeadlessBlocking(Long workflowId, String input) {
        return runHeadlessBlocking(workflowId, input, null);
    }

    /**
     * 无 SSE 同步运行，并在 run 记录插入后立即回调（作业用于持久化 runId，重试时复用）。
     */
    public WorkflowRun runHeadlessBlocking(Long workflowId, String input,
                                           java.util.function.Consumer<WorkflowRun> onCreated) {
        HeadlessRun headless = prepareHeadlessRun(workflowId, input);
        if (onCreated != null) {
            onCreated.accept(headless.run());
        }
        executeHeadless(headless, true);
        return headless.run();
    }

    private HeadlessRun prepareHeadlessRun(Long workflowId, String input) {
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
        run.setStatus(WorkflowConstants.STATUS_RUNNING);
        run.setGraphSnapshot(wf.getGraphJson());
        run.setExecutedNodes(0);
        run.setTotalNodes(graph.getNodes().size());
        run.setStartTime(LocalDateTime.now());
        runMapper.insert(run);
        return new HeadlessRun(run, graph, input == null ? "" : input, vr);
    }

    private void executeHeadless(HeadlessRun headless, boolean propagateFailure) {
        BiConsumer<String, Map<String, Object>> sink = (event, payload) -> { /* 静默触发，无 SSE 订阅 */ };
        try {
            engine.run(headless.run(), headless.graph(), headless.input(), headless.validation(), sink);
        } catch (PauseSignal pause) {
            log.info("触发运行 {} 在 HITL 节点暂停，等待人工确认", headless.run().getId());
        } catch (Exception e) {
            log.error("触发运行失败 runId={}", headless.run().getId(), e);
            markRunFailed(headless.run().getId(), e.getMessage());
            if (propagateFailure) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    /** 静默运行的一次准备结果。 */
    private record HeadlessRun(WorkflowRun run, WorkflowGraph graph, String input,
                               GraphValidator.GraphValidationResult validation) {
    }

    /**
     * HITL 确认后从断点续跑，返回新的 SSE 通道。
     *
     * <p>确认校验、状态推进与扣费在同一事务内提交；异步续跑等 afterCommit 后才开始，
     * 事务回滚不会留下已扣费。</p>
     */
    @Transactional
    public SseEmitter hitlConfirm(Long runId, String userInput) {
        HitlResumeState rs = prepareHitlResume(runId, userInput, false);

        SseEmitter emitter = createEmitter(runId);
        BiConsumer<String, Map<String, Object>> sink = sinkFor(runId);
        try {
            submitResume(rs, sink, true);
        } catch (RuntimeException e) {
            // 提交失败：事务将回滚（含扣费），同时清理已注册的通道
            emitters.remove(runId, emitter);
            throw e;
        }
        return emitter;
    }

    /**
     * 聊天窗口触发的 HITL 确认：事件通过调用方提供的 sink 输出（映射为聊天 SSE 协议），
     * 不创建独立 SSE 通道。返回更新后的运行记录（续跑异步进行）。
     *
     * <p>属于对话链路：若原对话本轮已结算，续跑不再重复扣费（整轮只收一次）。</p>
     */
    @Transactional
    public WorkflowRun resumeForChat(Long runId, String userInput,
                                     BiConsumer<String, Map<String, Object>> sink) {
        HitlResumeState rs = prepareHitlResume(runId, userInput, true);
        submitResume(rs, sink, false);
        return rs.run;
    }

    /**
     * 聊天窗口 HITL 确认的阻塞执行（后台作业使用）。
     *
     * <p>确认与扣费先在独立事务中提交，再在作业线程同步执行续跑，避免长事务，也不占用请求线程。
     */
    public WorkflowRun resumeForChatBlocking(Long runId, String userInput,
                                             BiConsumer<String, Map<String, Object>> sink) {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        HitlResumeState rs = template.execute(status -> prepareHitlResume(runId, userInput, true));
        if (rs == null) {
            throw new IllegalStateException("HITL 确认事务未返回状态");
        }
        executeHitlResume(rs, sink, false);
        return rs.run;
    }

    /** 提交异步续跑：必须等确认事务提交后才真正执行。 */
    private void submitResume(HitlResumeState rs,
                              BiConsumer<String, Map<String, Object>> sink,
                              boolean completeEmitter) {
        CompletableFuture<Void> gate = commitGate();
        try {
            workflowExecutor.execute(() -> {
                if (!awaitCommit(gate)) {
                    log.error("HITL 确认事务未提交或等待超时，放弃续跑: runId={}", rs.run.getId());
                    rs.billing().finish();
                    return;
                }
                executeHitlResume(rs, sink, completeEmitter);
            });
        } catch (TaskRejectedException e) {
            // 事务内：状态推进与计数/流水随异常整体回滚，无需单独退款
            throw e;
        }
    }

    /**
     * 校验断点、原子推进确认状态并完成扣费。
     *
     * @param chatChain {@code true} 表示来自聊天窗口的续跑，需要判断原对话本轮是否已结算
     */
    private HitlResumeState prepareHitlResume(Long runId, String userInput, boolean chatChain) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException("运行记录不存在");
        if (!WorkflowConstants.STATUS_PAUSED.equals(run.getStatus())) throw new BusinessException("运行未处于暂停状态，无法确认");

        WorkflowNodeRun pending = nodeRunMapper.selectOne(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId)
                .eq(WorkflowNodeRun::getStatus, WorkflowConstants.STATUS_HITL_PENDING)
                .orderByDesc(WorkflowNodeRun::getId)
                .last("LIMIT 1"));
        if (pending == null) throw new BusinessException("没有待确认的 HITL 节点");

        String confirmInput = userInput == null ? "" : userInput;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("text", confirmInput);
        String outputJson = toJson(output);

        // 幂等闸门：同一暂停项只允许确认一次；并发/重试时其它请求拿不到行数
        if (nodeRunMapper.claimHitlPending(pending.getId(), outputJson) == 0) {
            throw new BusinessException("该 HITL 确认已处理，请刷新运行状态");
        }

        RunContext ctx = engine.parseContext(run.getContextJson());
        ctx.getNodeOutputs().put(pending.getNodeId(), output);
        ctx.getVars().put("lastOutput", confirmInput);

        run.setStatus(WorkflowConstants.STATUS_RUNNING);
        run.setContextJson(engine.serializeContext(ctx));
        run.setUpdateTime(LocalDateTime.now());
        if (runMapper.claimPausedForResume(runId, run.getContextJson()) == 0) {
            throw new BusinessException("运行未处于暂停状态，无法确认");
        }
        pending.setStatus(WorkflowConstants.STATUS_SUCCESS);
        pending.setOutputJson(outputJson);
        pending.setEndTime(LocalDateTime.now());

        // 扣费：校验通过、真正开始执行之前扣一次；与原对话同一轮时不重复扣费
        Long chatSessionId = chatChain ? run.getSessionId() : null;
        LocalDateTime roundStart = chatChain
                ? (run.getStartTime() != null ? run.getStartTime() : run.getCreateTime())
                : null;
        QuotaService.ResumeChargeReservation reservation = quotaService.reserveResumeCharge(
                SecurityUtils.currentUsername(), null, "hitl:workflow:" + runId, chatSessionId, roundStart);
        quotaService.attachSession(reservation.charge(), run.getSessionId());
        QuotaChargeTracker.Handle billing = chargeTracker.track(reservation.charge());

        WorkflowGraph graph;
        try {
            graph = objectMapper.readValue(run.getGraphSnapshot(), WorkflowGraph.class);
        } catch (Exception e) {
            throw new BusinessException("运行图快照解析失败：" + e.getMessage());
        }
        GraphValidator.GraphValidationResult vr = graphValidator.validate(graph);
        return new HitlResumeState(run, graph, vr, ctx, billing);
    }

    private void executeHitlResume(HitlResumeState rs,
                                   BiConsumer<String, Map<String, Object>> sink,
                                   boolean completeEmitter) {
        QuotaChargeTracker.Handle billing = rs.billing();
        // 首个非空最终答复下发即视为本轮有效产出，结算扣费
        BiConsumer<String, Map<String, Object>> metered = (event, payload) -> {
            if (WorkflowEvents.WORKFLOW_COMPLETE.equals(event) && payload != null) {
                Object out = payload.get("output");
                if (out != null && !String.valueOf(out).isBlank()) {
                    billing.markOutput();
                }
            }
            sink.accept(event, payload);
        };
        boolean pausedAgain = false;
        try {
            engine.resume(rs.run, rs.graph, rs.validation, rs.ctx, metered);
        } catch (PauseSignal pause) {
            pausedAgain = true;
            log.info("工作流 {} 再次暂停", rs.run.getId());
        } catch (Exception e) {
            log.error("工作流恢复执行失败 runId={}", rs.run.getId(), e);
            markRunFailed(rs.run.getId(), e.getMessage());
            DomainEventPublisher.emit(metered, WorkflowEvents.WORKFLOW_FAILED, Map.of(
                    "runId", rs.run.getId(),
                    "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
        } finally {
            // 再次停在 HITL 不算失败：保持未结算，等下一次续跑复用，避免反复扣退
            if (pausedAgain) {
                billing.finishOnPause();
            } else {
                billing.finish();
            }
            if (completeEmitter) completeEmitter(rs.run.getId());
        }
    }

    /** 事务提交闸门：afterCommit 放行；回滚或超时不放行。无事务时立即放行。 */
    private static CompletableFuture<Void> commitGate() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            gate.complete(null);
            return gate;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gate.complete(null);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    gate.completeExceptionally(new IllegalStateException("HITL 确认事务未提交"));
                }
            }
        });
        return gate;
    }

    private static boolean awaitCommit(CompletableFuture<Void> gate) {
        try {
            gate.get(COMMIT_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** HITL 续跑所需的运行状态快照 */
    private record HitlResumeState(WorkflowRun run, WorkflowGraph graph,
                                   GraphValidator.GraphValidationResult validation,
                                   RunContext ctx, QuotaChargeTracker.Handle billing) {
    }

    /**
     * 实例中断后的续跑入口（供 RUN_RESUME 作业调用）。
     *
     * <p>只接受 RUNNING 状态的 run；PAUSED 属 HITL，仍走 HITL 确认接口；
     * 终态直接返回，保证重复投递幂等。租约丢失/作业取消向上抛出，不写业务终态。
     */
    public WorkflowRun resumeInterrupted(Long runId, JobContext context) {
        // 只读快照：run 的读取与后续读写短事务解耦，长执行过程不占用事务与连接
        WorkflowRun run = readOnlyTx().execute(status -> runMapper.selectById(runId));
        if (run == null) {
            throw new BusinessException("运行记录不存在：" + runId);
        }
        if (!WorkflowConstants.STATUS_RUNNING.equals(run.getStatus())) {
            return run;
        }

        WorkflowGraph graph;
        try {
            graph = objectMapper.readValue(run.getGraphSnapshot(), WorkflowGraph.class);
        } catch (Exception e) {
            throw new BusinessException("运行图快照解析失败：" + e.getMessage());
        }
        GraphValidator.GraphValidationResult validation = graphValidator.validate(graph);
        RunContext ctx = engine.parseContext(run.getContextJson());
        // 调试运行由调试会话自己推进，调度续跑不得接管
        if (ctx.getDebugFrontier() != null) {
            return run;
        }

        try {
            // 中断态重置在引擎内以短事务落库；节点执行循环本身不在事务内
            engine.resumeInterrupted(run, graph, validation, ctx,
                    JobRunSupport.events(context), JobRunSupport.lifecycle(context));
        } catch (PauseSignal pause) {
            log.info("工作流 {} 续跑后再次进入 HITL", runId);
        } catch (LeaseLostException | ExecutionCancelledException stop) {
            throw stop;
        } catch (Exception e) {
            log.error("工作流中断续跑失败 runId={}", runId, e);
            markRunFailed(runId, e.getMessage());
            throw new BusinessException("工作流续跑失败：" + e.getMessage());
        }
        return readOnlyTx().execute(status -> runMapper.selectById(runId));
    }

    /** 只读查询事务模板：单次快照读取独立成短事务。 */
    private TransactionTemplate readOnlyTx() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    // ==================== 单步调试 ====================

    /**
     * 单步调试：一次调用推进最多 {@code maxSteps} 个节点，返回每个节点的输入/输出快照。
     * 首次调用不传 runId 会创建调试运行记录；后续调用带上 runId 从断点游标继续。
     * 调试执行与 SSE 运行链路完全独立，开关关闭时直接拒绝。
     */
    public com.wgh.rag.workflow.dto.DebugStepResult debugStep(Long workflowId, String input, Long runId,
                                                              Integer maxSteps, String stopAtNodeId) {
        if (!debugStepEnabled) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "单步调试未启用（配置项 wgh.workflow.debug.enabled=false）");
        }
        Workflow wf = getVisible(workflowId);
        DataScopeGuard.assertWritable(wf.getCreateBy(), wf.getVisibility(), "工作流");

        WorkflowGraph graph = parseGraph(wf.getGraphJson());
        GraphValidator.GraphValidationResult vr = graphValidator.validate(graph);
        assertValid(vr);

        WorkflowRun run;
        RunContext ctx;
        if (runId == null) {
            run = new WorkflowRun();
            run.setWorkflowId(wf.getId());
            run.setWorkflowVersion(wf.getVersion());
            run.setInputText(input == null ? "" : input);
            run.setStatus(WorkflowConstants.STATUS_RUNNING);
            run.setCreateBy(SecurityUtils.currentUsername());
            run.setGraphSnapshot(wf.getGraphJson());
            run.setExecutedNodes(0);
            run.setTotalNodes(graph.getNodes().size());
            run.setStartTime(LocalDateTime.now());
            runMapper.insert(run);

            ctx = new RunContext();
            ctx.getVars().put("input", input == null ? "" : input);
            ctx.getVars().put("lastOutput", "");
        } else {
            run = getRun(runId);
            if (!workflowId.equals(run.getWorkflowId())) {
                throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "调试运行记录不属于该工作流");
            }
            if (!WorkflowConstants.STATUS_DEBUG_PAUSED.equals(run.getStatus())) {
                throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "调试会话已结束，请重新开始单步调试");
            }
            // 续跑必须使用创建调试会话时的图快照，避免中途编辑导致游标与图不一致
            if (run.getGraphSnapshot() != null && !run.getGraphSnapshot().isBlank()) {
                graph = parseGraph(run.getGraphSnapshot());
                vr = graphValidator.validate(graph);
                assertValid(vr);
            }
            ctx = engine.parseContext(run.getContextJson());
        }

        int budget = maxSteps == null ? 1 : Math.max(1, Math.min(maxSteps, 50));
        return engine.debugStep(run, graph, vr, ctx, budget, stopAtNodeId);
    }

    /** 取消运行。 */
    @Transactional
    public void cancel(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException("运行记录不存在");
        if (WorkflowConstants.STATUS_COMPLETED.equals(run.getStatus())
                || WorkflowConstants.STATUS_FAILED.equals(run.getStatus())) {
            throw new BusinessException("运行已结束，无法取消");
        }
        run.setStatus(WorkflowConstants.STATUS_CANCELLED);
        run.setFinishTime(LocalDateTime.now());
        run.setDurationMs(run.getStartTime() == null ? 0L
                : java.time.Duration.between(run.getStartTime(), LocalDateTime.now()).toMillis());
        runMapper.updateById(run);

        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId)
                .in(WorkflowNodeRun::getStatus, WorkflowConstants.STATUS_RUNNING,
                        WorkflowConstants.STATUS_PENDING, WorkflowConstants.STATUS_HITL_PENDING)
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
        run.setStatus(WorkflowConstants.STATUS_FAILED);
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
