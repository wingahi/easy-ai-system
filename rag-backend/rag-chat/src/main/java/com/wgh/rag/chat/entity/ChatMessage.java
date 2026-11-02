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
     * RAG 检索召回率（Recall@K，需要相关性标注；未标注时为 null，历史数据勿使用）
     */
    private Double recallRate;

    /**
     * RAG 召回覆盖率（实际返回条数 / topK，衡量 topK 填充程度，不是召回率）
     */
    private Double coverageRate;

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

    /**
     * 本条消息估算成本（元，按模型价格表回填；无价格配置为 null）
     */
    private Double cost;

    /**
     * 生成本条消息的模型名（厂商侧 modelName，用于成本按模型聚合）
     */
    private String modelName;

    /**
     * 用户反馈：0 无 / 1 赞 / -1 踩（仅 ASSISTANT 消息可反馈）
     */
    private Integer feedback;

    /**
     * 差评补充说明（截断 500 字）
     */
    private String feedbackComment;

    /**
     * 消息附件 JSON 数组（图片 data URL 或公网 URL，多模态问答）
     */
    private String attachments;
}
