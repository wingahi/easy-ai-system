package com.wgh.rag.kb.storage;

/**
 * 迁移任务状态。
 * <ul>
 *   <li>{@link #RUNNING}：任务已提交，正在枚举或搬运；</li>
 *   <li>{@link #COMPLETED}：引擎正常结束（报告中的 failed 为单对象失败数，不影响任务状态）；</li>
 *   <li>{@link #FAILED}：引擎级异常（后端创建、列举、中断等），无完整报告；</li>
 *   <li>{@link #CANCELLED}：收到取消请求并已停止派发后续对象。</li>
 * </ul>
 */
public enum MigrationJobStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
