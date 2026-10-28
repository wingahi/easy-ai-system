package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VARIABLE 节点 —— 按 assignments 渲染并写入变量池。
 */
@Component
@RequiredArgsConstructor
public class VariableNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;

    @Override
    public String supportedType() {
        return "VARIABLE";
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        Object raw = cfg.get("assignments");
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String name = String.valueOf(map.get("name"));
                    Object value = map.get("value");
                    String rendered = value instanceof String s ? variableResolver.render(s, ctx)
                            : String.valueOf(value);
                    ctx.getVars().put(name, rendered);
                    out.put(name, rendered);
                }
            }
        }
        return NodeResult.builder().success(true).output(out).build();
    }
}
