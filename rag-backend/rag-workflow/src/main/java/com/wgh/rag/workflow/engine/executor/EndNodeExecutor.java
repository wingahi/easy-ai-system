package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * END 节点 —— 渲染最终输出（workflow_complete 事件由引擎负责）。
 */
@Component
@RequiredArgsConstructor
public class EndNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;

    @Override
    public String supportedType() {
        return "END";
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        String template = NodeConfigSupport.str(cfg.get("output"));
        String text = template.isBlank()
                ? String.valueOf(ctx.getVars().getOrDefault("lastOutput", ""))
                : variableResolver.render(template, ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        return NodeResult.builder().success(true).output(out).build();
    }
}
