package com.wgh.rag.agentmulti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 图节点定义。
 * <p>
 * type 枚举：START / AGENT / CONDITION / LOOP / HITL / END
 * <p>
 * 各 type 专属字段：
 * <ul>
 *   <li>AGENT   → agentId</li>
 *   <li>CONDITION → expression（SpEL / OGNL 等布尔表达式）</li>
 *   <li>LOOP    → maxIterations, breakCondition</li>
 *   <li>HITL    → prompt</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    /** 节点唯一 ID */
    private String id;

    /** 节点类型，见类注释枚举 */
    private String type;

    /** 仅 AGENT：执行该节点的 agentId */
    private String agentId;

    /** 仅 CONDITION：布尔表达式 */
    private String expression;

    /** 仅 LOOP：最大迭代次数 */
    private Integer maxIterations;

    /** 仅 LOOP：跳出循环的布尔表达式 */
    private String breakCondition;

    /** 仅 HITL：给用户的提示语 */
    private String prompt;

    /** 输入模板（可选，将上游上下文渲染为当前节点输入） */
    private String inputTemplate;

    /** 扩展配置 */
    private Map<String, Object> config;
}
