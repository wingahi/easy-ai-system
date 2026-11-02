package com.wgh.rag.kb.service;

/**
 * 检索增强选项（窗口级配置解析后传入检索链路）
 *
 * @param rerankModelId      Rerank 模型 ID；null 表示不启用重排序
 * @param hybridEnabled      是否启用混合检索（向量 + 关键词 RRF 融合）
 * @param queryTransformMode 查询变换模式：NONE / REWRITE / MULTI_QUERY / HYDE
 * @param transformModelId   查询变换执行模型（CHAT 类型）；null 时变换不生效
 */
public record RetrievalOptions(Long rerankModelId, boolean hybridEnabled,
                               String queryTransformMode, Long transformModelId) {

    public RetrievalOptions(Long rerankModelId) {
        this(rerankModelId, false);
    }

    public RetrievalOptions(Long rerankModelId, boolean hybridEnabled) {
        this(rerankModelId, hybridEnabled, null, null);
    }

    public static RetrievalOptions defaults() {
        return new RetrievalOptions(null, false, null, null);
    }

    public boolean rerankEnabled() {
        return rerankModelId != null;
    }

    public boolean transformEnabled() {
        return transformModelId != null && queryTransformMode != null
                && !queryTransformMode.isBlank() && !"NONE".equalsIgnoreCase(queryTransformMode);
    }
}
