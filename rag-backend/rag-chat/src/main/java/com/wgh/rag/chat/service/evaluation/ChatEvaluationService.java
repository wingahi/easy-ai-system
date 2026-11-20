package com.wgh.rag.chat.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.evaluation.JudgeModelSelector;
import com.wgh.rag.chat.evaluation.LlmJudgeService;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.service.stream.ChatSsePublisher;
import com.wgh.rag.llm.entity.LlmModel;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge 在线评估：异步多 judge 打分，完成后推送 judge 事件并回填 assistant 消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatEvaluationService {

    private final LlmJudgeService llmJudgeService;
    private final JudgeModelSelector judgeModelSelector;
    private final ObjectMapper objectMapper;
    private final ChatMessageMapper messageMapper;

    public void runLlmJudgeAsync(ChatWindow window, String query,
                          List<EmbeddingMatch<TextSegment>> retrievedMatches,
                          String answer, SseEmitter emitter, ChatMessage assistantMsg) {
        String context = retrievedMatches.stream()
                .map(m -> m.embedded() != null ? m.embedded().text() : "")
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");

        // 选择 judge 模型（排除当前聊天模型避免自我偏好）
        List<LlmModel> judges = judgeModelSelector.selectJudges(
                window.getJudgeModelIds(), window.getLlmModelId());
        if (judges.isEmpty()) {
            log.warn("窗口 {} 无可用 judge 模型，跳过 LLM-as-Judge", window.getId());
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
            return;
        }

        final String finalContext = context;
        llmJudgeService.evaluateAsync(judges, query, finalContext, answer)
                .whenComplete((result, ex) -> {
                    try {
                        if (ex != null) {
                            log.error("LLM-as-Judge 执行异常", ex);
                        } else if (result != null && result.faithfulness() != null) {
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("faithfulness", result.faithfulness());
                            payload.put("answerRelevancy", result.answerRelevancy());
                            payload.put("judgeAgreement", result.judgeAgreement());
                            payload.put("judgeCount", result.details().size());
                            ChatSsePublisher.send(emitter, ChatEvents.JUDGE, payload);

                            if (assistantMsg != null && assistantMsg.getId() != null) {
                                ChatMessage update = new ChatMessage();
                                update.setId(assistantMsg.getId());
                                update.setFaithfulness(result.faithfulness());
                                update.setAnswerRelevancy(result.answerRelevancy());
                                update.setJudgeAgreement(result.judgeAgreement());
                                try {
                                    update.setJudgeDetails(objectMapper.writeValueAsString(result.details()));
                                } catch (Exception e) {
                                    log.warn("序列化 judgeDetails 失败", e);
                                }
                                messageMapper.updateById(update);
                            }
                        } else {
                            log.warn("LLM-as-Judge 全部失败，不发送 judge 事件");
                        }
                    } catch (Exception e) {
                        log.error("处理 judge 结果失败", e);
                    } finally {
                        ChatSsePublisher.completeQuietly(emitter);
                    }
                });
    }
}
