package com.wgh.rag.kb.service;

/**
 * 检索增强选项（窗口级配置解析后传入检索链路）
 *
 * @param rerankModelId      Rerank 模型 ID；null 表示不启用重排序
 * @param hybridEnabled      是否启用混合检索（向量 + 关键词 RRF 融合）
 * @param queryTransformMode 查询变换模式：NONE / REWRITE / MULTI_QUERY / HYDE
 * @param transformModelId   查询变换执行模型（CHAT 类型）；null 时变换不生效
 * @param topK               本次检索返回条数；null 时使用全局配置（评测可按指定 K 计算 Recall@K）
 */
public record RetrievalOptions(Long rerankModelId, boolean hybridEnabled,
                               String queryTransformMode, Long transformModelId, Integer topK) {

    public RetrievalOptions(Long rerankModelId, boolean hybridEnabled,
                            String queryTransformMode, Long transformModelId) {
        this(rerankModelId, hybridEnabled, queryTransformMode, transformModelId, null);
    }

    public RetrievalOptions(Long rerankModelId) {
        this(rerankModelId, false);
    }

    public RetrievalOptions(Long rerankModelId, boolean hybridEnabled) {
        this(rerankModelId, hybridEnabled, null, null, null);
    }

    public static RetrievalOptions defaults() {
        return new RetrievalOptions(null, false, null, null, null);
    }

    public boolean rerankEnabled() {
        return rerankModelId != null;
    }

    public boolean transformEnabled() {
        return transformModelId != null && queryTransformMode != null
                && !queryTransformMode.isBlank() && !"NONE".equalsIgnoreCase(queryTransformMode);
    }
}
