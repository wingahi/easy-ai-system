package com.wgh.rag.tool.executor;

/**
 * Skill 执行器需要的 LLM 子对话能力 —— 避免 rag-tool 依赖 rag-llm 产生循环依赖
 * <p>
 * rag-chat 模块提供实现（桥接 ModelCacheManager + LangChain4j ChatModel）。
 * SkillToolExecutor 通过构造器注入此接口即可调用模型。
 */
public interface SkillLlmClient {

    /**
     * 调用子模型执行 skill prompt
     *
     * @param modelId      子模型 ID（为 null 时用窗口默认模型）
     * @param systemPrompt skill 的系统提示词（渲染模板后的最终文本）
     * @param userMessage  skill 工具调用传入的参数（可作为用户消息补充）
     * @return 模型返回的纯文本结果
     */
    String callSubModel(Long modelId, String systemPrompt, String userMessage);
}
