package com.wgh.rag.chat;

import com.wgh.rag.chat.service.ContextCompressionService;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压缩服务单元测试
 */
@DisplayName("上下文压缩测试")
class ContextCompressionServiceTest {

    private ContextCompressionService compressionService;

    @BeforeEach
    void setUp() {
        compressionService = new ContextCompressionService();
    }

    // ============ 简单截断测试 ============

    @Test
    @DisplayName("compressHistory - 消息数不超限直接返回")
    void compressHistory_withinLimit() {
        List<ChatMessage> messages = buildMessages(3); // 1 Sys + 3 User + 3 AI = 7
        List<ChatMessage> result = compressionService.compressHistory(messages, 20);
        assertThat(result).hasSize(7);
        // System 消息应保留在首位
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
    }

    @Test
    @DisplayName("compressHistory - 消息数超限，保留 System + 最近消息")
    void compressHistory_exceedLimit() {
        List<ChatMessage> messages = buildMessages(25); // 1 System + 24 对话
        List<ChatMessage> result = compressionService.compressHistory(messages, 10);
        
        assertThat(result.size()).isLessThanOrEqualTo(10);
        // System 消息保留
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
    }

    // ============ Token 估算截断测试 ============

    @Test
    @DisplayName("compressByToken - 短消息都能保留")
    void compressByToken_allFit() {
        List<ChatMessage> messages = buildMessages(5); // 1 Sys + 5 User + 5 AI = 11
        List<ChatMessage> result = compressionService.compressByToken(messages, 10000);
        assertThat(result.size()).isEqualTo(11);
    }

    @Test
    @DisplayName("compressByToken - Token 估算截断")
    void compressByToken_needTruncation() {
        List<ChatMessage> messages = buildLongMessages(10);
        List<ChatMessage> result = compressionService.compressByToken(messages, 30);
        
        // 应该只保留 System 消息或加上最后一条
        assertThat(result.size()).isLessThan(messages.size());
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
    }

    // ============ 辅助方法 ============

    private List<ChatMessage> buildMessages(int turnCount) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个 AI 助手"));
        for (int i = 0; i < turnCount; i++) {
            messages.add(UserMessage.from("用户问题 #" + i));
            messages.add(AiMessage.from("AI 回答 #" + i));
        }
        return messages;
    }

    private List<ChatMessage> buildLongMessages(int turnCount) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("你是一个 AI 助手"));
        String longText = "人工智能是计算机科学的一个分支，它企图了解智能的实质，并生产出一种新的能以人类智能相似的方式做出反应的智能机器，该领域的研究包括机器人、语言识别、图像识别、自然语言处理和专家系统等。";
        for (int i = 0; i < turnCount; i++) {
            messages.add(UserMessage.from("问题 " + i + "：" + longText));
            messages.add(AiMessage.from("回答 " + i + "：" + longText));
        }
        return messages;
    }
}
