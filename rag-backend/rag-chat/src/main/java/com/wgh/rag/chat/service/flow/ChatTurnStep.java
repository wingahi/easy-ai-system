package com.wgh.rag.chat.service.flow;

/**
 * 对话链路的有序步骤。
 *
 * <p>按 {@link #order()} 升序执行；返回 {@code true} 表示本次对话已产生终态，后续步骤不再执行。
 * 每个实现只负责一条路线或一个准备阶段，便于独立阅读与替换。</p>
 */
interface ChatTurnStep {

    /** 执行顺序（升序）。 */
    int order();

    /**
     * @return true 表示终态已处理（不再继续后续步骤）
     */
    boolean execute(ChatTurnContext ctx) throws Exception;
}
