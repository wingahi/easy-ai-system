package com.wgh.rag.chat.service.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.evaluation.EvaluationScheme;
import com.wgh.rag.chat.service.window.ChatWindowSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 解析窗口级配置：知识库绑定、评估方案、Agent 开关。
 */
@Component
@RequiredArgsConstructor
final class ChatWindowBindingStep implements ChatTurnStep {

    private final ObjectMapper objectMapper;

    @Override
    public int order() {
        return 50;
    }

    @Override
    public boolean execute(ChatTurnContext ctx) {
        ctx.kbIds = ChatWindowSettings.parseKbIds(objectMapper, ctx.window.getKnowledgeBaseIds());
        ctx.hasKb = !ctx.kbIds.isEmpty();
        ctx.evaluationScheme = EvaluationScheme.from(ctx.window.getEvaluationScheme());
        ctx.agentEnabled = Integer.valueOf(1).equals(ctx.window.getAgentEnabled());
        return false;
    }
}
