package com.wgh.rag.execution.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 执行提交请求。
 *
 * <p>提交时刻即冻结执行上下文（租户、属主、负载、优先级、超时），执行期不得再回读
 * {@code ThreadLocal}（登录态、MDC、租户上下文均可能在异步线程中丢失）。
 *
 * @param jobType     作业类型，对应 {@code JobHandler#jobType()}；同一类型可对应多条业务记录
 * @param lane        执行车道
 * @param tenantId    租户标识，不允许为空；单租户场景使用 {@link #DEFAULT_TENANT}
 * @param ownerUserId 发起人用户标识，系统任务为 {@code null}
 * @param bizKey      业务幂等键（如 {@code workflow:123}），可为 {@code null}
 * @param payload     作业负载
 * @param priority    优先级，数值越大越优先，默认 0
 * @param scheduledAt 期望开始时间，{@code null} 表示立即
 * @param timeout     单次执行超时，{@code null} 表示使用车道默认值
 */
public record ExecutionRequest(
        String jobType,
        ExecutionLane lane,
        String tenantId,
        String ownerUserId,
        String bizKey,
        JobPayload payload,
        int priority,
        Instant scheduledAt,
        Duration timeout) {

    /** 单租户场景的默认租户标识。 */
    public static final String DEFAULT_TENANT = "default";

    public ExecutionRequest {
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType 不能为空");
        }
        Objects.requireNonNull(lane, "lane");
        tenantId = (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT : tenantId;
        payload = payload == null ? JobPayload.empty() : payload;
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
    }

    /**
     * 创建构建器。
     *
     * @param jobType 作业类型
     * @param lane    执行车道
     * @return 构建器
     */
    public static Builder builder(String jobType, ExecutionLane lane) {
        return new Builder(jobType, lane);
    }

    /** {@link ExecutionRequest} 构建器。 */
    public static final class Builder {

        private final String jobType;
        private final ExecutionLane lane;
        private String tenantId = DEFAULT_TENANT;
        private String ownerUserId;
        private String bizKey;
        private JobPayload payload = JobPayload.empty();
        private int priority;
        private Instant scheduledAt;
        private Duration timeout;

        private Builder(String jobType, ExecutionLane lane) {
            this.jobType = jobType;
            this.lane = lane;
        }

        public Builder tenant(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder owner(String ownerUserId) {
            this.ownerUserId = ownerUserId;
            return this;
        }

        public Builder bizKey(String bizKey) {
            this.bizKey = bizKey;
            return this;
        }

        public Builder payload(JobPayload payload) {
            this.payload = payload;
            return this;
        }

        public Builder payload(Map<String, Object> data) {
            this.payload = JobPayload.of(data);
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder scheduledAt(Instant scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public ExecutionRequest build() {
            return new ExecutionRequest(jobType, lane, tenantId, ownerUserId, bizKey,
                    payload, priority, scheduledAt, timeout);
        }
    }
}
