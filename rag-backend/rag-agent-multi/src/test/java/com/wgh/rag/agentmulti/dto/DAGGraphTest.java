package com.wgh.rag.agentmulti.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DAGGraph 单元测试 —— 验证 startNodes（入度为 0）计算与空图 / 单节点图边界。
 * <p>
 * 注意：DAGGraph 本身不提供 getOutEdges(nodeId) 方法，出边过滤逻辑内联在
 * GraphCoordinator / CrewCoordinator 中（本地 outEdges 映射）。此处用等价过滤逻辑
 * 验证 edges 数据可支撑该用法，详见测试报告。
 */
class DAGGraphTest {

    private static GraphNode node(String id) {
        return GraphNode.builder().id(id).type("AGENT").agentId(id).build();
    }

    private static GraphEdge edge(String source, String target) {
        return GraphEdge.builder().source(source).target(target).build();
    }

    /** startNodes：a→b、a→c、b→d 的 DAG 中仅 a 入度为 0 */
    @Test
    void startNodes_dagWithSingleRoot() {
        DAGGraph graph = DAGGraph.builder()
                .nodes(List.of(node("a"), node("b"), node("c"), node("d")))
                .edges(List.of(edge("a", "b"), edge("a", "c"), edge("b", "d")))
                .build();

        assertThat(graph.getStartNodes()).containsExactlyInAnyOrder("a");
    }

    /** startNodes：多个入度为 0 的节点全部返回 */
    @Test
    void startNodes_multipleRoots() {
        DAGGraph graph = DAGGraph.builder()
                .nodes(List.of(node("a"), node("b"), node("c")))
                .edges(List.of(edge("a", "c")))
                .build();

        assertThat(graph.getStartNodes()).containsExactlyInAnyOrder("a", "b");
    }

    /** 空图边界：nodes 为空集合时返回空集合 */
    @Test
    void startNodes_emptyGraph() {
        DAGGraph emptyList = DAGGraph.builder().nodes(List.of()).edges(List.of()).build();
        assertThat(emptyList.getStartNodes()).isEmpty();

        // nodes 为 null（JSON 反序列化缺字段场景）同样返回空集合而不抛 NPE
        DAGGraph nullNodes = new DAGGraph();
        assertThat(nullNodes.getStartNodes()).isEmpty();
    }

    /** 单节点图边界：无任何边时唯一节点即为起点；edges 为 null 也不抛 NPE */
    @Test
    void startNodes_singleNodeGraph() {
        DAGGraph noEdges = DAGGraph.builder()
                .nodes(List.of(node("only")))
                .edges(List.of())
                .build();
        assertThat(noEdges.getStartNodes()).containsExactly("only");

        DAGGraph nullEdges = DAGGraph.builder()
                .nodes(List.of(node("only")))
                .edges(null)
                .build();
        assertThat(nullEdges.getStartNodes()).containsExactly("only");
    }

    /**
     * 出边过滤（GraphCoordinator 构建 outEdges 的等价逻辑）：
     * 按 source 过滤 edges 只得到该节点的出边，其他节点的边被排除。
     */
    @Test
    void outEdges_filterBySource() {
        DAGGraph graph = DAGGraph.builder()
                .nodes(List.of(node("a"), node("b"), node("c")))
                .edges(List.of(edge("a", "b"), edge("a", "c"), edge("b", "c")))
                .build();

        Set<String> outOfA = graph.getEdges().stream()
                .filter(e -> "a".equals(e.getSource()))
                .map(GraphEdge::getTarget)
                .collect(Collectors.toSet());
        Set<String> outOfB = graph.getEdges().stream()
                .filter(e -> "b".equals(e.getSource()))
                .map(GraphEdge::getTarget)
                .collect(Collectors.toSet());
        Set<String> outOfC = graph.getEdges().stream()
                .filter(e -> "c".equals(e.getSource()))
                .map(GraphEdge::getTarget)
                .collect(Collectors.toSet());

        assertThat(outOfA).containsExactlyInAnyOrder("b", "c");
        assertThat(outOfB).containsExactly("c");
        assertThat(outOfC).isEmpty();
    }
}
