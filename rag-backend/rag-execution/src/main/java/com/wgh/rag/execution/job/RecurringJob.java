package com.wgh.rag.execution.job;

import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.JobPayload;

import java.time.Duration;

/**
 * 周期性维护作业声明。
 *
 * <p>调度层按本接口收集各类「需要周期提交」的维护作业（实现方通常同时实现 {@link JobHandler}）。
 * 每个周期只提交一条作业记录，执行仍走作业表 + 租约 + 维护车道单飞，不在业务侧另起
 * {@code @Scheduled} 或进程内锁。</p>
 *
 * <p>多实例同时提交同一周期时，由作业表 {@code (job_type, biz_key)} 唯一键去重；
 * 执行侧由维护车道的 Redis 单飞保证跨实例只跑一份。</p>
 */
public interface RecurringJob {

    /**
     * 作业类型，必须与对应 {@link JobHandler#jobType()} 一致。
     */
    String jobType();

    /**
     * 提交间隔；调度层按时间分桶（{@code epochMillis / interval}）生成业务幂等键。
     */
    Duration interval();

    /**
     * 执行车道；维护型周期作业默认走 {@link ExecutionLane#MAINTENANCE}。
     */
    default ExecutionLane lane() {
        return ExecutionLane.MAINTENANCE;
    }

    /**
     * 作业负载；周期作业通常为空。
     */
    default JobPayload payload() {
        return JobPayload.empty();
    }

    /**
     * 优先级，数值越大越优先。
     */
    default int priority() {
        return 0;
    }
}
