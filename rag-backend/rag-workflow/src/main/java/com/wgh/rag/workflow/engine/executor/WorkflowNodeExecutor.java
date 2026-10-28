package com.wgh.rag.workflow.engine.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.VariableResolver;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WORKFLOW 子流程节点 —— 嵌套执行另一个工作流并把输出带回父流程。
 * 引擎通过 ObjectProvider 懒解析，避免与 WorkflowEngine 形成构造期循环依赖。
 */
@Slf4j
@Component
public class WorkflowNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;
    private final WorkflowMapper workflowMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<WorkflowEngine> workflowEngineProvider;

    public WorkflowNodeExecutor(VariableResolver variableResolver,
                                WorkflowMapper workflowMapper,
                                ObjectMapper objectMapper,
                                ObjectProvider<WorkflowEngine> workflowEngineProvider) {
        this.variableResolver = variableResolver;
        this.workflowMapper = workflowMapper;
        this.objectMapper = objectMapper;
        this.workflowEngineProvider = workflowEngineProvider;
    }

    @Override
    public String supportedType() {
        return "WORKFLOW";
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) throws Exception {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();
        int nestingDepth = context.getNestingDepth();

        Long subId = NodeConfigSupport.asLong(cfg.get("workflowId"));
        if (subId == null) throw new IllegalArgumentException("子流程节点缺少 workflowId");

        Workflow subWorkflow = workflowMapper.selectById(subId);
        if (subWorkflow == null) throw new IllegalStateException("子流程不存在：" + subId);

        GraphValidator.GraphValidationResult vr = new GraphValidator()
                .validate(parseGraph(subWorkflow.getGraphJson()));
        if (vr.hasErrors()) throw new IllegalStateException("子流程图校验失败：" + String.join("；", vr.errors));

        String inputTemplate = NodeConfigSupport.str(cfg.get("inputTemplate"));
        String input;
        if (inputTemplate.isBlank()) {
            input = String.valueOf(ctx.getVars().getOrDefault("lastOutput",
                    ctx.getVars().getOrDefault("input", "")));
        } else {
            input = variableResolver.render(inputTemplate, ctx);
        }

        WorkflowRun subRun = workflowEngineProvider.getObject()
                .runSubWorkflow(subWorkflow, vr.graph, input, vr, nestingDepth + 1);

        String output;
        String outTemplate = NodeConfigSupport.str(cfg.get("outputTemplate"));
        if (outTemplate.isBlank()) {
            output = subRun.getOutputText() != null ? subRun.getOutputText() : "";
        } else {
            // 把子输出暴露到临时变量里再渲染
            ctx.getVars().put("_subOutput_" + subId, subRun.getOutputText());
            output = variableResolver.render(outTemplate, ctx);
        }

        if ("FAILED".equals(subRun.getStatus())) {
            throw new IllegalStateException("子流程执行失败：" + subRun.getErrorMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", output);
        out.put("subRunId", subRun.getId());
        out.put("subStatus", subRun.getStatus());
        return NodeResult.builder().success(true).output(out).build();
    }

    private WorkflowGraph parseGraph(String json) {
        if (json == null || json.isBlank()) throw new IllegalStateException("图为空");
        try {
            return objectMapper.readValue(json, WorkflowGraph.class);
        } catch (Exception e) {
            throw new IllegalStateException("图解析失败：" + e.getMessage());
        }
    }
}
