package com.wgh.rag.scheduler.lane;

import com.wgh.rag.execution.model.ExecutionLane;

/**
 * 单车道运行时快照。
 *
 * @param lane           车道
 * @param inFlight       已领取（含排队/运行）的作业数
 * @param queued         线程池队列长度
 * @param poolSize       当前线程数
 * @param maxConcurrent  最大并发
 * @param queueCapacity  队列容量
 */
public record LaneRuntimeSnapshot(
        ExecutionLane lane,
        int inFlight,
        int queued,
        int poolSize,
        int maxConcurrent,
        int queueCapacity) {
}
