package com.wgh.rag.controller.service.resume;

/**
 * 续跑相关作业类型。
 */
public final class ResumeJobTypes {

    /** 执行单个业务 run 的续跑。 */
    public static final String RUN_RESUME = "RUN_RESUME";

    /** 扫描三类业务 run 并投递 {@link #RUN_RESUME}。 */
    public static final String RUN_RESUME_SCAN = "RUN_RESUME_SCAN";

    private ResumeJobTypes() {
    }
}
