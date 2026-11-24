package com.wgh.rag.scheduler.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.execution.api.ExecutionFacade;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.scheduler.config.SchedulerProperties;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.entity.SchedulerInstance;
import com.wgh.rag.scheduler.entity.TenantSchedulePolicy;
import com.wgh.rag.scheduler.job.JobDispatcher;
import com.wgh.rag.scheduler.job.JobPayloadCodec;
import com.wgh.rag.scheduler.lane.LaneExecutors;
import com.wgh.rag.scheduler.mapper.ExecutionJobMapper;
import com.wgh.rag.scheduler.mapper.SchedulerInstanceMapper;
import com.wgh.rag.scheduler.mapper.TenantRuntimeSlotMapper;
import com.wgh.rag.scheduler.mapper.TenantSchedulePolicyMapper;
import com.wgh.rag.scheduler.policy.EffectiveSchedulePolicy;
import com.wgh.rag.scheduler.policy.PolicyCache;
import com.wgh.rag.scheduler.policy.RejectPolicy;
import com.wgh.rag.scheduler.policy.TenantSlotManager;
import com.wgh.rag.scheduler.reconcile.ReconcileReport;
import com.wgh.rag.scheduler.reconcile.ReconcileReportRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理端调度中心查询能力。
 *
 * <p>只读查询直接以作业表为准，不缓存；取消走 {@link ExecutionFacade}，
 * 与调度层既有取消语义一致（PENDING 直接落终态，LEASED/RUNNING 只置协作取消标记）。
 * 不修改状态机与租约语义。
 */
@Slf4j
@Service
public class SchedulerAdminService {

