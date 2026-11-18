package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.constant.WorkflowConstants;

import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.failover.BlockingModelFailover;
import com.wgh.rag.llm.failover.FailoverAttempt;
import com.wgh.rag.llm.failover.FailoverErrors;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.service.ModelFailoverService;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.llm.util.TokenEstimator;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.workflow.config.WorkflowContextProperties;
import com.wgh.rag.workflow.constant.WorkflowEvents;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 节点 —— 渲染 prompt 后调用大模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;
    private final ModelCacheManager modelCacheManager;
    private final ModelFailoverService modelFailoverService;
    private final WorkflowContextProperties contextProperties;
    private final RagMetrics metrics;

    @Override
    public String supportedType() {
        return WorkflowConstants.NODE_LLM;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        Long modelId = NodeConfigSupport.asLong(cfg.get("modelId"));
        if (modelId == null) throw new IllegalArgumentException("LLM 节点缺少 modelId");

        String systemPrompt = variableResolver.render(NodeConfigSupport.str(cfg.get("systemPrompt")), ctx);
        String prompt = variableResolver.render(NodeConfigSupport.str(cfg.get("prompt")), ctx);
        if (prompt.isBlank()) prompt = String.valueOf(ctx.getVars().getOrDefault("lastOutput", ""));

        List<LlmModel> chain = modelFailoverService.resolveChain(modelId);
        prompt = applyInputBudget(context, systemPrompt, prompt, chain);

        List<ChatMessage> messages = new ArrayList<>();
        if (!systemPrompt.isBlank()) messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(prompt));

        ChatRequest request = ChatRequest.builder().messages(messages).build();
        // 单节点算一次逻辑调用：首个响应前失败切下一个模型，已产出后不切链
        ChatResponse response = BlockingModelFailover.call(chain, (attempt, model) -> {
            ChatResponse r = modelCacheManager.getChatModel(model.getId()).chat(request);
            attempt.markStarted();
            return r;
        }, new BlockingModelFailover.Listener() {
            @Override
            public void onAttemptStart(FailoverAttempt attempt) {
                if (attempt.index() > 0) {
                    LlmModel switched = attempt.model();
                    context.emit(WorkflowEvents.MODEL_SWITCH, Map.of(
                            "modelName", switched.getModelName() != null ? switched.getModelName() : "",
                            "modelId", switched.getId(),
                            "reason", "上一个模型首内容前失败，切换备用模型"));
                }
            }

            @Override
            public void onPreContentFailure(FailoverAttempt attempt, Throwable error) {
                log.warn("[Failover] 工作流 LLM 节点 {} 模型 {}(id={}) 首内容前失败（尝试 {}/{}）：{}",
                        context.getNode().getId(), attempt.model().getModelName(), attempt.model().getId(),
                        attempt.index() + 1, attempt.total(), FailoverErrors.describe(error));
            }
        });
        String text = response.aiMessage() != null ? response.aiMessage().text() : "";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text != null ? text : "");
        return NodeResult.builder().success(true).output(out).build();
    }

    private static final String TRUNCATE_MARK = "\n…（节点输入超出上下文预算，已截断）";

    /**
     * LLM 节点输入预算：模型窗口 × 比例；System 优先占用预算，Prompt 超出时按句界裁剪并标注截断。
     */
    private String applyInputBudget(NodeExecutionContext context, String systemPrompt, String prompt,
                                    List<LlmModel> chain) {
        double ratio = contextProperties.getNodePromptMaxRatio();
        if (ratio <= 0 || ratio > 1) {
            ratio = 0.7;
        }
        Integer configuredWindow = chain.isEmpty() ? null : chain.get(0).getMaxContextTokens();
        int windowTokens = configuredWindow != null && configuredWindow > 0
                ? configuredWindow : contextProperties.getFallbackContextWindowTokens();
        int budget = Math.max((int) (windowTokens * ratio), TokenEstimator.estimate(TRUNCATE_MARK) + 1);

        int original = TokenEstimator.estimate(prompt);
        int promptBudget = budget - TokenEstimator.estimate(systemPrompt) - TokenEstimator.estimate(TRUNCATE_MARK);
        if (original <= promptBudget) {
            return prompt;
        }

        String clipped = RagContextBudgeter.clip(prompt, Math.max(promptBudget, 1)) + TRUNCATE_MARK;
        int kept = TokenEstimator.estimate(clipped);
        log.warn("工作流 LLM 节点 {} 输入超预算：窗口={}, 比例={}, 预算={} tokens, 原始={} tokens, 保留={} tokens",
                context.getNode() != null ? context.getNode().getId() : "?",
                windowTokens, ratio, budget, original, kept);
        metrics.recordWorkflowNodePromptTrim(original, kept);
        return clipped;
    }
}
