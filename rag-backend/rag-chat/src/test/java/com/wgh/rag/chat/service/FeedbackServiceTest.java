package com.wgh.rag.chat.service;

import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FeedbackService 单测：反馈校验、满意度统计
 */
class FeedbackServiceTest {

    private final ChatMessageMapper messageMapper = Mockito.mock(ChatMessageMapper.class);
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(messageMapper);
    }

    private ChatMessage assistantMsg(long id) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setRole("ASSISTANT");
        msg.setContent("答案");
        return msg;
    }

    @Test
    void submit_like_updates_feedback() {
        when(messageMapper.selectById(1L)).thenReturn(assistantMsg(1L));
        service.submitFeedback(1L, 1, null);
        var captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertThat(captor.getValue().getFeedback()).isEqualTo(1);
        assertThat(captor.getValue().getFeedbackComment()).isNull();
    }

    @Test
    void submit_dislike_keeps_comment_and_truncates() {
        when(messageMapper.selectById(1L)).thenReturn(assistantMsg(1L));
        service.submitFeedback(1L, -1, "x".repeat(600));
        var captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertThat(captor.getValue().getFeedback()).isEqualTo(-1);
        assertThat(captor.getValue().getFeedbackComment()).hasSize(500);
    }

    @Test
    void submit_rejects_invalid_value() {
        assertThatThrownBy(() -> service.submitFeedback(1L, 5, null))
                .isInstanceOf(BusinessException.class);
        verify(messageMapper, never()).updateById(any(ChatMessage.class));
    }

    @Test
    void submit_rejects_non_assistant_message() {
        ChatMessage userMsg = new ChatMessage();
        userMsg.setId(2L);
        userMsg.setRole("USER");
        when(messageMapper.selectById(2L)).thenReturn(userMsg);
        assertThatThrownBy(() -> service.submitFeedback(2L, 1, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅助手消息可反馈");
    }

    @Test
    void submit_rejects_missing_message() {
        when(messageMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.submitFeedback(9L, 1, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void stats_computes_rate() {
        when(messageMapper.countFeedbackByWindow(1L, 1)).thenReturn(3L);
        when(messageMapper.countFeedbackByWindow(1L, -1)).thenReturn(1L);
        Map<String, Object> stats = service.windowStats(1L);
        assertThat(stats.get("likes")).isEqualTo(3L);
        assertThat(stats.get("dislikes")).isEqualTo(1L);
        assertThat((Double) stats.get("rate")).isEqualTo(0.75);
    }

    @Test
    void stats_zero_when_no_feedback() {
        when(messageMapper.countFeedbackByWindow(anyLong(), anyInt())).thenReturn(0L);
        Map<String, Object> stats = service.windowStats(1L);
        assertThat((Double) stats.get("rate")).isEqualTo(0.0);
    }
}
