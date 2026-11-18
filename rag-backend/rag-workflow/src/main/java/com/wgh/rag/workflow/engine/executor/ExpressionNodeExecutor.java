package com.wgh.rag.workflow.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.ExpressionSandbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EXPRESSION 节点 —— 用受限表达式对上游节点输出做求值/转换，结果写入节点输出并可选写入变量池。
 *
 * <p>节点配置：
 * <ul>
 *   <li>{@code expression}：必填，SpEL 受限沙箱表达式（语法子集见 {@link ExpressionSandbox}）；</li>
 *   <li>{@code resultVar}：可选，求值结果同时写入 {@code vars[resultVar]}，供下游 {@code {{vars.x}}} 引用。</li>
 * </ul>
 *
 * <p>节点输出：{@code value}（原始值）、{@code text}（字符串化结果，下游 {@code {{nodeId.text}}} 取此值）、
 * {@code expression}、{@code resultType}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpressionNodeExecutor implements NodeExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public String supportedType() {
        return WorkflowConstants.NODE_EXPRESSION;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        String expression = NodeConfigSupport.str(cfg.get("expression")).trim();
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("表达式节点缺少表达式");
        }

        Object value = ExpressionSandbox.evaluate(expression, buildVariables(ctx));
        String text = stringify(value);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("text", text);
        out.put("expression", expression);
        out.put("resultType", value == null ? "null" : value.getClass().getSimpleName());

        String resultVar = NodeConfigSupport.str(cfg.get("resultVar")).trim();
        if (!resultVar.isEmpty()) {
            ctx.getVars().put(resultVar, value);
        }

        return NodeResult.builder().success(true).output(out).build();
    }

    /** 暴露给表达式的只读上下文：input / last / loop / vars / nodes / fn。 */
    private Map<String, Object> buildVariables(RunContext ctx) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("input", ctx.getVars().getOrDefault("input", ""));
        variables.put("last", ctx.getVars().getOrDefault("lastOutput", ""));
        variables.put("loop", ctx.getCurrentLoopIteration() == null ? 0 : ctx.getCurrentLoopIteration());
        variables.put("vars", ctx.getVars());
        variables.put("nodes", ctx.getNodeOutputs());
        variables.put("fn", ExpressionSandbox.Functions.INSTANCE);
        return variables;
    }

    /** 结果字符串化：字符串原样、标量 String.valueOf、Map/List 走 JSON，避免输出不可读。 */
    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.debug("表达式结果 JSON 序列化失败，回退 String.valueOf", e);
            return String.valueOf(value);
        }
    }
}
