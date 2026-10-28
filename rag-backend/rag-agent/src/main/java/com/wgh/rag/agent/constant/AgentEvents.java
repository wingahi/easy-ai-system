package com.wgh.rag.agent.constant;

/**
 * Agent 模块 SSE 事件名常量 —— 与前端 ChatPlayground / ShareChat 的事件处理逻辑一一对应。
 * 字符串值为对外协议，禁止改动。
 */
public final class AgentEvents {

    private AgentEvents() {
    }

    /** 计划创建成功 */
    public static final String PLAN_CREATED = "plan_created";
    /** 计划创建失败 */
    public static final String PLAN_CREATE_FAILED = "plan_create_failed";
    /** 计划执行完成 */
    public static final String PLAN_COMPLETE = "plan_complete";
    /** 计划执行失败 */
    public static final String PLAN_FAILED = "plan_failed";
    /** 计划暂停（等待 HITL 确认） */
    public static final String PLAN_PAUSED = "plan_paused";
    /** 计划从 HITL 断点恢复 */
    public static final String PLAN_RESUMED = "plan_resumed";
    /** 计划被动态重规划更新 */
    public static final String PLAN_UPDATED = "plan_updated";
    /** 计划被取消 */
    public static final String PLAN_CANCELLED = "plan_cancelled";
    /** 步骤开始执行 */
    public static final String STEP_START = "step_start";
    /** 步骤执行成功 */
    public static final String STEP_SUCCESS = "step_success";
    /** 步骤执行失败 */
    public static final String STEP_FAILED = "step_failed";
    /** 步骤 Reflection 重试 */
    public static final String STEP_RETRY = "step_retry";
    /** 步骤 Reflection 评估结果 */
    public static final String STEP_REFLECTION = "step_reflection";
    /** 步骤被跳过 */
    public static final String STEP_SKIPPED = "step_skipped";
    /** 步骤等待人工确认 */
    public static final String STEP_HITL_PENDING = "step_hitl_pending";
}
