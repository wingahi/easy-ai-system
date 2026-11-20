package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.service.prompt.ChatPromptAssembler;
import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.chat.service.stream.ChatSsePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 无结果兜底路线：无检索结果、无工具、无图片时直接返回提示话术。
 */
@Component
@RequiredArgsConstructor
final class NoResultChatStep implements ChatTurnStep {

    private final ChatPromptAssembler promptAssembler;
    private final ChatMessageStore messageStore;

    @Override
    public int order() {
        return 500;
    }

    @Override
    public boolean execute(ChatTurnContext ctx) throws Exception {
        if (!ctx.retrievedMatches.isEmpty() || !ctx.toolSpecs.isEmpty() || !ctx.imageUrls.isEmpty()) {
            return false;
        }

        String noResultMsg = promptAssembler.noResultMessage(ctx.hasKb);
        ChatSsePublisher.send(ctx.emitter, ChatEvents.CONTENT, Map.of("text", noResultMsg));
        ChatSsePublisher.send(ctx.emitter, ChatEvents.SOURCES, List.of());

        messageStore.saveAssistantMessage(ctx.session, noResultMsg, List.of(),
                ctx.hitRate, null, null, null);

        // 未配置知识库时不下发 accuracyRate 字段，前端按 null 不展示
        if (ctx.hasKb) {
            ChatSsePublisher.send(ctx.emitter, ChatEvents.DONE, Map.of("accuracyRate", 0.0));
        } else {
            ChatSsePublisher.send(ctx.emitter, ChatEvents.DONE, Map.of());
        }
        ctx.emitter.complete();
        return true;
    }
}
