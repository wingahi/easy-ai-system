package com.wgh.rag.chat.compression;

import org.springframework.stereotype.Component;

/**
 * 远期层处理器：对更早的对话只提取关键事实点。
 *
 * <p>降级策略：已保存结果可复用时直接使用；无压缩模型时跳过提取；
 * LLM 调用失败/超时置降级标记，由编排方统一降级为简单截断。
 */
@Component
public class FactExtractHandler extends AbstractLlmCompressionHandler {

    @Override
    public int order() {
        return 3;
    }

    @Override
    public boolean shouldHandle(CompressionContext ctx) {
        return !ctx.getLongTermLayer().isEmpty();
    }

    @Override
    public void handle(CompressionContext ctx) {
        if (ctx.isLongTermReusable()) {
            log.info("远期事实复用已有压缩: 需要覆盖<{}", ctx.getMidStart());
            ctx.setLongTermFacts(ctx.getSavedLongTermFacts());
            return;
        }
        if (ctx.getChatModel() == null) {
            log.warn("未配置压缩专用模型（compressionModel=true），跳过远期事实提取");
            return;
        }
        try {
            ctx.setLongTermFacts(callLlm(ctx, buildLongTermFactsPrompt(ctx)));
        } catch (Exception e) {
            log.error("LLM 远期事实提取失败，降级为简单截断", e);
            ctx.setDegraded(true);
        }
    }

    /**
     * 事实提取 Prompt 显式定义保留优先级（只留前 4 项），其余全部丢弃。
     */
    private String buildLongTermFactsPrompt(CompressionContext ctx) {
        String history = renderRounds(ctx.getLongTermLayer());
        Integer configured = ctx.getConfig().getLongTermFactsTokens();
        int maxTokens = configured != null ? configured : 400;
        return """
                你是对话历史压缩器。以下是更早的一段对话历史，请只提取关键事实点，每条一行。

                【保留优先级（只留前 4 项）】
                1. 用户明确的需求、决策、指令
                2. 已确认的业务规则或事实
                3. 实体名称（人名、产品名、文件名、配置项）
                4. 时间、数字、ID 等精确数据
                （闲聊、过程性对话、礼貌用语全部丢弃）

                【输出要求】
                - 每行一个事实点，格式："- [事实要点]"
                - 不超过 8 条，只留不可丢失的信息
                - 总长度控制在 %d 以内

                【对话历史】
                %s
                """.formatted(maxTokens, history);
    }
}
