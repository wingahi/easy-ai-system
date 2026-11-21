package com.wgh.rag.scheduler.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.execution.api.ExecutionFacade;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.job.JobPayloadCodec;
import com.wgh.rag.scheduler.mapper.ExecutionJobMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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

    private final JobPayloadCodec codec;

    private final ObjectProvider<ExecutionFacade> executionFacadeProvider;

    public SchedulerAdminService(ExecutionJobMapper mapper,
                                 JobPayloadCodec codec,
                                 ObjectProvider<ExecutionFacade> executionFacadeProvider) {
        this.mapper = mapper;
        this.codec = codec;
        this.executionFacadeProvider = executionFacadeProvider;
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

    /** JSON 对象解析：负载脏数据回退为原文，结果为空返回空对象。 */
    private Map<String, Object> parseJsonObject(String json, boolean required) {
        if (!StringUtils.hasText(json)) {
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
