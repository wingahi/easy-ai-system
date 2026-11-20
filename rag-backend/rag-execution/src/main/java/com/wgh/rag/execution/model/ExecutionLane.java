package com.wgh.rag.execution.model;

/**
 * 执行车道。
 *
 * <p>与统一调度方案的三车道一一对应：
 * <ul>
 *   <li>{@link #INTERACTIVE}：有订阅者的交互式执行（对话流、Agent 计划、多 Agent 协作）。允许实例内执行，
 *       但必须受受管线程池与准入控制，禁止裸线程。</li>
 *   <li>{@link #BACKGROUND}：后台作业（评测、入库、工作流运行、计划任务）。必须落作业表，
 *       以租约保证同一作业只有一个执行者。</li>
 *   <li>{@link #MAINTENANCE}：维护型作业（连接器同步、上传清理、记忆清理、孤儿扫描、语义缓存清理）。
 *       必须单飞（advisory lock），多副本下同一时刻只允许一个实例执行。</li>
 * </ul>
 */
public enum ExecutionLane {

    /** 交互式执行：进程内执行，受准入与并发上限约束。 */
    INTERACTIVE,

    /** 后台作业：持久化 + 租约，可被其它实例接管。 */
    BACKGROUND,

    /** 维护作业：单飞执行，多副本不重复。 */
    MAINTENANCE
}
