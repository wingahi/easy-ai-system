package com.wgh.rag.llm.constant;

/**
 * 大模型模块常量：模型类型与提供商标识（与 wgh_llm_model / wgh_embedding_model 持久化值一致）。
 * <p>
 * provider 不是数据库枚举列，新增提供商无需变更表结构；所有标识集中在此处维护，
 * 能力差异见 {@link ProviderCatalog}。
 */
public interface LlmConstants {

    String MODEL_TYPE_CHAT = "CHAT";
    String MODEL_TYPE_RERANK = "RERANK";

    /** OpenAI 官方（OpenAI 兼容协议） */
    String PROVIDER_OPENAI = "OPENAI";
    /** 通义 DashScope 原生端点 */
    String PROVIDER_DASHSCOPE = "DASHSCOPE";
    /** 通义 DashScope 的兼容别名 */
    String PROVIDER_QWEN = "QWEN";
    /** DeepSeek（OpenAI 兼容协议，仅对话） */
    String PROVIDER_DEEPSEEK = "DEEPSEEK";
    /** Azure OpenAI（v1 端点，api-key 鉴权） */
    String PROVIDER_AZURE_OPENAI = "AZURE_OPENAI";
    /** Ollama 本地/内网部署（OpenAI 兼容端点，无需 Key） */
    String PROVIDER_OLLAMA = "OLLAMA";
    /** 智谱 GLM（OpenAI 兼容端点） */
    String PROVIDER_ZHIPU = "ZHIPU";
    /** 任意 OpenAI 兼容端点（vLLM / LM Studio / OneAPI 等） */
    String PROVIDER_CUSTOM_OPENAI = "CUSTOM_OPENAI";
    /** 通用 /rerank 兼容端点（Jina / SiliconFlow 等） */
    String PROVIDER_CUSTOM_RERANK = "CUSTOM_RERANK";
    /** 旧版前端使用的自定义别名，运行时归一化为 CUSTOM_OPENAI */
    String PROVIDER_CUSTOM_LEGACY = "CUSTOM";
}
