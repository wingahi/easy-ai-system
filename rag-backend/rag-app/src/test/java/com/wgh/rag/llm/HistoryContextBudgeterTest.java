package com.wgh.rag.llm;

import com.wgh.rag.llm.util.HistoryContextBudgeter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史消息 Token 预算器单元测试
 */
@DisplayName("历史消息 Token 预算")
class HistoryContextBudgeterTest {

    /** 预算充足时消息列表保持不变，且不产生丢弃。 */
    @Test
    @DisplayName("预算充足 - 原样保留")
    void trim_withinBudget_keepsAll() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("第一问"),
                AiMessage.from("第一答"),
                UserMessage.from("当前问题"));

        HistoryContextBudgeter.Result result = HistoryContextBudgeter.trim(messages, 10000);

        assertThat(result.kept()).hasSize(4);
        assertThat(result.droppedMessages()).isZero();
        assertThat(result.keptTokens()).isEqualTo(result.originalTokens());
    }

    /** 超出预算时从最旧消息开始丢弃，System 与当前用户消息保留。 */
    @Test
    @DisplayName("超出预算 - 丢最旧、保 System 与当前问题")
    void trim_overBudget_dropsOldest() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("sys"));
        messages.add(UserMessage.from(longText(100)));   // 100 tokens
        messages.add(AiMessage.from(longText(100)));
        messages.add(UserMessage.from(longText(100)));
        messages.add(AiMessage.from(longText(100)));
        messages.add(UserMessage.from("q"));

        HistoryContextBudgeter.Result result = HistoryContextBudgeter.trim(messages, 120);

        assertThat(result.kept().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.kept().get(result.kept().size() - 1)).isSameAs(messages.get(5));
        // 预算 120：System 1 + 当前问题 1，仅容得下最近一条 100 tokens 的历史
        assertThat(result.kept()).containsExactly(messages.get(0), messages.get(4), messages.get(5));
        assertThat(result.droppedMessages()).isEqualTo(3);
        assertThat(result.keptTokens()).isLessThanOrEqualTo(result.originalTokens());
    }

    /** 当前用户消息单条就超预算时仍必须保留，避免问题本身被裁掉。 */
    @Test
    @DisplayName("当前问题超预算 - 仍保留")
    void trim_currentQuestionAlwaysKept() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("sys"),
                UserMessage.from(longText(200)),
                AiMessage.from(longText(200)),
                UserMessage.from(longText(500)));

        HistoryContextBudgeter.Result result = HistoryContextBudgeter.trim(messages, 10);

        assertThat(result.kept()).hasSize(2);
        assertThat(result.kept().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.kept().get(1)).isSameAs(messages.get(3));
        assertThat(result.droppedMessages()).isEqualTo(2);
    }

    /** 压缩生成的摘要 System 消息（远期事实 / 中期摘要）不参与丢弃，优先裁掉更早的原始对话。 */
    @Test
    @DisplayName("摘要 System 保留 - 优先丢原始对话")
    void trim_keepsSummarySystemMessages() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("sys"),
                SystemMessage.from("一二三四五六七八九十"), // 10 tokens 的中期摘要
                UserMessage.from(longText(100)),
                AiMessage.from(longText(100)),
                UserMessage.from("q"));

        HistoryContextBudgeter.Result result = HistoryContextBudgeter.trim(messages, 30);

        assertThat(result.kept()).containsExactly(messages.get(0), messages.get(1), messages.get(4));
        assertThat(result.droppedMessages()).isEqualTo(2);
    }

    /** Token 非正数时使用默认 4000 预算，不抛异常。 */
    @Test
    @DisplayName("非法预算 - 回落默认值")
    void trim_invalidBudget_usesDefault() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("sys"),
                UserMessage.from("q"));

        HistoryContextBudgeter.Result result = HistoryContextBudgeter.trim(messages, 0);

        assertThat(result.kept()).hasSize(2);
        assertThat(result.droppedMessages()).isZero();
    }

    /** ASCII 400 字符按 4 字符 ≈ 1 token 估算为 100 tokens。 */
    private static String longText(int tokens) {
        return "a".repeat(tokens * 4);
    }
}
