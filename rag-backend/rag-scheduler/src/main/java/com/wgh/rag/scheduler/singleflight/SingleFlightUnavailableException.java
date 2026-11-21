package com.wgh.rag.scheduler.singleflight;

/**
 * 单飞锁不可用（Redis 未启用、连接失败或命令失败）。
 *
 * <p>跨实例单飞不能降级为进程内锁：进程内锁无法约束其它实例，会把「只跑一份」变成
 * 「每个实例各跑一份」。因此该异常是失败关闭信号，调用方必须放弃本次执行。
 */
public class SingleFlightUnavailableException extends RuntimeException {

    public SingleFlightUnavailableException(String message) {
        super(message);
    }

    public SingleFlightUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
