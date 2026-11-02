package com.wgh.rag.kb.service;

/**
 * 检索增强选项（窗口级配置解析后传入检索链路）
 *
 * @param rerankModelId Rerank 模型 ID；null 表示不启用重排序
 * @param hybridEnabled 是否启用混合检索（向量 + 关键词 RRF 融合）
 */
public record RetrievalOptions(Long rerankModelId, boolean hybridEnabled) {

    public RetrievalOptions(Long rerankModelId) {
        this(rerankModelId, false);
    }

    public static RetrievalOptions defaults() {
        return new RetrievalOptions(null, false);
    }

    public boolean rerankEnabled() {
        return rerankModelId != null;
    }
}
