package com.wgh.rag.chat.compression;

import org.springframework.stereotype.Component;

/**
 * 中期层处理器：对中间段对话生成 3-5 句进展摘要。
 *
 * <p>降级策略：已保存结果可复用时直接使用；无压缩模型时跳过摘要；
 * LLM 调用失败/超时置降级标记，由编排方统一降级为简单截断。
 */
@Component
public class SummaryHandler extends AbstractLlmCompressionHandler {

    @Override
    public int order() {
        return 2;
    }

    @Override
    public boolean shouldHandle(CompressionContext ctx) {
        return !ctx.getMidLayer().isEmpty();
    }

    @Override
    public void handle(CompressionContext ctx) {
        if (ctx.isMidReusable()) {
            log.info("中期摘要复用已有压缩: [{}, {})", ctx.getMidStart(), ctx.getRecentStart());
            ctx.setMidSummary(ctx.getSavedMidSummary());
            return;
        }
        if (ctx.getChatModel() == null) {
            log.warn("未配置压缩专用模型（compressionModel=true），跳过中期摘要");
            return;
        }
        try {
            ctx.setMidSummary(callLlm(ctx, buildMidSummaryPrompt(ctx)));
        } catch (Exception e) {
            log.error("LLM 中期摘要失败，降级为简单截断", e);
            ctx.setDegraded(true);
        }
    }

    /**
     * 摘要 Prompt 显式定义保留优先级，避免 LLM 随意裁剪关键信息。
     */
    private String buildMidSummaryPrompt(CompressionContext ctx) {
        String history = renderRounds(ctx.getMidLayer());
        Integer configured = ctx.getConfig().getSummaryMaxTokens();
        int maxTokens = configured != null ? configured : 600;
        return """
                你是对话历史压缩器。以下是一段对话历史，请生成简洁的进展摘要（3-5 句）。

                【保留优先级（从高到低）】
                1. 用户明确的需求、决策、指令
                2. 已确认的业务规则或事实
                3. 实体名称（人名、产品名、文件名、配置项）
                4. 时间、数字、ID 等精确数据
                5. 讨论中的假设、未解决问题
                6. 背景闲聊、礼貌用语、重复表述（可删除）

                【输出要求】
                - 直接给摘要正文，不要输出"摘要："等前缀
                - 用陈述句，每句一行，或用分号连接
                - 总长度控制在 %d 以内

                【对话历史】
                %s
                """.formatted(maxTokens, history);
    }
}
