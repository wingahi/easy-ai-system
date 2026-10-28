package com.wgh.rag.workflow.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画布节点定义（graph_json.nodes[]）。
 */
@Data
public class GraphNode {

    /** 节点唯一 ID，如 n_llm_1 */
    private String id;

    /** 节点类型：START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE */
    private String type;

    /** 显示名 */
    private String name;

    /** 画布坐标 */
    private Position position;

    /** 节点配置，按类型不同结构不同 */
    private Map<String, Object> config = new LinkedHashMap<>();

    /** 失败策略：STOP（默认）/ CONTINUE */
    private String onError;

    @Data
    public static class Position {
        private Double x;
        private Double y;
    }
}
