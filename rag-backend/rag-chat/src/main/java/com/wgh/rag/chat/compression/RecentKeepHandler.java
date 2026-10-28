package com.wgh.rag.chat.compression;

import dev.langchain4j.data.message.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 近期层处理器：最近 N 轮完整保留原文，不做压缩（原文即该级的兜底）。
 */
@Component
public class RecentKeepHandler implements CompressionHandler {

    @Override
    public int order() {
        return 1;
    }

    @Override
    public boolean shouldHandle(CompressionContext ctx) {
        return true;
    }

    @Override
    public void handle(CompressionContext ctx) {
        List<ChatMessage> recent = new ArrayList<>();
        for (Round r : ctx.getRecentLayer()) {
            recent.add(r.getUser());
            if (r.getAi() != null) {
                recent.add(r.getAi());
            }
        }
        ctx.setRecentMessages(recent);
    }
}
