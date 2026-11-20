package com.wgh.rag.execution.event;

/**
 * 通用执行事件类型。
 *
 * <p>只收录跨执行体通用的事件；业务事件（如「引用命中」「步骤完成」）由各执行体在
 * {@link ExecutionEvent#type()} 中使用自有命名，避免把对话/工作流细节写进契约。
 */
public final class ExecutionEventTypes {

    /** 开始执行。 */
    public static final String STARTED = "execution.started";

    /** 进度更新。 */
    public static final String PROGRESS = "execution.progress";

    /** 等待外部输入（如 HITL 确认）。 */
    public static final String WAITING_INPUT = "execution.waiting_input";

    /** 执行成功。 */
    public static final String COMPLETED = "execution.completed";

    /** 执行失败。 */
    public static final String FAILED = "execution.failed";

    /** 执行取消。 */
    public static final String CANCELLED = "execution.cancelled";

    /** 心跳，用于长任务保活与前端超时判定。 */
    public static final String HEARTBEAT = "execution.heartbeat";

    private ExecutionEventTypes() {
    }
}
