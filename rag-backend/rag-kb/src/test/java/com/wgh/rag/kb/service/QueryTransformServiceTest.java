package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * QueryTransformService 单测：三种模式解析、异常/超时/空输出降级
 */
class QueryTransformServiceTest {

    private final ModelCacheManager modelCacheManager = Mockito.mock(ModelCacheManager.class);
    private final ChatModel chatModel = Mockito.mock(ChatModel.class);

    private QueryTransformService service;

    @BeforeEach
    void setUp() {
        service = new QueryTransformService(modelCacheManager, new ObjectMapper(), 100);
        when(modelCacheManager.getChatModel(5L)).thenReturn(chatModel);
    }

    @Test
    void none_mode_passthrough() {
        var r = service.transform("q", "NONE", 5L);
        assertThat(r.applied()).isFalse();
        assertThat(r.vectorQueries()).containsExactly("q");
        assertThat(r.transformedQueries()).isEmpty();
    }

    @Test
    void null_model_falls_back() {
        var r = service.transform("q", "REWRITE", null);
        assertThat(r.applied()).isFalse();
        assertThat(r.vectorQueries()).containsExactly("q");
    }

    @Test
    void rewrite_returns_rewritten_query() {
        when(chatModel.chat(anyString())).thenReturn("规范检索查询");
        var r = service.transform("那个东西怎么弄", "REWRITE", 5L);
        assertThat(r.applied()).isTrue();
        assertThat(r.vectorQueries()).containsExactly("规范检索查询");
        assertThat(r.transformedQueries()).containsExactly("规范检索查询");
    }

    @Test
    void multi_query_parses_json_array_with_surrounding_text() {
        when(chatModel.chat(anyString())).thenReturn("变体如下：\n[\"v1\",\"v2\",\"v3\"]\n完毕");
        var r = service.transform("q", "MULTI_QUERY", 5L);
        assertThat(r.applied()).isTrue();
        // 向量路 = 原查询 + 变体
        assertThat(r.vectorQueries()).containsExactly("q", "v1", "v2", "v3");
        assertThat(r.transformedQueries()).containsExactly("v1", "v2", "v3");
    }

    @Test
    void multi_query_invalid_output_falls_back() {
        when(chatModel.chat(anyString())).thenReturn("这不是 JSON");
        var r = service.transform("q", "MULTI_QUERY", 5L);
        assertThat(r.applied()).isFalse();
        assertThat(r.vectorQueries()).containsExactly("q");
    }

    @Test
    void hyde_returns_hypothetical_answer() {
        when(chatModel.chat(anyString())).thenReturn("这是一段假设性答案。");
        var r = service.transform("q", "HYDE", 5L);
        assertThat(r.applied()).isTrue();
        assertThat(r.vectorQueries()).containsExactly("这是一段假设性答案。");
    }

    @Test
    void exception_falls_back_to_original() {
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("HTTP 401"));
        var r = service.transform("q", "REWRITE", 5L);
        assertThat(r.applied()).isFalse();
        assertThat(r.vectorQueries()).containsExactly("q");
    }

    @Test
    void blank_output_falls_back_to_original() {
        when(chatModel.chat(anyString())).thenReturn("   ");
        var r = service.transform("q", "REWRITE", 5L);
        assertThat(r.applied()).isFalse();
        assertThat(r.vectorQueries()).containsExactly("q");
    }

    @Test
    void timeout_falls_back_to_original() {
        // 超时阈值 100ms，chat 睡眠 2s → 触发超时降级
        when(chatModel.chat(anyString())).thenAnswer(inv -> {
            Thread.sleep(2000);
            return "too late";
        });
        var r = service.transform("q", "REWRITE", 5L);
        assertThat(r.applied()).isFalse();
        assertThat(r.vectorQueries()).containsExactly("q");
    }
}
