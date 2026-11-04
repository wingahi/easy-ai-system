package com.wgh.rag.workflow.engine;

import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.dto.GraphEdge;
import com.wgh.rag.workflow.dto.GraphNode;
import com.wgh.rag.workflow.dto.WorkflowGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流图校验：保存 / 运行前的结构约束（唯一 START、END 约束、节点配置、非法环、回边豁免）。
 */
@DisplayName("工作流图校验")
class GraphValidatorTest {

    private final GraphValidator validator = new GraphValidator();

    // ===== 图基本结构 =====

    @Test
    @DisplayName("空图 → 至少需要一个节点")
    void emptyGraph() {
        GraphValidator.GraphValidationResult nullResult = validator.validate(null);
        assertThat(nullResult.errors).containsExactly("工作流至少需要一个节点");

        WorkflowGraph empty = new WorkflowGraph();
        assertThat(validator.validate(empty).errors).containsExactly("工作流至少需要一个节点");
    }

    @Test
    @DisplayName("节点 id 缺失 / 重复 / 类型非法均被检出")
    void invalidNodes() {
        WorkflowGraph graph = graph(
                List.of(node(null, WorkflowConstants.NODE_LLM),
                        node("dup", WorkflowConstants.NODE_LLM),
                        node("dup", WorkflowConstants.NODE_LLM),
                        node("bad-type", "unknown")),
                List.of());

        assertThat(validator.validate(graph).errors)
                .contains("存在缺少 id 的节点",
                        "节点 id 重复：dup",
                        "节点类型非法：bad-type → unknown");
    }

    @Test
    @DisplayName("必须且只能有一个 START、至少一个 END")
    void startEndCounts() {
        WorkflowGraph none = graph(
                List.of(node("llm", WorkflowConstants.NODE_LLM)),
                List.of());
        assertThat(validator.validate(none).errors)
                .contains("必须且只能有一个 START 节点，当前 0 个", "至少需要一个 END 节点");

        WorkflowGraph twoStarts = graph(
                List.of(node("s1", WorkflowConstants.NODE_START),
                        node("s2", WorkflowConstants.NODE_START),
                        node("end", WorkflowConstants.NODE_END)),
                List.of());
        assertThat(validator.validate(twoStarts).errors)
                .contains("必须且只能有一个 START 节点，当前 2 个");
    }

    @Test
    @DisplayName("START 不允许有入边、END 不允许有出边")
    void startEndEdgeConstraints() {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        node("llm", WorkflowConstants.NODE_LLM, Map.of("modelId", 8)),
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("llm", "start"), edge("start", "llm"), edge("llm", "end"), edge("end", "llm")));

        assertThat(validator.validate(graph).errors)
                .contains("START 节点不允许有入边：start", "END 节点不允许有出边：end");
    }

    @Test
    @DisplayName("连线端点不存在被检出")
    void danglingEdge() {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START), node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "ghost"), edge("phantom", "end")));

        assertThat(validator.validate(graph).errors)
                .contains("连线目标节点不存在：ghost", "连线源节点不存在：phantom");
    }

    // ===== 节点必填配置 =====

    @Test
    @DisplayName("LLM/RETRIEVAL/TOOL/CONDITION/WORKFLOW 缺少必填配置被检出")
    void requiredNodeConfig() {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        node("llm", WorkflowConstants.NODE_LLM),
                        node("retrieval", WorkflowConstants.NODE_RETRIEVAL),
                        node("tool", WorkflowConstants.NODE_TOOL),
                        node("condition", WorkflowConstants.NODE_CONDITION),
                        node("sub", WorkflowConstants.NODE_WORKFLOW),
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "end")));

        assertThat(validator.validate(graph).errors)
                .contains("LLM 节点未选择模型：llm",
                        "知识库节点未选择知识库：retrieval",
                        "工具节点未选择工具：tool",
                        "条件节点缺少表达式：condition",
                        "子流程节点未选择工作流：sub");
    }

    @Test
    @DisplayName("配置值以字符串形式提供也可通过（configLong 兼容字符串数字）")
    void stringConfigValuesAccepted() {
        WorkflowGraph graph = validGraph(node("llm", WorkflowConstants.NODE_LLM, Map.of("modelId", "8")));

        GraphValidator.GraphValidationResult result = validator.validate(graph);

        assertThat(result.hasErrors()).isFalse();
    }

    // ===== 环路 =====

    @Test
    @DisplayName("普通节点成环（非回边）→ 非法环")
    void illegalCycle() {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        node("a", WorkflowConstants.NODE_HITL),
                        node("b", WorkflowConstants.NODE_HITL),
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "a"), edge("a", "b"), edge("b", "a")));

        assertThat(validator.validate(graph).errors)
                .contains("检测到非法环（仅允许连回 LOOP 节点的回边），请检查连线");
    }

    @Test
    @DisplayName("指向 LOOP 的回边豁免：LOOP→上游 不视为环，并产出回边集合")
    void loopBackEdgeAllowed() {
        WorkflowGraph graph = graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        node("llm", WorkflowConstants.NODE_LLM, Map.of("modelId", 8)),
                        node("loop", WorkflowConstants.NODE_LOOP),
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "llm"), edge("llm", "loop"), edge("loop", "llm"), edge("llm", "end")));

        GraphValidator.GraphValidationResult result = validator.validate(graph);

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.backEdgeSources).containsExactly("llm");
        assertThat(result.parallelInDegree)
                .containsEntry("start", 0)
                .containsEntry("llm", 2)   // start→llm 与 loop→llm（回边不计入 rawInDegree 但计入平行度）
                .containsEntry("loop", 0)
                .containsEntry("end", 1);
    }

    // ===== 正常图 =====

    @Test
    @DisplayName("合法图：无错误并返回入边计数")
    void validGraphPasses() {
        WorkflowGraph graph = validGraph(node("llm", WorkflowConstants.NODE_LLM, Map.of("modelId", 8)));

        GraphValidator.GraphValidationResult result = validator.validate(graph);

        assertThat(result.hasErrors()).isFalse();
        assertThat(result.parallelInDegree)
                .containsEntry("start", 0)
                .containsEntry("llm", 1)
                .containsEntry("end", 1);
    }

    // ===== 辅助 =====

    private WorkflowGraph validGraph(GraphNode llm) {
        return graph(
                List.of(node("start", WorkflowConstants.NODE_START),
                        llm,
                        node("end", WorkflowConstants.NODE_END)),
                List.of(edge("start", "llm"), edge("llm", "end")));
    }

    private WorkflowGraph graph(List<GraphNode> nodes, List<GraphEdge> edges) {
        WorkflowGraph graph = new WorkflowGraph();
        graph.setNodes(new ArrayList<>(nodes));
        graph.setEdges(new ArrayList<>(edges));
        return graph;
    }

    private GraphNode node(String id, String type) {
        return node(id, type, Map.of());
    }

    private GraphNode node(String id, String type, Map<String, Object> config) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setType(type);
        node.setConfig(new LinkedHashMap<>(config));
        return node;
    }

    private GraphEdge edge(String source, String target) {
        GraphEdge edge = new GraphEdge();
        edge.setSource(source);
        edge.setTarget(target);
        return edge;
    }
}
