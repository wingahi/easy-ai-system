package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 节点 —— 渲染 prompt 后调用大模型。
 */
@Component
@RequiredArgsConstructor
public class LlmNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;
    private final ModelCacheManager modelCacheManager;

    @Override
    public String supportedType() {
        return "LLM";
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

        List<ChatMessage> messages = new ArrayList<>();
        if (!systemPrompt.isBlank()) messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(prompt));

        ChatModel model = modelCacheManager.getChatModel(modelId);
        ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
        String text = response.aiMessage() != null ? response.aiMessage().text() : "";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text != null ? text : "");
        return NodeResult.builder().success(true).output(out).build();
    }
}
