package com.wgh.rag.scheduler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行事件（wgh_execution_event）。
 *
 * <p>跨实例 SSE 重放的事实源：每次发布按 {@code (execution_id, seq)} 落一行，
 * 断线重连按 {@code Last-Event-ID} 补齐。事件表只增不改，按终态保留期清理。
 */
@Data
@TableName("wgh_execution_event")
public class ExecutionEventRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 执行标识。 */
    private String executionId;

    /** 执行内单调递增序号，从 1 开始。 */
    private Long seq;

    /** 事件类型（ExecutionEventTypes）。 */
    private String eventType;

    /** 事件负载 JSON。 */
    private String payloadJson;

    private LocalDateTime createTime;
}
