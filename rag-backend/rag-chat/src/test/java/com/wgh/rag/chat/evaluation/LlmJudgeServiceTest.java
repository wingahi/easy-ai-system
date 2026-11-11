package com.wgh.rag.chat.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.config.property.RagEvaluationProperties;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * LLM-as-Judge 单测：用手工构造的 judge JSON 校验 Context Precision/Recall 计算、
 * 无参考答案的「不可计算」语义，以及 judge 超时不影响主链路。
 */
@DisplayName("LLM-as-Judge 生成侧指标测试")
class LlmJudgeServiceTest {

    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);

    private LlmJudgeService service;

    @BeforeEach
    void setUp() {
        service = new LlmJudgeService(modelCacheManager, new ObjectMapper(), new RagEvaluationProperties());
    }

    private LlmModel model(long id) {
        LlmModel model = new LlmModel();
        model.setId(id);
        model.setName("judge-" + id);
        model.setProvider("DEEPSEEK");
        return model;
    }

    @Test
    @DisplayName("Context Precision/Recall - 手算校验：chunk 相关标记 [1,0,1] → CP≈0.8333；声明 2/3 被支撑 → CR≈0.6667")
    void contextMetricsHandCalculated() throws Exception {
        LlmModel judge = model(1L);
        ChatModel chatModel = Mockito.mock(ChatModel.class);
        when(modelCacheManager.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.chat(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("chunkRelevant")) {
                return "```json\n{\"chunkRelevant\":[1,0,1],"
                        + "\"claims\":[{\"claim\":\"a\",\"supported\":true},"
                        + "{\"claim\":\"b\",\"supported\":false},"
                        + "{\"claim\":\"c\",\"supported\":true}],"
                        + "\"reason\":\"ok\"}\n```";
            }
            return "{\"faithfulness\":0.8,\"answerRelevancy\":0.9,\"reason\":\"ok\"}";
        });

        LlmJudgeService.JudgeResult result = service.evaluateWithReferenceAsync(
                        List.of(judge), "问题", List.of("c1", "c2", "c3"), "回答", "参考答案")
                .get(5, TimeUnit.SECONDS);

        assertThat(result.faithfulness()).isEqualTo(0.8);
        assertThat(result.answerRelevancy()).isEqualTo(0.9);
        // CP = (Precision@1×1 + Precision@3×1) / 2 = (1 + 2/3) / 2
        assertThat(result.contextPrecision()).isCloseTo(0.8333, offset(0.0001));
        // CR = 2/3
        assertThat(result.contextRecall()).isCloseTo(0.6667, offset(0.0001));
        assertThat(result.contextMetricStatus()).isEqualTo(LlmJudgeService.ContextMetricStatus.COMPUTED);
        assertThat(result.details()).hasSize(1);
        assertThat(result.details().get(0).contextPrecision()).isCloseTo(0.8333, offset(0.0001));
    }

    @Test
    @DisplayName("无参考答案 - Context Precision/Recall 返回 null 并标记不可计算，不用 0 冒充")
    void contextMetricsNotComputableWithoutReference() throws Exception {
        LlmModel judge = model(2L);
        ChatModel chatModel = Mockito.mock(ChatModel.class);
        when(modelCacheManager.getChatModel(2L)).thenReturn(chatModel);
        when(chatModel.chat(anyString()))
                .thenReturn("{\"faithfulness\":0.7,\"answerRelevancy\":0.8,\"reason\":\"ok\"}");

        LlmJudgeService.JudgeResult result = service.evaluateAsync(
                        List.of(judge), "问题", "上下文", "回答")
                .get(5, TimeUnit.SECONDS);

        assertThat(result.faithfulness()).isEqualTo(0.7);
        assertThat(result.contextPrecision()).isNull();
        assertThat(result.contextRecall()).isNull();
        assertThat(result.contextMetricStatus())
                .isEqualTo(LlmJudgeService.ContextMetricStatus.NOT_COMPUTABLE_NO_REFERENCE);
    }

    @Test
    @DisplayName("judge 超时 - 返回失败明细，不抛异常、不阻塞调用方")
    void judgeTimeoutDoesNotBreakCaller() throws Exception {
        RagEvaluationProperties props = new RagEvaluationProperties();
        props.setJudgeTimeoutSeconds(1);
        service = new LlmJudgeService(modelCacheManager, new ObjectMapper(), props);

        LlmModel judge = model(3L);
        ChatModel chatModel = Mockito.mock(ChatModel.class);
        when(modelCacheManager.getChatModel(3L)).thenReturn(chatModel);
        when(chatModel.chat(anyString())).thenAnswer(invocation -> {
            Thread.sleep(4000);
            return "{\"faithfulness\":1,\"answerRelevancy\":1}";
        });

        LlmJudgeService.JudgeResult result = service.evaluateAsync(
                        List.of(judge), "问题", "上下文", "回答")
                .get(10, TimeUnit.SECONDS);

        assertThat(result.faithfulness()).isNull();
        assertThat(result.details()).hasSize(1);
        assertThat(result.details().get(0).error()).isNotBlank();
    }

    @Test
    @DisplayName("Context Precision/Recall 静态计算 - 空标记不可计算，0 条声明不可计算")
    void contextMetricEdgeCases() {
        assertThat(LlmJudgeService.calculateContextPrecision(List.of())).isNull();
        assertThat(LlmJudgeService.calculateContextPrecision(null)).isNull();
        assertThat(LlmJudgeService.calculateContextPrecision(List.of(false, false))).isEqualTo(0.0);
        assertThat(LlmJudgeService.calculateContextRecall(0, 0)).isNull();
        assertThat(LlmJudgeService.calculateContextRecall(1, 2)).isEqualTo(0.5);
    }
}
