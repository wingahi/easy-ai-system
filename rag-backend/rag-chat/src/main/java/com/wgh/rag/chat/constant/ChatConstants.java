package com.wgh.rag.chat.constant;

/**
 * 聊天模块业务常量：消息角色、会话状态、默认参数（与 DB 持久化值一致）。
 */
public interface ChatConstants {

    // ============ 消息角色 ============
    String ROLE_USER = "USER";
    String ROLE_ASSISTANT = "ASSISTANT";

    // ============ 消息类型 ============
    String MESSAGE_TYPE_TEXT = "TEXT";
    String MESSAGE_TYPE_IMAGE = "IMAGE";

    // ============ 会话状态 ============
    String SESSION_STATUS_ACTIVE = "ACTIVE";

    // ============ 助手消息生成状态 ============
    /** 正常生成完成 */
    String MESSAGE_STATUS_COMPLETED = "COMPLETED";
    /** 用户主动停止（含客户端断连） */
    String MESSAGE_STATUS_STOPPED = "STOPPED";
    /** 生成失败（已产出部分内容后中断） */
    String MESSAGE_STATUS_FAILED = "FAILED";

    // ============ 评估方案 ============
    String EVALUATION_SCHEME_NONE = "NONE";

    // ============ 窗口默认参数 ============
    int DEFAULT_MAX_HISTORY_MESSAGES = 20;
    double DEFAULT_TEMPERATURE = 0.7;
    int DEFAULT_STATUS_ENABLED = 1;
    int STATUS_DISABLED = 0;

    // ============ SSE ============
    long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
}
