package com.wgh.rag.scheduler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.handler.JsonStringTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调度实例注册与心跳（wgh_scheduler_instance）。
 *
 * <p>只用于存活判定与管理端集群视角，不是待执行数据的存储：
 * 多实例协调仍以 {@code wgh_execution_job} 的状态与租约为唯一事实源。
 */
@Data
@TableName("wgh_scheduler_instance")
public class SchedulerInstance {

    /** 实例标识，与 {@code wgh_execution_job.lease_owner} 同口径。 */
    @TableId(type = IdType.INPUT)
    private String instanceId;

    /** 本次进程启动时间；同实例重启后覆盖。 */
    private LocalDateTime startedAt;

    /** 最近一次心跳（数据库时间）。 */
    private LocalDateTime lastHeartbeat;

    /** 优雅停机标记时间；非空表示已主动下线。 */
    private LocalDateTime stoppedAt;

    /** 车道运行时快照 JSON（在途/排队/线程数），仅供观测。 */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String lanesJson;

    /** 心跳/状态更新版本号。 */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
