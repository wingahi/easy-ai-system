package com.wgh.rag.scheduler.job;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.common.log.MdcContext;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.mapper.ExecutionJobMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 作业持久化门面。
 *
 * <p>所有状态迁移都通过 Mapper 的条件 UPDATE 完成，并在返回「影响行数为 0」时视为租约丢失；
 * 这里不缓存任何状态，确保跨实例读取的是数据库事实。
 */
@Slf4j
@Component
public class JobStore {

    private static final int MAX_ERROR_MESSAGE = 2000;

    private final ExecutionJobMapper mapper;

    private final JobPayloadCodec codec;

    public JobStore(ExecutionJobMapper mapper, JobPayloadCodec codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    /**
     * 提交作业。
     *
     * <p>带 {@code bizKey} 时按 (jobType, bizKey) 幂等：已存在则直接返回既有记录，不重复插入。
     *
     * @param request        执行请求
     * @param maxAttempts    最大尝试次数
     * @param timeoutSeconds 超时秒数，可为 {@code null}
     * @return 已持久化的作业
     */
    public ExecutionJob submit(ExecutionRequest request, int maxAttempts, Integer timeoutSeconds) {
        String bizKey = request.bizKey();
        if (bizKey != null && !bizKey.isBlank()) {
            ExecutionJob existing = mapper.selectByBizKey(request.jobType(), bizKey);
            if (existing != null) {
                return existing;
            }
        }
        ExecutionJob job = new ExecutionJob();
        job.setExecutionId(UUID.randomUUID().toString());
        // 提交线程若处于链路中，把 traceId 一并持久化；执行实例恢复后日志可跨实例关联
        job.setTraceId(MdcContext.currentTraceId());
        job.setJobType(request.jobType());
        job.setLane(request.lane());
        job.setTenantId(request.tenantId());
        job.setOwnerUserId(request.ownerUserId());
        job.setBizKey(bizKey);
        JobPayload payload = request.payload();
        job.setPayloadJson(codec.toJson(payload.data()));
        job.setPayloadSchemaVersion(payload.schemaVersion());
        job.setPriority(request.priority());
        job.setStatus(ExecutionStatus.PENDING);
        job.setScheduledAt(toLocal(request.scheduledAt()));
        job.setAttempt(0);
        job.setMaxAttempts(Math.max(1, maxAttempts));
        job.setTimeoutSeconds(timeoutSeconds);
        job.setCancelRequested(0);
        job.setVersion(0);
        try {
            mapper.insert(job);
            return job;
        } catch (DuplicateKeyException e) {
            ExecutionJob existing = bizKey == null ? null : mapper.selectByBizKey(request.jobType(), bizKey);
            if (existing == null) {
                throw e;
            }
            log.debug("作业已存在，按幂等键复用 jobType={} bizKey={} executionId={}",
                    request.jobType(), bizKey, existing.getExecutionId());
            return existing;
        }
    }

    /**
     * 登记一条已在进程内处于 RUNNING 的交互式运行态。
     *
     * <p>与 {@link #submit} 的区别：不进入 PENDING、不等待领取；{@code attempt=maxAttempts=1} 使既有
     * 租约过期/实例失联回收直接落 {@code DEAD} 终态，避免 INTERACTIVE 车道出现无人领取的 PENDING。
     *
     * @param request    执行请求（车道固定为 INTERACTIVE）
     * @param leaseOwner 持有者实例标识
     * @param leaseTtl   租约 TTL；对话无周期心跳，需覆盖单次执行上限
     * @return 已持久化的运行态记录
     */
    public ExecutionJob registerRunning(ExecutionRequest request, String leaseOwner, Duration leaseTtl) {
        LocalDateTime now = LocalDateTime.now();
        ExecutionJob job = new ExecutionJob();
        job.setExecutionId(UUID.randomUUID().toString());
        job.setTraceId(MdcContext.currentTraceId());
        job.setJobType(request.jobType());
        job.setLane(ExecutionLane.INTERACTIVE);
        job.setTenantId(request.tenantId());
        job.setOwnerUserId(request.ownerUserId());
        job.setBizKey(request.bizKey());
        JobPayload payload = request.payload();
        job.setPayloadJson(codec.toJson(payload.data()));
        job.setPayloadSchemaVersion(payload.schemaVersion());
        job.setPriority(request.priority());
        job.setStatus(ExecutionStatus.RUNNING);
        job.setScheduledAt(now);
        job.setStartTime(now);
        job.setHeartbeatTime(now);
        job.setLeaseOwner(leaseOwner);
        job.setLeaseExpireTime(now.plus(leaseTtl == null ? Duration.ofMinutes(35) : leaseTtl));
        job.setAttempt(1);
        job.setMaxAttempts(1);
        job.setCancelRequested(0);
        job.setVersion(0);
        mapper.insert(job);
        return job;
    }

    /**
     * 注销交互式运行态（正常结束/停止/异常均走这里）。
     *
     * <p>物理删除，不保留终态历史：该行只服务于「进行中」查询，保留会随对话量线性膨胀。
     * 删除条件带实例归属，已被回收/接管的行不受影响。
     *
     * @return {@code true} 表示本次删除生效
     */
    public boolean finishInteractive(Long id, String leaseOwner) {
        if (id == null) {
            return false;
        }
        return mapper.deleteRunningInteractive(id, leaseOwner) > 0;
    }

    /** 清理崩溃残留的终态交互式运行态记录。 */
    public int purgeTerminalInteractiveRuns(Duration retention) {
        long seconds = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofHours(24).toSeconds() : Math.max(60L, retention.toSeconds());
        return mapper.purgeTerminalInteractiveRuns(seconds);
    }

    /** 按执行标识查作业。 */
    public Optional<ExecutionJob> find(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByExecutionId(executionId));
    }

