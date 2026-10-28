package com.wgh.rag.chat.compression;

import com.wgh.rag.config.property.RagProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 近期层处理器测试：最近 N 轮完整保留原文。
 */
@DisplayName("近期原文保留处理器测试")
class RecentKeepHandlerTest {

    private final RecentKeepHandler handler = new RecentKeepHandler();

    /** shouldHandle 对任意上下文恒返回 true，因近期层始终需要保留 */
    @Test
    @DisplayName("shouldHandle 恒为 true（近期层总是保留）")
    void shouldHandle_alwaysTrue() {
        assertThat(handler.shouldHandle(new CompressionContext())).isTrue();
    }

    /** 近期层按 user、ai 顺序展开为原文消息并保持原对象引用 */
    @Test
    @DisplayName("handle 按序展开近期轮次的 user/ai 原文")
    void handle_flattensRecentRounds() {
        CompressionContext ctx = ctxWithRounds(3, 0, 1); // 近期层 = 第 1~2 轮

        handler.handle(ctx);

        List<ChatMessage> recent = ctx.getRecentMessages();
        assertThat(recent).hasSize(4);
        assertThat(recent.get(0)).isSameAs(ctx.getRounds().get(1).getUser());
        assertThat(recent.get(1)).isSameAs(ctx.getRounds().get(1).getAi());
        assertThat(recent.get(2)).isSameAs(ctx.getRounds().get(2).getUser());
        assertThat(recent.get(3)).isSameAs(ctx.getRounds().get(2).getAi());
    }

    /** 最后一轮 ai 回复为空时，近期层只保留该轮 user 消息 */
    @Test
    @DisplayName("最后一轮 ai 为 null 时只保留 user 消息")
    void handle_lastRoundWithoutAi() {
        CompressionContext ctx = ctxWithRounds(2, 0, 1);
        ctx.getRounds().get(1).setAi(null); // 最后一轮尚未收到回复

        handler.handle(ctx);

        assertThat(ctx.getRecentMessages()).containsExactly(ctx.getRounds().get(1).getUser());
    }

    static CompressionContext ctxWithRounds(int totalRounds, int midStart, int recentStart) {
        List<Round> rounds = new ArrayList<>();
        for (int i = 0; i < totalRounds; i++) {
            rounds.add(new Round(UserMessage.from("问题" + i), AiMessage.from("回答" + i)));
        }
        CompressionContext ctx = new CompressionContext();
        ctx.setRounds(rounds);
        ctx.setMidStart(midStart);
        ctx.setRecentStart(recentStart);
        ctx.setConfig(new RagProperties().getContext());
        ctx.setTimeoutSeconds(20);
        return ctx;
    }
}
