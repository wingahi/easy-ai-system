package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.engine.ConditionEvaluator;
import com.wgh.rag.workflow.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NodeExecutorRegistry 路由测试 —— 9 种类型各归其位，未知类型与原 default 分支报错一致。
 */
class NodeExecutorRegistryTest {

    private final VariableResolver variableResolver = new VariableResolver();

    private NodeExecutorRegistry newRegistry() {
        return new NodeExecutorRegistry(List.of(
                new StartNodeExecutor(),
                new LlmNodeExecutor(variableResolver, null),
                new RetrievalNodeExecutor(variableResolver, null),
                new ToolNodeExecutor(variableResolver, null),
                new WorkflowNodeExecutor(variableResolver, null, null, null),
                new ConditionNodeExecutor(variableResolver, new ConditionEvaluator()),
                new LoopNodeExecutor(),
                new VariableNodeExecutor(variableResolver),
                new EndNodeExecutor(variableResolver)));
    }

    /** 9 种节点类型均能路由到对应的执行器实现 */
    @Test
    void get_routesAllSupportedTypes() {
        NodeExecutorRegistry registry = newRegistry();

        assertThat(registry.get("START")).isInstanceOf(StartNodeExecutor.class);
        assertThat(registry.get("LLM")).isInstanceOf(LlmNodeExecutor.class);
        assertThat(registry.get("RETRIEVAL")).isInstanceOf(RetrievalNodeExecutor.class);
        assertThat(registry.get("TOOL")).isInstanceOf(ToolNodeExecutor.class);
        assertThat(registry.get("WORKFLOW")).isInstanceOf(WorkflowNodeExecutor.class);
        assertThat(registry.get("CONDITION")).isInstanceOf(ConditionNodeExecutor.class);
        assertThat(registry.get("LOOP")).isInstanceOf(LoopNodeExecutor.class);
        assertThat(registry.get("VARIABLE")).isInstanceOf(VariableNodeExecutor.class);
        assertThat(registry.get("END")).isInstanceOf(EndNodeExecutor.class);
    }

    /** 未知节点类型抛出与原 default 分支一致的"不支持的节点类型"异常 */
    @Test
    void get_unknownType_throwsSameAsBefore() {
        NodeExecutorRegistry registry = newRegistry();

        assertThatThrownBy(() -> registry.get("UNKNOWN_X"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("不支持的节点类型：UNKNOWN_X");
    }
}
