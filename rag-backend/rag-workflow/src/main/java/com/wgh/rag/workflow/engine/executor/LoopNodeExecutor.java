package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LOOP 节点 —— 首次进入时初始化轮次（回边重入由引擎调度逻辑处理）。
 */
@Component
public class LoopNodeExecutor implements NodeExecutor {

    @Override
    public String supportedType() {
        return "LOOP";
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        RunContext ctx = context.getRunContext();

        int max = NodeConfigSupport.intConfig(context.getNode(), "maxIterations", 3);
        ctx.getLoopIterations().put(context.getNode().getId(), 1);
        ctx.setCurrentLoopIteration(1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("iteration", 1);
        out.put("maxIterations", max);
        return NodeResult.builder().success(true).output(out).build();
    }
}
