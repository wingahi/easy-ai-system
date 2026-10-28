package com.wgh.rag.agentmulti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图边定义。
 * <p>
 * sourceHandle 约定：
 * <ul>
 *   <li>普通节点 → null</li>
 *   <li>CONDITION → "true" / "false"</li>
 *   <li>LOOP → "loop"（继续循环）/ "done"（跳出）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {

    private String source;

    private String target;

    /** 源节点输出端口标识，可为 null */
    private String sourceHandle;
}
