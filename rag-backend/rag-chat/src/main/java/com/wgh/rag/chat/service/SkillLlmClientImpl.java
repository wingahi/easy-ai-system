package com.wgh.rag.chat.service;

import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.tool.executor.SkillLlmClient;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SkillLlmClient 的 rag-chat 实现 —— 桥接 ModelCacheManager 调用 LangChain4j ChatModel
 * <p>
 * 为什么 rag-chat 实现而非 rag-llm：
 * rag-tool 不能依赖 rag-llm（会引入循环依赖风险），
 * 但 rag-chat 同时依赖 rag-tool 和 rag-llm，正好作为桥接层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillLlmClientImpl implements SkillLlmClient {

    private final ModelCacheManager modelCacheManager;

    @Override
    public String callSubModel(Long modelId, String systemPrompt, String userMessage) {
        if (modelId == null) {
            // 没有指定子模型 —— 用窗口级默认模型（由调用方传入，这里我们不知道窗口 ID，所以报错）
            log.warn("Skill 未指定 subModelId，无法调用子对话");
            return "Error: Skill executorConfig 缺少 subModelId 字段";
        }
        try {
            log.info("Skill 子对话调用: modelId={}", modelId);
            ChatModel chatModel = modelCacheManager.getChatModel(modelId);

            dev.langchain4j.data.message.SystemMessage sysMsg =
                    new dev.langchain4j.data.message.SystemMessage(systemPrompt != null ? systemPrompt : "");
            dev.langchain4j.data.message.UserMessage usrMsg =
                    new dev.langchain4j.data.message.UserMessage(userMessage != null ? userMessage : "");

            dev.langchain4j.model.chat.response.ChatResponse response = chatModel.chat(sysMsg, usrMsg);
            String text = response.aiMessage().text();
            log.info("Skill 子对话结果: {} chars", text != null ? text.length() : 0);
            return text != null ? text : "";
        } catch (Exception e) {
            log.error("Skill 子对话调用失败: modelId={}", modelId, e);
            return "Error: Skill 子对话调用失败 - " + e.getMessage();
        }
    }
}
