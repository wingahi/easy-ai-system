package com.wgh.rag.workflow.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流图定义（graph_json 根对象）。
 */
@Data
public class WorkflowGraph {

    private List<GraphNode> nodes = new ArrayList<>();

    private List<GraphEdge> edges = new ArrayList<>();

    /** 防御空集合（不参与 JSON 序列化） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<GraphEdge> getEdgesSafe() {
        return edges != null ? edges : new ArrayList<>();
    }
}
