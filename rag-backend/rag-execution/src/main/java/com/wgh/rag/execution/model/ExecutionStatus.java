package com.wgh.rag.execution.model;

/**
 * 执行体统一状态。
 *
 * <p>业务表可保留更细的状态（例如 {@code HITL_PENDING}），但对外查询、管理端聚合与重试判定
 * 一律映射到本枚举，避免每种执行体各有一套状态机。
 *
 * <p>状态迁移：
 * <pre>
 * PENDING ──► LEASED ──► RUNNING ──► SUCCEEDED
 *    │           │          ├──────► FAILED
 *    │           │          ├──────► DEAD
 *    │           │          └──────► CANCELLED
 *    └───────────┴──────────────────► CANCELLED
 * </pre>
 * 交互式执行（{@link ExecutionLane#INTERACTIVE}）不经过 {@code PENDING/LEASED}，直接进入 {@code RUNNING}。
 */
public enum ExecutionStatus {

    /** 已接受、尚未被任何实例领取（后台作业）。 */
    PENDING,

    /** 已被某实例领取并持有租约，尚未开始执行业务逻辑。 */
    LEASED,

    /** 正在执行。 */
    RUNNING,

    /** 成功终态。 */
    SUCCEEDED,

    /** 可重试的失败终态；是否重试由调度策略决定。 */
    FAILED,

    /** 不可重试终态（超过重试上限、payload 不兼容等）。 */
    DEAD,

    /** 取消终态。 */
    CANCELLED;

    /**
     * 是否为终态。
     *
     * @return {@code true} 表示不会再发生状态迁移
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == DEAD || this == CANCELLED;
    }
}
