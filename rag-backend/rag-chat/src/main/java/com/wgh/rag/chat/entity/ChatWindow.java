package com.wgh.rag.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import com.wgh.rag.common.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天窗口实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_chat_window")
public class ChatWindow extends BaseEntity {

    /**
     * 窗口名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 使用的 LLM 模型 ID
     */
    private Long llmModelId;

    /**
     * 使用的知识库 ID（支持多个，JSON 数组）
     */
    private String knowledgeBaseIds;

    /**
     * System 提示词
     */
    private String systemPrompt;

    /**
     * 分享 Token（UUID，用于公开分享链接）
     */
    private String shareToken;

    /**
     * 是否启用分享
     */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean shareEnabled;

    /**
     * 状态：0-停用，1-启用
     */
    private Integer status;

    /**
     * 关联的工具配置 ID（JSON 数组）
     */
    private String toolIds;

    /**
     * 最大历史消息条数
     */
    private Integer maxHistoryMessages;

    /**
     * 温度参数
     */
    private Double temperature;

    /**
     * RAG 评估方案：NONE / METRICS / LLM_JUDGE / BOTH
     */
    private String evaluationScheme;

    /**
     * LLM-as-judge 模型 ID 列表（JSON 数组），为空则自动选择 2 个不同 provider
     */
    private String judgeModelIds;
}
