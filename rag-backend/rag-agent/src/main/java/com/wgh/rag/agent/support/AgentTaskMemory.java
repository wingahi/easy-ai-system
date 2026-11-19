package com.wgh.rag.agent.support;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.context.ContextAssembler;
import com.wgh.rag.context.ContextSegment;
import com.wgh.rag.context.SegmentType;
import com.wgh.rag.kb.service.AgentMemoryService;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.llm.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 长期记忆接入层：任务作用域写入 + 召回后按统一预算组装为 {@code FAR_FACT} 片段。
 *
 * <p>召回范围：当前任务（TASK:taskId）与所属会话（SESSION:sessionId）合并，
 * 分别对应「同任务续跑」与「同会话后续任务」两种复用场景。召回失败一律降级为空串，
 * 不影响主流程。
 *
 * <p>预算：召回内容交给 {@link ContextAssembler} 以 {@link SegmentType#FAR_FACT} 类型
 * 参与统一分配，复用 {@code wgh.rag.memory.max-tokens}，不另起裁剪逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskMemory {

    private final AgentMemoryService memoryService;
    private final RagProperties ragProperties;

    /**
     * 召回并组装长期记忆块；无命中、关闭或异常时返回空串。
     *
     * @param taskId   任务 ID（单 Agent 为 planId，多 Agent 为 runId），可为空
     * @param sessionId 会话 ID，可为空
     * @param query    召回查询（当前步骤/用户输入）
     */
    public String recallBlock(String taskId, Long sessionId, String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        RagProperties.Memory cfg = memoryConfig();
        if (!Boolean.TRUE.equals(cfg.getEnabled())) {
            return "";
        }
        try {
            List<AgentMemoryService.MemoryHit> hits = new ArrayList<>();
            if (taskId != null && !taskId.isBlank()) {
                hits.addAll(memoryService.recall(AgentMemoryService.ScopeType.TASK, taskId, query));
            }
            if (sessionId != null && sessionId > 0) {
                hits.addAll(memoryService.recall(
                        AgentMemoryService.ScopeType.SESSION, String.valueOf(sessionId), query));
            }
            List<AgentMemoryService.MemoryHit> merged = mergeByScore(hits, cfg.getTopK());
            if (merged.isEmpty()) {
                return "";
            }

            List<ContextSegment> segments = new ArrayList<>();
            for (int i = 0; i < merged.size(); i++) {
                segments.add(new ContextSegment(i, SegmentType.FAR_FACT, merged.get(i).content(), true));
            }
            int budget = cfg.getMaxTokens() != null ? Math.max(1, cfg.getMaxTokens()) : 500;
            ContextAssembler.Result result = ContextAssembler.assemble(
                    budget, ContextAssembler.Options.of(1.0, budget, 0), segments,
                    TokenEstimator::estimate, RagContextBudgeter::clip);
            if (result.kept().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(
                    "## 长期记忆（按相关度召回，仅供参考；与当前任务冲突时以当前为准）");
            for (ContextAssembler.KeptSegment kept : result.kept()) {
                sb.append("\n- ").append(kept.content());
            }
            log.debug("✓ 组装长期记忆: task={}, session={}, hits={}, tokens={}",
                    taskId, sessionId, result.kept().size(), result.keptTokens());
            return sb.toString();
        } catch (Exception e) {
            log.warn("长期记忆召回/组装失败（降级为空）: task={}, session={}, reason={}",
                    taskId, sessionId, e.getMessage());
            return "";
        }
    }

    /**
     * 写入任务级记忆（内部完成摘要/截断与 TASK→SESSION 镜像）；失败只记日志。
     */
    public void rememberTask(String taskId, Long sessionId, String agentId,
                             String memoryType, String content) {
        if (taskId == null || taskId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        try {
            memoryService.rememberTask(taskId, sessionId, agentId, memoryType, content);
        } catch (Exception e) {
            log.warn("写入任务记忆失败（忽略）: task={}, agent={}, reason={}",
                    taskId, agentId, e.getMessage());
        }
    }

    /** 按正文去重（保留较高分），按分数降序并截取 topK。 */
    private List<AgentMemoryService.MemoryHit> mergeByScore(
            List<AgentMemoryService.MemoryHit> hits, Integer topK) {
        Map<String, AgentMemoryService.MemoryHit> byContent = new LinkedHashMap<>();
        for (AgentMemoryService.MemoryHit hit : hits) {
            if (hit == null || hit.content() == null || hit.content().isBlank()) {
                continue;
            }
            byContent.merge(hit.content(), hit,
                    (left, right) -> right.score() > left.score() ? right : left);
        }
        List<AgentMemoryService.MemoryHit> merged = new ArrayList<>(byContent.values());
        merged.sort(Comparator.comparingDouble(AgentMemoryService.MemoryHit::score).reversed());
        int limit = topK != null && topK > 0 ? topK : 5;
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }

    private RagProperties.Memory memoryConfig() {
        RagProperties.Memory cfg = ragProperties.getMemory();
        return cfg != null ? cfg : new RagProperties.Memory();
    }
}
