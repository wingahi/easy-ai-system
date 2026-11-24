package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.constant.ChatEvents;
import com.wgh.rag.chat.service.prompt.ChatContextBudgetService;
import com.wgh.rag.chat.service.prompt.ChatPromptAssembler;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.tool.constant.ToolConstants;
import com.wgh.rag.tool.fallback.ToolInvoker;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * LLM 路线：组装最终请求并启动流式故障转移（工具循环与终态由 {@link ChatAnswerStreamer} 负责）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
final class LlmChatStep implements ChatTurnStep {

    private final ModelRouter modelRouter;
    private final ChatPromptAssembler promptAssembler;
    private final ChatContextBudgetService contextBudgetService;
    private final ChatAnswerStreamer answerStreamer;
    private final ToolInvoker toolInvoker;
    private final RagMetrics metrics;

    @Override
    public int order() {
        return 600;
    }

    @Override
    public boolean execute(ChatTurnContext ctx) {
        // 模型链路/消息历史已在预算前置阶段装配；Agent 降级等场景在此兜底补齐
        if (ctx.modelChain == null) {
            ctx.modelChain = modelRouter.resolveChain(ctx.window.getModelRouteGroup(),
                    ctx.window.getLlmModelId());
        }

        // 记录实际模型（切链成功后回填，用于成本回填）
        final AtomicReference<LlmModel> actualModel = new AtomicReference<>(ctx.modelChain.get(0));
        // 故障转移可观测事件：尝试顺序/切换原因同步到日志与前端
        BiConsumer<LlmModel, String> onModelSwitch = (m, reason) -> {
            log.info("[Failover] 使用模型 {} (id={})，原因: {}", m.getModelName(), m.getId(), reason);
            actualModel.set(m);
            try {
                ctx.events.send(ChatEvents.MODEL_SWITCH,
                        Map.of("modelName", m.getModelName(), "modelId", m.getId(), "reason", reason));
            } catch (IOException ignored) {
            }
        };
        // 链路模式下先声明首模型（保持原有 model_switch 事件语义）
        if (ctx.modelChain.size() > 1) {
            onModelSwitch.accept(ctx.modelChain.get(0), "首模型");
        }

        if (ctx.messages == null) {
            ctx.messages = promptAssembler.buildMessageHistory(ctx.session, ctx.systemPrompt, ctx.userMessage);
            // Agent 分支失败降级到普通 RAG 时，历史此时才装配，需重新按完整 prompt 计算预算
            if (!ctx.retrievedMatches.isEmpty()) {
                contextBudgetService.applyRagBudget(ctx.retrievedMatches, ctx.messages, ctx.modelChain.get(0));
            }
        }

        // 有检索结果时把知识库内容拼进用户消息；只配了工具但没有知识库结果时用原始消息
        String userPromptText = ctx.userMessage;
        if (!ctx.retrievedMatches.isEmpty()) {
            // 预算已在前面统一应用，此处直接拼接，保证来源/指标/上下文同源
            userPromptText = promptAssembler.buildRagUserPrompt(ctx.userMessage, ctx.retrievedMatches);
        }
        ChatMessage currentUserMessage = promptAssembler.buildUserMessage(userPromptText, ctx.imageUrls);
        ctx.messages.set(ctx.messages.size() - 1, currentUserMessage);

        try {
            ctx.request = ChatRequest.builder()
                    .messages(new ArrayList<>(ctx.messages))
                    .toolSpecifications(ctx.toolSpecs)
                    .build();
            ctx.maxToolRounds = ToolConstants.MAX_TOOL_ROUNDS;
            ctx.actualModel = actualModel;
            ctx.onModelSwitch = onModelSwitch;
            ctx.toolBudget = toolInvoker.newTaskBudget();

            // 首 token 前失败（异步 onError 或同步异常）统一回到故障转移链路；
            // 链路只有 1 个模型时等价于单模型调用
            // 流式调用是异步的：本轮收口交给流式终态（完成/停止/失败），
            // 编排线程返回后不得提前判定「无产出」
            ctx.deferCompletion();
            answerStreamer.start(ctx, 0);
        } catch (Exception e) {
            metrics.recordChatError("llm");
            log.error("LLM调用异常", e);
            try {
                ctx.events.send(ChatEvents.CONTENT,
                        Map.of("text", "抱歉，大模型调用出现异常: " + e.getMessage()));
                ctx.events.completeWithError(e);
            } catch (IOException ex) {
                log.error("发送错误失败", ex);
            }
            // 同步失败即未发起流式调用：本轮不可能再有产出，由本处收口
            ctx.completeTurn();
        }
        return true;
    }
}
