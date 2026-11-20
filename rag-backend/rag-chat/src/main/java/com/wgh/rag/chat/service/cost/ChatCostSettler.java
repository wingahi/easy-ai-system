package com.wgh.rag.chat.service.cost;

import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.ModelPriceService;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.llm.util.TokenEstimator;
import com.wgh.rag.quota.service.QuotaService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话成本回填与配额 Token 累加。
 *
 * <p>优先使用模型返回的真实 token 用量，缺失时按字符启发式估算；
 * 成本/配额失败只记日志，不影响主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCostSettler {

    private final ModelRouter modelRouter;
    private final ModelPriceService modelPriceService;
    private final QuotaService quotaService;
    private final ChatMessageMapper messageMapper;
    private final RagMetrics metrics;

    public void settle(ChatWindow window, String userMessage,
                List<EmbeddingMatch<TextSegment>> retrievedMatches, String fullAnswer,
                ChatMessage saved, long inputTokens, long outputTokens,
                LlmModel actualModel, String quotaUsername) {
        try {
            if (inputTokens + outputTokens == 0) {
                StringBuilder inputText = new StringBuilder(userMessage != null ? userMessage : "");
                for (EmbeddingMatch<TextSegment> m : retrievedMatches) {
                    if (m.embedded() != null && m.embedded().text() != null) {
                        inputText.append(m.embedded().text());
                    }
                }
                inputTokens = TokenEstimator.estimate(inputText.toString());
                outputTokens = TokenEstimator.estimate(fullAnswer);
            }
            long total = inputTokens + outputTokens;
            metrics.recordLlmTokens(inputTokens, outputTokens);

            Double cost = null;
            String modelName = null;
            try {
                // 优先使用故障转移链路中「实际响应」的模型，回退到 route_group 首模型，再回退到窗口直配模型
                LlmModel model = actualModel;
                if (model == null) {
                    model = modelRouter.resolveChain(window.getModelRouteGroup(), window.getLlmModelId()).get(0);
                }
                if (model != null) {
                    modelName = model.getModelName();
                    cost = modelPriceService.estimateCost(model.getProvider(), model.getModelName(),
                            inputTokens, outputTokens);
                    metrics.recordLlmCall(model.getProvider());
                    if (cost != null) metrics.recordLlmCost(cost);
                }
            } catch (Exception e) {
                log.warn("成本估算失败: windowId={}, reason={}", window.getId(), e.getMessage());
            }

            if (saved != null && saved.getId() != null) {
                ChatMessage update = new ChatMessage();
                update.setId(saved.getId());
                update.setTokenCount((int) Math.min(Integer.MAX_VALUE, total));
                update.setCost(cost);
                update.setModelName(modelName);
                messageMapper.updateById(update);
            }
            quotaService.addTokens(quotaUsername, total);
        } catch (Exception e) {
            log.warn("成本回填/配额累加失败: {}", e.getMessage());
        }
    }
}
