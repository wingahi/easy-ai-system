package com.wgh.rag.kb.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 检索式记忆实体（wgh_agent_memory）
 * 会话/任务级摘要与事实，向量存 wgh_agent_memory_vec（metadata.scopeKey 隔离）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_agent_memory")
public class AgentMemory extends BaseEntity {

    /** 作用域类型：SESSION / TASK */
    private String scopeType;

    /** 作用域 ID（sessionId 或 taskId） */
    private String scopeId;

    /** 产生该记忆的 Agent（单 Agent 链路为空） */
    private String agentId;

    /** 记忆类型：SUMMARY / FACT */
    private String memoryType;

    /** 记忆正文 */
    private String content;

    /** 正文 SHA-256，同作用域去重 */
    private String contentHash;

    /** wgh_agent_memory_vec 中的向量 ID（uuid） */
    private String embeddingId;

    /** 来源消息 ID（可选） */
    private Long sourceMessageId;

    /** 正文估算 Token 数 */
    private Integer tokenCount;
}
