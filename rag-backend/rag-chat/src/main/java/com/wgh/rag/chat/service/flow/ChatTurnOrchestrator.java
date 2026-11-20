package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.service.stream.ChatSsePublisher;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 对话链路编排器：按 order 升序执行步骤，任一步骤返回 true 即短路。
 *
 * <p>路由优先级固定在步骤的 order 中：窗口绑定 → 工作流 → 语义缓存 → 检索 →
 * Agent → 无结果兜底 → LLM。</p>
 */
@Service
public final class ChatTurnOrchestrator {

    private final List<ChatTurnStep> steps;

    public ChatTurnOrchestrator(List<ChatTurnStep> steps) {
        this.steps = steps.stream()
                .sorted(Comparator.comparingInt(ChatTurnStep::order))
                .toList();
    }

    public void execute(ChatTurnContext ctx) throws Exception {
        ChatSsePublisher.send(ctx.emitter, ChatEvents.START,
                Map.of("sessionId", ctx.session.getId()));
        for (ChatTurnStep step : steps) {
            if (step.execute(ctx)) {
                return;
            }
        }
    }
}
