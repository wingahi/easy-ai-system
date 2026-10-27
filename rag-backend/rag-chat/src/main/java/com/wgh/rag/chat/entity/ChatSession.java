package com.wgh.rag.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import com.wgh.rag.common.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天会话实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_chat_session")
public class ChatSession extends BaseEntity {

    /**
     * 聊天窗口 ID
     */
    private Long chatWindowId;

    /**
     * 会话标题（用户首句生成）
     */
    private String title;

    /**
     * 用户标识（登录用户 ID 或匿名会话 ID）
     */
    private String userIdentifier;

    /**
     * 是否长期记忆
     */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean persistent;

    /**
     * 总消息数
     */
    private Integer messageCount;

    /**
     * 总 Token 消耗
     */
    private Integer totalTokens;

    /**
     * 状态：ACTIVE / ARCHIVED
     */
    private String status;

    // ============ 分层压缩持久化字段 ============

    /** 中期摘要正文 */
    private String midSummary;

    /** 中期摘要覆盖的起始轮次（0-based, inclusive） */
    private Integer midSummaryRoundStart;

    /** 中期摘要覆盖的结束轮次（exclusive） */
    private Integer midSummaryRoundEnd;

    /** 生成中期摘要的模型名 */
    private String midSummaryModel;

    /** 中期摘要 prompt 版本号 */
    private String midSummaryPromptVersion;

    /** 中期摘要生成时间 */
    private java.time.LocalDateTime midSummaryTime;

    /** 远期关键事实（bullet list 纯文本） */
    private String longTermFacts;

    /** 远期事实覆盖到的轮次（exclusive） */
    private Integer longTermFactsRoundEnd;

    /** 生成远期事实的模型名 */
    private String longTermFactsModel;

    /** 远期事实 prompt 版本号 */
    private String longTermFactsPromptVersion;

    /** 远期事实生成时间 */
    private java.time.LocalDateTime longTermFactsTime;
}
