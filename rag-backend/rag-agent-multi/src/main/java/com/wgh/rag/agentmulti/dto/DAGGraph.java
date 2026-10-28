package com.wgh.rag.agentmulti.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DAG / 状态图定义（GRAPH_ROUTED 执行模式下使用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DAGGraph {

    private List<GraphNode> nodes;

    private List<GraphEdge> edges;

    /**
     * 计算属性：入度为 0 的节点 id 集合。
     * 由 nodes / edges 动态计算，不参与 JSON 反序列化。
     */
    @JsonIgnore
    public Set<String> getStartNodes() {
        if (nodes == null || nodes.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> targets = edges == null
                ? new HashSet<>()
                : edges.stream().map(GraphEdge::getTarget).collect(Collectors.toSet());
        return nodes.stream()
                .map(GraphNode::getId)
                .filter(id -> !targets.contains(id))
                .collect(Collectors.toSet());
    }
}
