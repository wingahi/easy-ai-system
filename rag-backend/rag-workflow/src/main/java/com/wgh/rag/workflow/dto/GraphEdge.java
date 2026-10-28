package com.wgh.rag.workflow.dto;

import lombok.Data;

/**
 * 画布连线（graph_json.edges[]）。
 */
@Data
public class GraphEdge {

    private String id;

    private String source;

    private String target;

    /** 分支标识：default / true / false / loop / done */
    private String sourceHandle;
}
