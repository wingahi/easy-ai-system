package com.wgh.rag.chat.evaluation;

/**
 * RAG 评估方案
 */
public enum EvaluationScheme {

    /** 不启用扩展评估 */
    NONE,

    /** 仅启用检索指标（MRR + NDCG），纯后端计算 */
    METRICS,

    /** 仅启用 LLM-as-Judge（Faithfulness + Answer Relevancy） */
    LLM_JUDGE,

    /** 同时启用 METRICS 与 LLM_JUDGE */
    BOTH;

    public boolean hasMetrics() {
        return this == METRICS || this == BOTH;
    }

    public boolean hasLlmJudge() {
        return this == LLM_JUDGE || this == BOTH;
    }

    /**
     * 从字符串解析，非法值回退为 NONE
     */
    public static EvaluationScheme from(String value) {
        if (value == null || value.isBlank()) return NONE;
        try {
            return EvaluationScheme.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
