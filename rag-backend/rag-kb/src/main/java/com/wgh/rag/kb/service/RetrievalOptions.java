package com.wgh.rag.kb.service;

/**
 * 检索增强选项（窗口级配置解析后传入检索链路）
 *
 * @param rerankModelId Rerank 模型 ID；null 表示不启用重排序
 */
public record RetrievalOptions(Long rerankModelId) {

    public static RetrievalOptions defaults() {
        return new RetrievalOptions(null);
    }

    public boolean rerankEnabled() {
        return rerankModelId != null;
    }
}