    /** 单页最大条数，避免管理端一次拉取过多大字段。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final ExecutionJobMapper mapper;

    private final SchedulerInstanceMapper instanceMapper;

    private final SchedulerProperties properties;

    private final ReconcileReportRegistry reconcileReports;

    private final JobPayloadCodec codec;

    private final ObjectProvider<ExecutionFacade> executionFacadeProvider;

    private final TenantSchedulePolicyMapper policyMapper;

    private final TenantRuntimeSlotMapper slotMapper;

    private final ObjectProvider<PolicyCache> policyCacheProvider;

    private final ObjectProvider<TenantSlotManager> slotManagerProvider;

    private final ObjectProvider<LaneExecutors> laneExecutorsProvider;

    private final ObjectProvider<JobDispatcher> jobDispatcherProvider;

    public SchedulerAdminService(ExecutionJobMapper mapper,
                                 SchedulerInstanceMapper instanceMapper,
                                 SchedulerProperties properties,
                                 ReconcileReportRegistry reconcileReports,
                                 JobPayloadCodec codec,
                                 ObjectProvider<ExecutionFacade> executionFacadeProvider,
                                 TenantSchedulePolicyMapper policyMapper,
                                 TenantRuntimeSlotMapper slotMapper,
                                 ObjectProvider<PolicyCache> policyCacheProvider,
                                 ObjectProvider<TenantSlotManager> slotManagerProvider,
                                 ObjectProvider<LaneExecutors> laneExecutorsProvider,
                                 ObjectProvider<JobDispatcher> jobDispatcherProvider) {
        this.mapper = mapper;
        this.instanceMapper = instanceMapper;
        this.properties = properties;
        this.reconcileReports = reconcileReports;
        this.codec = codec;
        this.executionFacadeProvider = executionFacadeProvider;
        this.policyMapper = policyMapper;
        this.slotMapper = slotMapper;
        this.policyCacheProvider = policyCacheProvider;
        this.slotManagerProvider = slotManagerProvider;
        this.laneExecutorsProvider = laneExecutorsProvider;
        this.jobDispatcherProvider = jobDispatcherProvider;
    }

    /**
     * 分页查询作业。
     *
     * @param status   状态（可选，按 {@link ExecutionStatus} 名称）
     * @param lane     车道（可选，按 {@link ExecutionLane} 名称）
     * @param jobType  作业类型（可选，精确匹配）
     * @param tenantId 租户（可选，精确匹配）
     * @param bizKey   业务键（可选，包含匹配）
     * @param from     计划时间起（含）
     * @param to       计划时间止（含）
     */
    public PageResult<SchedulerJobSummary> page(String status,
                                                String lane,
                                                String jobType,
                                                String tenantId,
                                                String bizKey,
                                                LocalDateTime from,
                                                LocalDateTime to,
                                                long page,
                                                long size) {
        long current = Math.max(1L, page);
        long pageSize = Math.max(1L, Math.min(MAX_PAGE_SIZE, size));

        LambdaQueryWrapper<ExecutionJob> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(ExecutionJob::getStatus, parseStatus(status));
        }
        if (StringUtils.hasText(lane)) {
            wrapper.eq(ExecutionJob::getLane, parseLane(lane));
        }
        if (StringUtils.hasText(jobType)) {
            wrapper.eq(ExecutionJob::getJobType, jobType.trim());
        }
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(ExecutionJob::getTenantId, tenantId.trim());
        }
        if (StringUtils.hasText(bizKey)) {
            wrapper.like(ExecutionJob::getBizKey, bizKey.trim());
        }
        if (from != null) {
            wrapper.ge(ExecutionJob::getScheduledAt, from);
        }
        if (to != null) {
            wrapper.le(ExecutionJob::getScheduledAt, to);
        }
        wrapper.orderByDesc(ExecutionJob::getId);

        Page<ExecutionJob> result = mapper.selectPage(Page.of(current, pageSize), wrapper);
        List<SchedulerJobSummary> rows = result.getRecords().stream().map(this::toSummary).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), rows);
    }

    /** 失败与死信列表（一次查询 FAILED + DEAD，供管理端失败页）。 */
    public PageResult<SchedulerJobSummary> failures(String jobType, String tenantId, long page, long size) {
        long current = Math.max(1L, page);
        long pageSize = Math.max(1L, Math.min(MAX_PAGE_SIZE, size));
        LambdaQueryWrapper<ExecutionJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ExecutionJob::getStatus, ExecutionStatus.FAILED, ExecutionStatus.DEAD);
        if (StringUtils.hasText(jobType)) {
            wrapper.eq(ExecutionJob::getJobType, jobType.trim());
        }
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(ExecutionJob::getTenantId, tenantId.trim());
        }
        wrapper.orderByDesc(ExecutionJob::getId);
        Page<ExecutionJob> result = mapper.selectPage(Page.of(current, pageSize), wrapper);
        List<SchedulerJobSummary> rows = result.getRecords().stream().map(this::toSummary).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), rows);
    }

    /** 作业详情（含负载、结果、租约与取消标记）。 */
    public SchedulerJobDetail detail(String executionId) {
        ExecutionJob job = requireJob(executionId);
        return new SchedulerJobDetail(
                job.getExecutionId(),
                job.getJobType(),
                job.getLane(),
                job.getStatus(),
                job.getTenantId(),
                job.getOwnerUserId(),
                job.getBizKey(),
                job.getPayloadSchemaVersion(),
                job.getPriority(),
                parseJsonObject(job.getPayloadJson(), true),
                parseJsonObject(job.getResultJson(), false),
                job.getScheduledAt(),
                job.getNotBefore(),
                job.getNotBefore(),
                job.getLeaseOwner(),
                job.getLeaseExpireTime(),
                job.getHeartbeatTime(),
                job.getAttempt(),
                job.getMaxAttempts(),
                job.getTimeoutSeconds(),
                job.getCancelRequested() != null && job.getCancelRequested() == 1,
                job.getCancelReason(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getStartTime(),
                job.getFinishTime(),
                job.getVersion(),
                job.getCreateTime(),
                job.getUpdateTime());
    }

    /** 概览统计：状态/车道分布与关键计数，数据来源为作业表。 */
    public SchedulerOverview overview() {
        Map<String, Long> statusDistribution = new LinkedHashMap<>();
        for (ExecutionStatus value : ExecutionStatus.values()) {
            statusDistribution.put(value.name(), 0L);
        }
        for (Map<String, Object> row : mapper.countGroupByStatus()) {
            String key = stringValue(row, "status");
            if (key != null) {
                statusDistribution.put(key, longValue(row, "cnt"));
            }
        }

        Map<String, Long> laneDistribution = new LinkedHashMap<>();
        for (ExecutionLane value : ExecutionLane.values()) {
            laneDistribution.put(value.name(), 0L);
        }
        for (Map<String, Object> row : mapper.countGroupByLane()) {
            String key = stringValue(row, "lane");
            if (key != null) {
                laneDistribution.put(key, longValue(row, "cnt"));
            }
        }

        return new SchedulerOverview(
                LocalDateTime.now(),
                mapper.countAll(),
                statusDistribution,
                laneDistribution,
                mapper.countPendingAll(),
                mapper.countInFlight(),
                mapper.countFailedAll(),
                mapper.countDeadAll(),
                mapper.countTimeoutOrLeaseExpired());
    }

    /**
     * 实例视图（集群视角）。
     *
     * <p>存活判定：未主动下线且心跳距今不超过 {@code wgh.scheduler.instance-liveness-ttl}。
     * 失联实例会显示为 {@code alive=false}，其作业由调度循环提前回收。
     */
    public List<SchedulerInstanceView> instances() {
        List<SchedulerInstance> rows = instanceMapper.selectAllInstances();
        LocalDateTime now = instanceMapper.now();
        if (now == null) {
            now = LocalDateTime.now();
        }
        long livenessSeconds = Math.max(1L, properties.getInstanceLivenessTtl().toSeconds());
        List<SchedulerInstanceView> views = new ArrayList<>(rows.size());
        for (SchedulerInstance row : rows) {
            long ageSeconds = row.getLastHeartbeat() == null
                    ? -1L
                    : Math.max(0L, Duration.between(row.getLastHeartbeat(), now).getSeconds());
            boolean alive = row.getStoppedAt() == null && ageSeconds <= livenessSeconds;
            views.add(new SchedulerInstanceView(
                    row.getInstanceId(),
                    row.getStartedAt(),
                    row.getLastHeartbeat(),
                    row.getStoppedAt(),
                    alive,
                    ageSeconds,
                    parseLanes(row.getLanesJson())));
        }
        return views;
    }

    /** 最近一次作业/业务对账报告；本实例尚未执行过时返回空报告。 */
    public ReconcileReport reconcileReport() {
        return reconcileReports.last().orElseGet(ReconcileReport::notRun);
    }

    /**
     * 取消作业。
     *
     * <p>不可取消的状态返回明确业务错误，而不是落入 500。
     */
    public SchedulerCancelResult cancel(String executionId, String reason) {
        ExecutionJob job = requireJob(executionId);
        ExecutionStatus status = job.getStatus();
        if (status == null) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "作业状态缺失，不可取消");
        }
        if (status.isTerminal()) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "作业已处于终态（" + status.name() + "），不可取消");
        }
        if (status != ExecutionStatus.PENDING
                && status != ExecutionStatus.LEASED
                && status != ExecutionStatus.RUNNING) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "作业当前状态不可取消: " + status.name());
        }

        ExecutionFacade facade = executionFacadeProvider.getIfAvailable();
        if (facade == null) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "调度中心未启用，无法取消作业");
        }

        String cancelReason = StringUtils.hasText(reason) ? reason.trim() : "管理端手动取消";
        boolean accepted = facade.cancel(executionId, cancelReason);
        ExecutionJob latest = mapper.selectByExecutionId(executionId);

        if (!accepted) {
            ExecutionStatus latestStatus = latest == null ? null : latest.getStatus();
            if (latestStatus != null && latestStatus.isTerminal()) {
                throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                        "作业已处于终态（" + latestStatus.name() + "），不可取消");
            }
            throw new BusinessException(ResultCode.FAIL, "取消请求未被受理，请刷新后重试");
        }

        ExecutionStatus latestStatus = latest == null ? status : latest.getStatus();
        boolean cancelRequested = latest != null && latest.getCancelRequested() != null
                && latest.getCancelRequested() == 1;
        String message = latestStatus == ExecutionStatus.CANCELLED
                ? "作业已取消"
                : "已提交取消请求，执行体将在协作点退出";
        log.info("管理端取消作业 executionId={} reason={} status={}", executionId, cancelReason, latestStatus);
        return new SchedulerCancelResult(executionId, latestStatus, cancelRequested, true, message);
    }

    // ===== 租户策略（S2） =====

    /**
     * 租户策略视图。
     *
     * @param tenantId 租户标识；为空时返回全部自定义策略行
     */
    public List<SchedulerPolicyView> policies(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return policyMapper.selectAllPolicies().stream()
                    .map(row -> policyView(row.getTenantId(), row.getLane(), row))
                    .toList();
        }
        String tenant = normalizeTenant(tenantId);
        Map<ExecutionLane, TenantSchedulePolicy> rows = new java.util.EnumMap<>(ExecutionLane.class);
        for (TenantSchedulePolicy row : policyMapper.selectByTenant(tenant)) {
            if (row.getLane() != null) {
                rows.put(row.getLane(), row);
            }
        }
        List<SchedulerPolicyView> views = new ArrayList<>(2);
        views.add(policyView(tenant, ExecutionLane.INTERACTIVE, rows.get(ExecutionLane.INTERACTIVE)));
        views.add(policyView(tenant, ExecutionLane.BACKGROUND, rows.get(ExecutionLane.BACKGROUND)));
        return views;
    }

    /**
     * 新建或更新租户策略。
     *
     * <p>更新必须携带 {@code version}（乐观锁）；冲突返回 {@code OPERATION_NOT_ALLOWED}，由管理端提示刷新。
     * 写入成功后立即重载本地策略缓存，不等下一个刷新周期。
     */
    public List<SchedulerPolicyView> updatePolicies(String tenantId, List<SchedulerPolicyUpdate> updates) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户标识不能为空");
        }
        if (updates == null || updates.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "策略更新内容不能为空");
        }
        String tenant = normalizeTenant(tenantId);
        for (SchedulerPolicyUpdate update : updates) {
            if (!StringUtils.hasText(update.lane())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "策略更新必须指定车道");
            }
            ExecutionLane lane = parseLane(update.lane());
            validatePolicyUpdate(update);
            TenantSchedulePolicy existing = policyMapper.selectOne(new LambdaQueryWrapper<TenantSchedulePolicy>()
                    .eq(TenantSchedulePolicy::getTenantId, tenant)
                    .eq(TenantSchedulePolicy::getLane, lane)
                    .last("LIMIT 1"));
            if (existing == null) {
                policyMapper.insert(newPolicy(tenant, lane, update));
                continue;
            }
            int expected = update.version() != null ? update.version()
                    : (existing.getVersion() == null ? 0 : existing.getVersion());
            if (existing.getVersion() != null && expected != existing.getVersion()) {
                throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                        "策略已被其它管理员修改（版本 " + existing.getVersion() + "），请刷新后重试");
            }
            applyPolicyUpdate(existing, update);
            if (policyMapper.updateWithVersion(existing, expected) == 0) {
                throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                        "策略已被其它管理员修改，请刷新后重试");
            }
        }
        PolicyCache cache = policyCacheProvider.getIfAvailable();
        if (cache != null) {
            cache.reload();
        }
        log.info("租户调度策略已更新 tenantId={} lanes={}", tenant, updates.size());
        return policies(tenant);
    }

    /** 策略试算：按当前负载与拟定策略给出预计准入结论，不落库。 */
    public List<SchedulerPolicyDryRunResult> dryRun(String tenantId, List<SchedulerPolicyUpdate> updates) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户标识不能为空");
        }
        if (updates == null || updates.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "试算内容不能为空");
        }
        String tenant = normalizeTenant(tenantId);
        List<SchedulerPolicyDryRunResult> results = new ArrayList<>(updates.size());
        for (SchedulerPolicyUpdate update : updates) {
            if (!StringUtils.hasText(update.lane())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "试算必须指定车道");
            }
            ExecutionLane lane = parseLane(update.lane());
            validatePolicyUpdate(update);
            EffectiveSchedulePolicy current = resolvePolicy(tenant, lane);
            int maxConcurrent = update.maxConcurrent() != null
                    ? update.maxConcurrent() : current.maxConcurrent();
            int maxQueue = update.maxQueue() != null ? update.maxQueue() : current.maxQueue();
            RejectPolicy rejectPolicy = update.rejectPolicy() == null
                    ? current.rejectPolicy() : RejectPolicy.parse(update.rejectPolicy(), current.rejectPolicy());
            long running = mapper.countTenantActive(tenant, lane.name());
            long queued = mapper.countPendingByTenant(tenant, lane.name());
            String decision;
            String reason;
            if (running < maxConcurrent) {
                decision = "PERMIT";
                reason = "并发未达上限（" + running + "/" + maxConcurrent + "）";
            } else if (rejectPolicy == RejectPolicy.REJECT) {
                decision = "REJECT";
                reason = "并发已满且拒绝策略为 REJECT（" + running + "/" + maxConcurrent + "）";
            } else if (queued >= maxQueue) {
                decision = "REJECT";
                reason = "并发已满且排队已达上限（" + queued + "/" + maxQueue + "）";
            } else {
                decision = "QUEUE";
                reason = "并发已满但可排队（" + queued + "/" + maxQueue + "）";
            }
            results.add(new SchedulerPolicyDryRunResult(tenant, lane.name(), maxConcurrent, maxQueue,
                    rejectPolicy.name(), running, queued, decision, reason));
        }
        return results;
    }

    /** 当前实例运行时快照：车道水位、租户槽位、槽位漂移与策略缓存条数。 */
    public Map<String, Object> runtime() {
        Map<String, Object> result = new LinkedHashMap<>();
        LaneExecutors executors = laneExecutorsProvider.getIfAvailable();
        result.put("lanes", executors == null ? List.of() : executors.snapshots());
        TenantSlotManager slotManager = slotManagerProvider.getIfAvailable();
        result.put("slotDrift", slotManager == null ? 0L : slotManager.drift());
        PolicyCache cache = policyCacheProvider.getIfAvailable();
        result.put("cachedPolicyCount", cache == null ? 0 : cache.cachedPolicyCount());
        try {
            result.put("tenantSlots", slotMapper.selectAllSlots());
        } catch (RuntimeException e) {
            result.put("tenantSlots", List.of());
            log.debug("租户槽位读取失败（可能未执行 20261124-01/04 迁移）: {}", e.getMessage());
        }
        return result;
    }

    /** 管理端重试：失败/死信/取消作业回到队列。 */
    public SchedulerJobDetail retryJob(String executionId) {
        ExecutionJob job = requireJob(executionId);
        ExecutionStatus status = job.getStatus();
        if (status != ExecutionStatus.FAILED && status != ExecutionStatus.DEAD
                && status != ExecutionStatus.CANCELLED) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "仅 FAILED/DEAD/CANCELLED 作业可重试，当前状态: " + (status == null ? "未知" : status.name()));
        }
        if (mapper.retryJob(job.getId()) == 0) {
            throw new BusinessException(ResultCode.FAIL, "作业状态已变化，请刷新后重试");
        }
        kick();
        log.info("管理端重试作业 executionId={} previousStatus={}", executionId, status);
        return detail(executionId);
    }

    /** 管理端强制回收：活跃作业回到 PENDING，由调度循环重新领取。 */
    public SchedulerJobDetail reclaimJob(String executionId, String reason) {
        ExecutionJob job = requireJob(executionId);
        ExecutionStatus status = job.getStatus();
        if (status != ExecutionStatus.LEASED && status != ExecutionStatus.RUNNING) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "仅 LEASED/RUNNING 作业可强制回收，当前状态: " + (status == null ? "未知" : status.name()));
        }
        String reclaimReason = StringUtils.hasText(reason) ? reason.trim() : "管理端强制回收";
        if (mapper.reclaimJob(job.getId(), reclaimReason) == 0) {
            throw new BusinessException(ResultCode.FAIL, "作业状态已变化，请刷新后重试");
        }
        kick();
        log.info("管理端强制回收作业 executionId={} previousStatus={}", executionId, status);
        return detail(executionId);
    }

    private SchedulerPolicyView policyView(String tenantId, ExecutionLane lane, TenantSchedulePolicy row) {
        EffectiveSchedulePolicy defaults = EffectiveSchedulePolicy.defaults(tenantId, lane, properties);
        long running = mapper.countTenantActive(tenantId, lane.name());
        long queued = mapper.countPendingByTenant(tenantId, lane.name());
        if (row == null) {
            return new SchedulerPolicyView(tenantId, lane.name(), true,
                    defaults.maxConcurrent(), defaults.maxQueue(), defaults.weight(), defaults.priorityBoost(),
                    defaults.rejectPolicy().name(), defaults.queueTimeoutSeconds(), defaults.dailyJobLimit(),
                    defaults.ratePerMinute(), defaults.timeWindowJson(), null, 0, false, running, queued, null);
        }
        return new SchedulerPolicyView(tenantId, lane.name(),
                row.getEnabled() == null || row.getEnabled() != 0,
                row.getMaxConcurrent() == null ? defaults.maxConcurrent() : row.getMaxConcurrent(),
                row.getMaxQueue() == null ? defaults.maxQueue() : row.getMaxQueue(),
                row.getWeight() == null ? defaults.weight() : row.getWeight(),
                row.getPriorityBoost() == null ? 0 : row.getPriorityBoost(),
                row.getRejectPolicy() == null ? defaults.rejectPolicy().name() : row.getRejectPolicy(),
                row.getQueueTimeoutSeconds() == null ? defaults.queueTimeoutSeconds() : row.getQueueTimeoutSeconds(),
                row.getDailyJobLimit() == null ? 0 : row.getDailyJobLimit(),
                row.getRatePerMinute() == null ? 0 : row.getRatePerMinute(),
                row.getTimeWindowJson(), row.getRemark(),
                row.getVersion() == null ? 0 : row.getVersion(), true,
                running, queued, row.getUpdateTime());
    }

    private TenantSchedulePolicy newPolicy(String tenantId, ExecutionLane lane, SchedulerPolicyUpdate update) {
        EffectiveSchedulePolicy defaults = EffectiveSchedulePolicy.defaults(tenantId, lane, properties);
        TenantSchedulePolicy policy = new TenantSchedulePolicy();
        policy.setTenantId(tenantId);
        policy.setLane(lane);
        policy.setEnabled(update.enabled() == null || update.enabled() ? 1 : 0);
        policy.setMaxConcurrent(update.maxConcurrent() == null ? defaults.maxConcurrent() : update.maxConcurrent());
        policy.setMaxQueue(update.maxQueue() == null ? defaults.maxQueue() : update.maxQueue());
        policy.setWeight(update.weight() == null ? defaults.weight() : update.weight());
        policy.setPriorityBoost(update.priorityBoost() == null ? 0 : update.priorityBoost());
        policy.setRejectPolicy(update.rejectPolicy() == null
                ? defaults.rejectPolicy().name() : update.rejectPolicy().trim().toUpperCase(Locale.ROOT));
        policy.setQueueTimeoutSeconds(update.queueTimeoutSeconds() == null
                ? defaults.queueTimeoutSeconds() : update.queueTimeoutSeconds());
        policy.setDailyJobLimit(update.dailyJobLimit() == null ? 0 : update.dailyJobLimit());
        policy.setRatePerMinute(update.ratePerMinute() == null ? 0 : update.ratePerMinute());
        policy.setTimeWindowJson(update.timeWindowJson());
        policy.setRemark(update.remark());
        policy.setVersion(0);
        return policy;
    }

    private void applyPolicyUpdate(TenantSchedulePolicy policy, SchedulerPolicyUpdate update) {
        if (update.enabled() != null) {
            policy.setEnabled(update.enabled() ? 1 : 0);
        }
        if (update.maxConcurrent() != null) {
            policy.setMaxConcurrent(update.maxConcurrent());
        }
        if (update.maxQueue() != null) {
            policy.setMaxQueue(update.maxQueue());
        }
        if (update.weight() != null) {
            policy.setWeight(update.weight());
        }
        if (update.priorityBoost() != null) {
            policy.setPriorityBoost(update.priorityBoost());
        }
        if (update.rejectPolicy() != null) {
            policy.setRejectPolicy(update.rejectPolicy().trim().toUpperCase(Locale.ROOT));
        }
        if (update.queueTimeoutSeconds() != null) {
            policy.setQueueTimeoutSeconds(update.queueTimeoutSeconds());
        }
        if (update.dailyJobLimit() != null) {
            policy.setDailyJobLimit(update.dailyJobLimit());
        }
        if (update.ratePerMinute() != null) {
            policy.setRatePerMinute(update.ratePerMinute());
        }
        if (update.timeWindowJson() != null) {
            policy.setTimeWindowJson(update.timeWindowJson());
        }
        if (update.remark() != null) {
            policy.setRemark(update.remark());
        }
    }

    private void validatePolicyUpdate(SchedulerPolicyUpdate update) {
        if (update.maxConcurrent() != null && update.maxConcurrent() <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "并发上限必须大于 0");
        }
        if (update.maxQueue() != null && update.maxQueue() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "排队上限不能为负数");
        }
        if (update.weight() != null && (update.weight() < 1 || update.weight() > 100)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "权重必须在 1-100 之间");
        }
        if (update.rejectPolicy() != null) {
            try {
                RejectPolicy.valueOf(update.rejectPolicy().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "拒绝策略只能是 REJECT 或 QUEUE");
            }
        }
        if (update.queueTimeoutSeconds() != null && update.queueTimeoutSeconds() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "排队超时不能为负数");
        }
    }

    private EffectiveSchedulePolicy resolvePolicy(String tenantId, ExecutionLane lane) {
        PolicyCache cache = policyCacheProvider.getIfAvailable();
        return cache == null
                ? EffectiveSchedulePolicy.defaults(tenantId, lane, properties)
                : cache.resolve(tenantId, lane);
    }

    private String normalizeTenant(String tenantId) {
        PolicyCache cache = policyCacheProvider.getIfAvailable();
        return cache == null ? tenantId.trim() : cache.normalize(tenantId);
    }

    private void kick() {
        JobDispatcher dispatcher = jobDispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            dispatcher.kick();
        }
    }

    private ExecutionJob requireJob(String executionId) {
        if (!StringUtils.hasText(executionId)) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "执行标识不能为空");
        }
        ExecutionJob job = mapper.selectByExecutionId(executionId.trim());
        if (job == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "作业不存在: " + executionId);
        }
        return job;
    }

    private SchedulerJobSummary toSummary(ExecutionJob job) {
        return new SchedulerJobSummary(
                job.getExecutionId(),
                job.getJobType(),
                job.getLane(),
                job.getStatus(),
                job.getAttempt(),
                job.getMaxAttempts(),
                job.getScheduledAt(),
                job.getStartTime(),
                job.getFinishTime(),
                job.getErrorCode(),
                job.getTenantId(),
                job.getOwnerUserId(),
                job.getBizKey(),
                job.getCreateTime());
    }

    /** 车道快照解析；脏数据返回空对象，不影响实例视图。 */
    private Map<String, Object> parseLanes(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return codec.fromJson(json);
        } catch (IllegalArgumentException e) {
            log.debug("实例车道快照无法解析，返回空对象: {}", e.getMessage());
            return Map.of();
        }
    }

    /** JSON 对象解析：负载脏数据回退为原文，结果为空返回空对象。 */
    private Map<String, Object> parseJsonObject(String json, boolean required) {        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return codec.fromJson(json);
        } catch (IllegalArgumentException e) {
            if (!required) {
                log.debug("作业结果 JSON 无法解析，返回原文: {}", e.getMessage());
            }
            return Map.of("_raw", json);
        }
    }

    private static ExecutionStatus parseStatus(String status) {
        try {
            return ExecutionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "作业状态不合法: " + status);
        }
    }

    private static ExecutionLane parseLane(String lane) {
        try {
            return ExecutionLane.valueOf(lane.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "执行车道不合法: " + lane);
        }
    }

    private static String stringValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private static long longValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(key) || entry.getValue() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}
