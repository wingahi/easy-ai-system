package com.wgh.rag.chat.compression;

/**
 * 上下文压缩处理器（责任链节点）
 *
 * <p>默认三级压缩链：{@link RecentKeepHandler} → {@link SummaryHandler} → {@link FactExtractHandler}，
 * 按 {@link #order()} 升序执行。每级的降级策略封装在各自实现内：
 * 结果可复用时直接使用、LLM 未配置时跳过该级、LLM 调用失败时置
 * {@link CompressionContext#setDegraded(boolean)} 由编排方统一降级为简单截断。
 */
public interface CompressionHandler {

    /**
     * 判断本级是否需要处理（如对应层是否为空）
     */
    boolean shouldHandle(CompressionContext ctx);

    /**
     * 执行本级压缩，结果写回 ctx
     */
    void handle(CompressionContext ctx);

    /**
     * 链上顺序（升序执行）
     */
    int order();
}
