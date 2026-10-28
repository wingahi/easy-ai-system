package com.wgh.rag.workflow.dto;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单节点执行结果。
 */
@Data
@Builder
public class NodeResult {

    /** 写入运行上下文 nodeOutputs[nodeId] */
    @Builder.Default
    private Map<String, Object> output = new LinkedHashMap<>();

    private boolean success;

    private String error;
}
