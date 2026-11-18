package com.wgh.rag.llm.util;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具结果 Token 预算器单元测试
 */
@DisplayName("工具结果 Token 预算")
class ToolResultBudgeterTest {

    /** 未超限时原样返回，不产生截断/折叠。 */
    @Test
    @DisplayName("预算充足 - 零改动")
    void trim_withinBudget_noChange() {
        List<ChatMessage> messages = List.of(
                new ToolExecutionResultMessage("call-1", "http_tool", ascii(10)),
                new ToolExecutionResultMessage("call-2", "http_tool", ascii(10)));

        ToolResultBudgeter.Result result = ToolResultBudgeter.trim(messages, 100, 100);

        assertThat(result.changed()).isFalse();
        assertThat(result.truncatedCount()).isZero();
        assertThat(result.collapsedCount()).isZero();
        assertThat(result.keptTokens()).isEqualTo(result.originalTokens());
        assertThat(((ToolExecutionResultMessage) result.messages().get(0)).text()).isEqualTo(ascii(10));
    }

    /** 单条超限：按句界裁剪并追加可见截断标记，保留消息 id 与工具名。 */
    @Test
    @DisplayName("单条超限 - 裁剪并标注")
    void trim_singleResultTruncated() {
        List<ChatMessage> messages = List.of(
                new ToolExecutionResultMessage("call-1", "http_tool", ascii(100)));

        ToolResultBudgeter.Result result = ToolResultBudgeter.trim(messages, 50, 3000);

        assertThat(result.truncatedCount()).isEqualTo(1);
        ToolExecutionResultMessage trimmed = (ToolExecutionResultMessage) result.messages().get(0);
        assertThat(trimmed.id()).isEqualTo("call-1");
        assertThat(trimmed.toolName()).isEqualTo("http_tool");
        assertThat(trimmed.text()).endsWith(ToolResultBudgeter.TRUNCATE_MARK);
        assertThat(TokenEstimator.estimate(trimmed.text())).isLessThanOrEqualTo(50);
        assertThat(trimmed.text().length()).isLessThan(ascii(100).length());
    }

    /** 累计超限：从最旧结果开始折叠为占位提示，最新结果保持完整，消息配对不破坏。 */
    @Test
    @DisplayName("累计超限 - 折叠最旧、保留最新")
    void trim_cumulativeCollapsesOldest() {
        List<ChatMessage> messages = List.of(
                new ToolExecutionResultMessage("call-1", "tool_a", ascii(40)),
                new ToolExecutionResultMessage("call-2", "tool_b", ascii(40)),
                new ToolExecutionResultMessage("call-3", "tool_c", ascii(40)));

        ToolResultBudgeter.Result result = ToolResultBudgeter.trim(messages, 100, 115);

        assertThat(result.collapsedCount()).isEqualTo(1);
        assertThat(result.truncatedCount()).isZero();
        ToolExecutionResultMessage oldest = (ToolExecutionResultMessage) result.messages().get(0);
        assertThat(oldest.text()).isEqualTo(ToolResultBudgeter.COLLAPSED_PLACEHOLDER);
        assertThat(oldest.id()).isEqualTo("call-1");
        assertThat(oldest.toolName()).isEqualTo("tool_a");
        // 最新结果完整保留
        assertThat(((ToolExecutionResultMessage) result.messages().get(2)).text()).isEqualTo(ascii(40));
        assertThat(result.keptTokens()).isLessThanOrEqualTo(115);
    }

    /** 只处理工具结果消息，普通对话消息不受影响。 */
    @Test
    @DisplayName("仅处理工具结果 - 其他消息不变")
    void trim_ignoresNonToolMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(ascii(500)));
        messages.add(AiMessage.from("调用工具"));
        messages.add(new ToolExecutionResultMessage("call-1", "tool", ascii(100)));

        ToolResultBudgeter.Result result = ToolResultBudgeter.trim(messages, 50, 3000);

        assertThat(result.messages().get(0)).isSameAs(messages.get(0));
        assertThat(result.messages().get(1)).isSameAs(messages.get(1));
        assertThat(result.messages().get(2)).isNotSameAs(messages.get(2));
        // 原列表不被修改
        assertThat(((ToolExecutionResultMessage) messages.get(2)).text()).isEqualTo(ascii(100));
    }

    /** 空列表 / null 输入返回空结果，不抛异常。 */
    @Test
    @DisplayName("空输入 - 返回空结果")
    void trim_emptyInput() {
        assertThat(ToolResultBudgeter.trim(null, 0, 0).messages()).isEmpty();
        assertThat(ToolResultBudgeter.trim(List.of(), 0, 0).changed()).isFalse();
    }

    /** ASCII 4 字符 ≈ 1 token。 */
    private static String ascii(int tokens) {
        return "a".repeat(tokens * 4);
    }
}
