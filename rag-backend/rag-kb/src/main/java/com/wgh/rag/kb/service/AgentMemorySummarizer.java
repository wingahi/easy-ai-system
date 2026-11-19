package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆摘要生成器 —— 可选的 LLM 摘要，失败时返回 null 由调用方回退到截断。
 *
 * <p>默认关闭（{@code wgh.rag.memory.llm-summary-enabled=false}），避免为每条记忆额外增加
 * 一次模型调用；开启后优先使用 {@code summaryModelId}，未配置则取第一个启用的对话模型。
 * 任何异常都只记日志并返回 null，不影响记忆写入主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMemorySummarizer {

    private static final String SYSTEM_PROMPT = """
            你是记忆整理助手。请把任务执行结果压缩成一条可长期复用的中文记忆。
            要求：
            1. 保留关键实体（人名、组织、产品、文件名、专有名词）；
            2. 保留全部关键数字（金额、日期、比例、指标、版本号）；
            3. 保留明确结论与决定，删除过程描述、客套与重复；
            4. 只输出记忆正文本身，不要标题、列表符号或 Markdown。""";

    private final LlmModelMapper llmModelMapper;
    private final ModelCacheManager modelCacheManager;
    private final RagProperties ragProperties;

    /**
     * 生成摘要；开关关闭、无可用模型或调用失败时返回 null。
     */
    public String summarize(String content) {
        RagProperties.Memory cfg = memoryConfig();
        if (content == null || content.isBlank() || !Boolean.TRUE.equals(cfg.getLlmSummaryEnabled())) {
            return null;
        }
        Long modelId = resolveModelId(cfg);
        if (modelId == null) {
            log.debug("记忆摘要已开启但无可用对话模型，回退到截断");
            return null;
        }
        try {
            int maxInput = cfg.getSummaryMaxInputChars() != null
                    ? Math.max(1, cfg.getSummaryMaxInputChars()) : 4000;
            String input = content.length() > maxInput ? content.substring(0, maxInput) : content;
            int maxTokens = cfg.getSummaryMaxTokens() != null
                    ? Math.max(32, cfg.getSummaryMaxTokens()) : 300;
            String userPrompt = "请生成不超过 " + maxTokens + " tokens 的记忆：\n\n" + input;

            ChatModel model = modelCacheManager.getChatModel(modelId);
            var response = model.chat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt)))
                    .build());
            String summary = response != null && response.aiMessage() != null
                    ? response.aiMessage().text() : null;
            if (summary == null || summary.isBlank()) {
                log.debug("记忆摘要为空，回退到截断");
                return null;
            }
            return summary.trim();
        } catch (Exception e) {
            log.warn("记忆摘要生成失败（回退到截断）: reason={}", e.getMessage());
            return null;
        }
    }

    private Long resolveModelId(RagProperties.Memory cfg) {
        if (cfg.getSummaryModelId() != null) {
            return cfg.getSummaryModelId();
        }
        try {
            List<LlmModel> models = llmModelMapper.selectList(new LambdaQueryWrapper<LlmModel>()
                    .eq(LlmModel::getStatus, 1)
                    .orderByAsc(LlmModel::getId)
                    .last("LIMIT 1"));
            return models == null || models.isEmpty() ? null : models.get(0).getId();
        } catch (Exception e) {
            log.debug("解析摘要模型失败: reason={}", e.getMessage());
            return null;
        }
    }

    private RagProperties.Memory memoryConfig() {
        RagProperties.Memory cfg = ragProperties.getMemory();
        return cfg != null ? cfg : new RagProperties.Memory();
    }
}
