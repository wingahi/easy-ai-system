package com.wgh.rag.scheduler.admin;

/**
 * 策略试算结果（不落库）：按当前负载与拟定策略给出预计准入结论。
 *
 * @param tenantId     租户标识
 * @param lane         执行车道
 * @param maxConcurrent 拟定并发上限
 * @param maxQueue     拟定排队上限
 * @param rejectPolicy 拟定拒绝方式
 * @param running      当前运行中作业数
 * @param queued       当前排队作业数
 * @param decision     PERMIT / QUEUE / REJECT
 * @param reason       结论说明（拒绝时含错误码口径）
 */
public record SchedulerPolicyDryRunResult(
        String tenantId,
        String lane,
        int maxConcurrent,
        int maxQueue,
        String rejectPolicy,
        long running,
        long queued,
        String decision,
        String reason) {
}
