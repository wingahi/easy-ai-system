package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.ToolNodeInvoker;
import com.wgh.rag.workflow.engine.VariableResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TOOL 节点 —— 渲染参数后按 toolId 调用工具。
 */
@Component
@RequiredArgsConstructor
public class ToolNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;
    private final ToolNodeInvoker toolNodeInvoker;

    @Override
    public String supportedType() {
        return "TOOL";
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) throws Exception {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        Long toolId = NodeConfigSupport.asLong(cfg.get("toolId"));
        if (toolId == null) throw new IllegalArgumentException("工具节点缺少 toolId");

        Object rawArgs = cfg.get("arguments");
        Map<String, Object> args = new LinkedHashMap<>();
        if (rawArgs instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object v = entry.getValue();
                args.put(String.valueOf(entry.getKey()),
                        v instanceof String s ? variableResolver.render(s, ctx) : v);
            }
        }

        String text = toolNodeInvoker.invoke(toolId, args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("success", text == null || !text.startsWith("Error:"));
        return NodeResult.builder().success(true).output(out).build();
    }
}
