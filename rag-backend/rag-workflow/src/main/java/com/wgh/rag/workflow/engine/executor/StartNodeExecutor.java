package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.NodeResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * START 节点 —— 输出运行输入。
 */
@Component
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public String supportedType() {
        return "START";
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", context.getRunContext().getVars().getOrDefault("input", ""));
        return NodeResult.builder().success(true).output(out).build();
    }
}
