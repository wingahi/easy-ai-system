package com.wgh.rag.kb.service;

/**
 * 知识库 embedding 模型变更事件。
 * <p>
 * 模型一变，旧向量就处在旧语义空间且维度可能不同，必须整库重建；
 * 事件在 update 事务提交后由 KnowledgeFileService 监听并触发后台重建，
 * 用于解耦 KnowledgeBaseService（发布方）与文件重放（消费方）之间的循环依赖。
 *
 * @param kbId          知识库 ID
 * @param oldModelId    变更前的 embedding 模型 ID（可能为 null）
 * @param newModelId    变更后的 embedding 模型 ID
 * @param oldCollection 变更前的向量表名（可能为 null）
 * @param newCollection 变更后启用的向量表名
 */
public record KbEmbeddingModelChangedEvent(
        Long kbId,
        Long oldModelId,
        Long newModelId,
        String oldCollection,
        String newCollection) {
}
