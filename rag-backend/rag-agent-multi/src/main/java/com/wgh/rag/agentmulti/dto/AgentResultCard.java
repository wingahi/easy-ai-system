package com.wgh.rag.agentmulti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 多 Agent 单个 worker 的结构化结果卡片。
 *
 * <p>汇总时只拼接卡片，不再直接塞原始输出，避免 worker 长文本按顺序挤占汇总 prompt。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResultCard {

    private String agentId;
    private String agentName;
    /** 一句话结论（受 Token 预算裁剪） */
    private String conclusion;
    /** 证据引用：检索文件名 + 成功工具名 */
    private List<String> evidence;
    /** 置信度 0~1，来自 Reflection 打分；未启用时为 null */
    private Double confidence;
    private Integer outputChars;
}
