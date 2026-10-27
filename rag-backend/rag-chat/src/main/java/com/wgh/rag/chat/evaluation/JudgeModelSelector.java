package com.wgh.rag.chat.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.LlmModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM-as-Judge 模型选择器
 * 策略：
 *  1. ChatWindow.judgeModelIds 非空 → 解析使用这些模型（过滤禁用）
 *  2. 否则 → 从启用模型中自动选 2 个不同 provider，优先 DEEPSEEK + DASHSCOPE/QWEN
 *     且排除当前窗口聊天模型（避免自我偏好偏差）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeModelSelector {

    private final LlmModelService llmModelService;
    private final ObjectMapper objectMapper;

    /**
     * 选择 judge 模型
     *
     * @param judgeModelIdsJson ChatWindow 中的 judge 模型 ID JSON（可为空）
     * @param excludeModelId    需要排除的模型 ID（通常是当前聊天模型）
     * @return judge 模型列表（可能为空，表示无可用 judge）
     */
    public List<LlmModel> selectJudges(String judgeModelIdsJson, Long excludeModelId) {
        // 手动指定优先
        List<Long> ids = parseIds(judgeModelIdsJson);
        if (!ids.isEmpty()) {
            List<LlmModel> result = new ArrayList<>();
            for (Long id : ids) {
                if (excludeModelId != null && excludeModelId.equals(id)) continue;
                try {
                    LlmModel m = llmModelService.getChatModel(id);
                    if (m.getStatus() != null && m.getStatus() == 1) {
                        result.add(m);
                    }
                } catch (Exception e) {
                    log.warn("Judge 模型 {} 不可用: {}", id, e.getMessage());
                }
            }
            if (!result.isEmpty()) {
                log.info("使用手动指定的 {} 个 judge 模型", result.size());
                return result;
            }
            log.warn("手动指定的 judge 模型均不可用，回退到自动选择");
        }

        // 自动选择：优先 DEEPSEEK + DASHSCOPE/QWEN，保证不同 provider
        List<LlmModel> enabled = llmModelService.listEnabledChatModels();
        List<LlmModel> candidates = new ArrayList<>();
        for (LlmModel m : enabled) {
            if (excludeModelId != null && excludeModelId.equals(m.getId())) continue;
            if (Boolean.TRUE.equals(m.getCompressionModel())) continue; // 跳过压缩模型
            candidates.add(m);
        }
        if (candidates.isEmpty()) return List.of();

        List<LlmModel> selected = new ArrayList<>();
        Set<String> usedProviders = new HashSet<>();

        // 优先 DEEPSEEK
        selectByProvider(candidates, selected, usedProviders, "DEEPSEEK");
        // 然后 DASHSCOPE / QWEN
        selectByProvider(candidates, selected, usedProviders, "DASHSCOPE");
        if (selected.size() < 2) {
            selectByProvider(candidates, selected, usedProviders, "QWEN");
        }
        // 凑不齐 2 个不同 provider 时，从剩余候选中补齐
        if (selected.size() < 2) {
            for (LlmModel m : candidates) {
                if (selected.size() >= 2) break;
                if (usedProviders.contains(m.getProvider())) continue;
                selected.add(m);
                usedProviders.add(m.getProvider());
            }
        }
        // 兜底：如果仍只有 1 个，允许重复 provider
        if (selected.size() < 2 && !candidates.isEmpty() && selected.isEmpty()) {
            selected.add(candidates.get(0));
        }

        log.info("自动选择 {} 个 judge 模型: {}", selected.size(),
                selected.stream().map(m -> m.getName() + "(" + m.getProvider() + ")").toList());
        return selected;
    }

    private void selectByProvider(List<LlmModel> candidates, List<LlmModel> selected,
                                   Set<String> usedProviders, String provider) {
        if (selected.size() >= 2) return;
        for (LlmModel m : candidates) {
            if (provider.equalsIgnoreCase(m.getProvider()) && !usedProviders.contains(m.getProvider())) {
                selected.add(m);
                usedProviders.add(m.getProvider());
                return;
            }
        }
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("解析 judgeModelIds 失败: {}", json);
            return List.of();
        }
    }
}
