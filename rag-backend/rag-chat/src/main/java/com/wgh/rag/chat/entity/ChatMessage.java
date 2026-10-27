package com.wgh.rag.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天消息实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_chat_message")
public class ChatMessage extends BaseEntity {

    /**
     * 会话 ID
     */
    private Long sessionId;

    /**
     * 角色：USER / ASSISTANT / SYSTEM
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 消息类型：TEXT / TOOL_CALL / RAG_CONTEXT
     */
    private String messageType;

    /**
     * Token 数量
     */
    private Integer tokenCount;

    /**
     * RAG 检索召回率
     */
    private Double recallRate;

    /**
     * RAG 命中率
     */
    private Double hitRate;

    /**
     * 答案正确率
     */
    private Double accuracyRate;

    /**
     * 引用来源（JSON）
     */
    private String sources;

    /**
     * MRR（Mean Reciprocal Rank），第一个被引用 chunk 的检索排名倒数
     */
    private Double mrr;

    /**
     * NDCG（Normalized Discounted Cumulative Gain）
     */
    private Double ndcg;

    /**
     * 忠实度（多 judge 平均）
     */
    private Double faithfulness;

    /**
     * 答案相关性（多 judge 平均）
     */
    private Double answerRelevancy;

    /**
     * Judge 间一致性（1 - 平均绝对差）
     */
    private Double judgeAgreement;

    /**
     * Judge 详细结果 JSON
     */
    private String judgeDetails;
}
