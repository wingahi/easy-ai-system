package com.wgh.rag.chat.compression;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 需要调用 LLM 的压缩处理器基类：封装带超时的 LLM 调用与轮次渲染。
 */
public abstract class AbstractLlmCompressionHandler implements CompressionHandler {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 分层压缩 LLM 调用线程池（各级 handler 共享） */
    private static final ExecutorService SUMMARY_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ctx-compress-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    /** 带超时调用 LLM，超时取消并抛出 TimeoutException */
    protected String callLlm(CompressionContext ctx, String prompt) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> ctx.getChatModel().chat(prompt), SUMMARY_EXECUTOR);
        try {
            return future.get(ctx.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("摘要生成超时 " + ctx.getTimeoutSeconds() + "s");
        }
    }

    /** 渲染轮次为 prompt 用的对话文本 */
    protected String renderRounds(List<Round> rounds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            Round r = rounds.get(i);
            sb.append("【第 ").append(i + 1).append(" 轮】\n");
            sb.append("用户: ").append(contentOf(r.getUser())).append("\n");
            if (r.getAi() != null) {
                sb.append("助手: ").append(contentOf(r.getAi())).append("\n");
            }
        }
        return sb.toString();
    }

    private static String contentOf(ChatMessage msg) {
        return switch (msg.type()) {
            case SYSTEM -> ((SystemMessage) msg).text();
            case USER -> ((UserMessage) msg).singleText();
            case AI -> ((AiMessage) msg).text();
            case TOOL_EXECUTION_RESULT -> ((ToolExecutionResultMessage) msg).text();
            default -> "";
        };
    }
}
