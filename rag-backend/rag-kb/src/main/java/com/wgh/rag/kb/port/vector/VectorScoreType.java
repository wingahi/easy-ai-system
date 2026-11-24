package com.wgh.rag.kb.port.vector;

/**
 * 端口层显式声明分数语义，避免不同后端把余弦、点积、BM25、RRF 分数混用。
 */
public enum VectorScoreType {
    /** 余弦相似度（0~1，越大越相关）。 */
    COSINE,
    /** 向量点积（未归一化，取值范围随维度变化）。 */
    DOT_PRODUCT,
    /** 关键词相关性（pg_trgm / BM25 等，仅同后端内可比）。 */
    KEYWORD,
    /** Reciprocal Rank Fusion 融合分数。 */
    RRF,
    /** 重排序模型分数。 */
    RERANK
}