    /** 按执行标识查快照。 */
    public Optional<ExecutionSnapshot> query(String executionId) {
        return find(executionId).map(this::toSnapshot);
    }

    /**
     * 请求取消。
     *
     * @return {@code true} 表示已置取消标记或已置终态
     */
    public boolean cancel(String executionId, String reason) {
        Optional<ExecutionJob> found = find(executionId);
        if (found.isEmpty()) {
            return false;
        }
        ExecutionJob job = found.get();
        if (job.getStatus() != null && job.getStatus().isTerminal()) {
            return false;
        }
        if (mapper.cancelPending(job.getId(), reason) > 0) {
            return true;
        }
        return mapper.requestCancel(job.getId(), reason) > 0;
    }

    /** 领取一条到期作业。 */
    public Optional<ExecutionJob> claim(ExecutionLane lane, String owner, Duration leaseTtl) {
        Long id = mapper.claimNext(lane.name(), owner, seconds(leaseTtl));
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id));
    }

    public boolean markRunning(Long id, String owner) {
        return mapper.markRunning(id, owner) > 0;
    }

    public boolean renewLease(Long id, String owner, Duration leaseTtl) {
        return mapper.renewLease(id, owner, seconds(leaseTtl)) > 0;
    }

    public boolean complete(Long id, String owner, Map<String, Object> result) {
        return mapper.complete(id, owner, codec.toJson(result)) > 0;
    }

    /** 执行中持久化进度；租约丢失返回 {@code false}。 */
    public boolean saveResult(Long id, String owner, Map<String, Object> result) {
        return mapper.updateResult(id, owner, codec.toJson(result)) > 0;
    }

    /** 读取已持久化的执行进度；解析失败按空进度处理，避免历史脏数据阻断续跑。 */
    public Map<String, Object> resultOf(ExecutionJob job) {
        if (job == null || job.getResultJson() == null || job.getResultJson().isBlank()) {
            return Map.of();
        }
        try {
            return codec.fromJson(job.getResultJson());
        } catch (IllegalArgumentException e) {
            log.warn("作业进度 JSON 无法解析，按空进度处理 executionId={}", job.getExecutionId());
            return Map.of();
        }
    }

    /**
     * 落失败态。
     *
     * @param status  FAILED（可重试）或 DEAD（死信）
     * @param retryAt 下次可领取时间；DEAD 时传 {@code null}
     */
    public boolean fail(Long id, String owner, ExecutionStatus status, String errorCode, String errorMessage,
                        Instant retryAt) {
        LocalDateTime notBefore = retryAt == null ? null : toLocal(retryAt);
        LocalDateTime finishTime = status == ExecutionStatus.DEAD ? LocalDateTime.now() : null;
        return mapper.fail(id, owner, status.name(), errorCode, truncate(errorMessage), notBefore, finishTime) > 0;
    }

    /** 持有者落取消终态。 */
    public boolean cancelOwned(Long id, String owner, String reason) {
        return mapper.cancelOwned(id, owner, reason) > 0;
    }

    /** 放弃本次领取（单飞锁未抢到 / Redis 不可用），按延迟回到 PENDING。 */
    public boolean releaseClaim(Long id, String owner, Duration delay) {
        LocalDateTime notBefore = toLocal(Instant.now().plus(delay == null ? Duration.ZERO : delay));
        return mapper.releaseClaim(id, owner, notBefore) > 0;
    }

    /** 是否已请求取消。 */
    public boolean isCancelRequested(Long id) {
        Integer flag = mapper.selectCancelRequested(id);
        return flag != null && flag == 1;
    }

    public long countPending(ExecutionLane lane) {
        return mapper.countPending(lane.name());
    }

    public long countTenantActive(String tenantId, ExecutionLane lane) {
        return mapper.countTenantActive(tenantId, lane.name());
    }

    public int reclaimExpired() {
        return mapper.reclaimExpired();
    }

    /**
     * 回收指定实例持有的活跃作业（实例失联/已下线）。
     *
     * <p>不检查 {@code lease_expire_time}：实例心跳已过期即可判定失联，
     * 避免「实例已死但租约未到期」造成长时间悬空。
     */
    public int reclaimDeadOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return 0;
        }
        return mapper.reclaimByOwner(owner);
    }

    public int requeueFailed() {
        return mapper.requeueFailed();
    }

    public int expireQueued(Duration queueTimeout) {
        return mapper.expireQueued(seconds(queueTimeout));
    }

    /** 作业 → 统一快照。 */
    public ExecutionSnapshot toSnapshot(ExecutionJob job) {
        Map<String, Object> result = safeResult(job.getResultJson());
        String runId = result.get("runId") instanceof String value ? value : null;
        return new ExecutionSnapshot(
                job.getExecutionId(),
                runId,
                job.getJobType(),
                job.getLane(),
                job.getStatus(),
                job.getTenantId(),
                job.getOwnerUserId(),
                job.getBizKey(),
                job.getAttempt() == null ? 0 : job.getAttempt(),
                Map.of(),
                job.getErrorCode(),
                toInstant(job.getScheduledAt()),
                toInstant(job.getStartTime()),
                toInstant(job.getFinishTime()));
    }

    /**
     * 作业负载。
     *
     * @throws IllegalArgumentException payload 无法解析，调用方应按 {@code INCOMPATIBLE_PAYLOAD} 处置
     */
    public JobPayload payloadOf(ExecutionJob job) {
        int schemaVersion = job.getPayloadSchemaVersion() == null
                ? JobPayload.CURRENT_SCHEMA_VERSION : job.getPayloadSchemaVersion();
        return new JobPayload(schemaVersion, codec.fromJson(job.getPayloadJson()));
    }

    private Map<String, Object> safeResult(String json) {
        try {
            return new LinkedHashMap<>(codec.fromJson(json));
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static long seconds(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return 60L;
        }
        return Math.max(1L, duration.toSeconds());
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? LocalDateTime.now() : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_MESSAGE) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE);
    }
}
