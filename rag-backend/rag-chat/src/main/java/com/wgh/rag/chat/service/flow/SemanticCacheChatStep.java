package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.service.session.ChatMessageStore;
import com.wgh.rag.kb.service.SemanticCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义缓存路线：命中则直接返回缓存答案，跳过检索与 LLM。
 *
 * <p>带图片的消息不做语义缓存：缓存仅按文本相似度匹配，图片内容无法参与比对。</p>
 */
@Component
@RequiredArgsConstructor
final class SemanticCacheChatStep implements ChatTurnStep {

    private final SemanticCacheService semanticCacheService;
    private final ChatMessageStore messageStore;

    @Override
    public int order() {
        return 200;
    }

    @Override
    public boolean execute(ChatTurnContext ctx) {
        if (!ctx.hasKb || !ctx.imageUrls.isEmpty()
                || !Boolean.TRUE.equals(ctx.window.getSemanticCacheEnabled())) {
            return false;
        }
        SemanticCacheService.CacheHit hit =
                semanticCacheService.lookup(ctx.window.getId(), ctx.kbIds, ctx.userMessage);
        if (hit == null) {
            return false;
        }
        ctx.metricCtx.route().set("cache");
        sendCachedAnswer(ctx, hit);
        return true;
    }

    /** 直接下发缓存答案（cache + content + sources + done），并落库 assistant 消息。 */
    private void sendCachedAnswer(ChatTurnContext ctx, SemanticCacheService.CacheHit hit) {
        ctx.events.sendQuietly("cache",
                Map.of("cacheHit", true, "similarity", hit.score()));
        if (hit.answer() != null && !hit.answer().isBlank()) {
            // 先落库「已产出」再下发答案，避免崩溃后流水仍为 CHARGED 被补偿退款
            ctx.markOutput();
        }
        ctx.events.sendQuietly("content", Map.of("text", hit.answer()));
        ctx.events.sendQuietly("sources", List.of());
        com.wgh.rag.chat.entity.ChatMessage saved = messageStore.saveAssistantMessage(
                ctx.session, hit.answer(), List.of(), null, null, null, null);
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("fullResponse", hit.answer());
        doneData.put("cacheHit", true);
        if (saved != null && saved.getId() != null) doneData.put("assistantMessageId", saved.getId());
        ctx.events.sendQuietly("done", doneData);
        ctx.events.completeQuietly();
    }
}
