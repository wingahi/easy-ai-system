package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.ConditionEvaluator;
import com.wgh.rag.workflow.engine.VariableResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CONDITION 节点 —— 渲染并求值条件表达式，输出布尔结果（分支选择由引擎负责）。
 */
@Component
@RequiredArgsConstructor
public class ConditionNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;
    private final ConditionEvaluator conditionEvaluator;

    @Override
    public String supportedType() {
        return "CONDITION";
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        String expr = variableResolver.render(NodeConfigSupport.str(cfg.get("expression")), ctx);
        boolean result = conditionEvaluator.evaluate(expr);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("expression", expr);
        return NodeResult.builder().success(true).output(out).build();
    }
}
