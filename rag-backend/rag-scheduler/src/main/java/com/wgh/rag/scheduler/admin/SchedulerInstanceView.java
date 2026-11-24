package com.wgh.rag.scheduler.admin;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理端实例视图。
 *
 * @param instanceId        实例标识（与 lease_owner 同口径）
 * @param startedAt         本次启动时间
 * @param lastHeartbeat     最近心跳时间（数据库时间）
 * @param stoppedAt         主动下线时间；非空表示已停止
 * @param alive             是否存活（未停止且心跳未超过存活阈值）
 * @param heartbeatAgeSeconds 距最近心跳的秒数
 * @param lanes             车道运行时快照
 */
public record SchedulerInstanceView(
        String instanceId,
        LocalDateTime startedAt,
        LocalDateTime lastHeartbeat,
        LocalDateTime stoppedAt,
        boolean alive,
        long heartbeatAgeSeconds,
        Map<String, Object> lanes) {
}
