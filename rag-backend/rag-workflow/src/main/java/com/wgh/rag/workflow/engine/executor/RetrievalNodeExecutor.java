package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.workflow.constant.WorkflowConstants;

import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.llm.util.RagContextBudgeter;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RETRIEVAL 节点 —— 知识库检索并拼接片段文本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;
    private final RagRetrieveService ragRetrieveService;

    @Override
    public String supportedType() {
        return WorkflowConstants.NODE_RETRIEVAL;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        Map<String, Object> cfg = context.config();
        RunContext ctx = context.getRunContext();

        List<Long> kbIds = NodeConfigSupport.asLongList(cfg.get("kbIds"));
        if (kbIds.isEmpty()) throw new IllegalArgumentException("知识库节点未选择知识库");
        String query = variableResolver.render(NodeConfigSupport.str(cfg.get("query")), ctx);
        if (query.isBlank()) query = String.valueOf(ctx.getVars().getOrDefault("input", ""));
        int topK = NodeConfigSupport.asInt(cfg.get("topK"), 5);

        List<EmbeddingMatch<TextSegment>> matches = ragRetrieveService.searchMultiple(kbIds, query);
        if (matches == null) matches = List.of();
        if (matches.size() > topK) matches = matches.subList(0, topK);

        // 工作流节点拿不到下游 LLM 节点的模型配置，使用默认预算参数（兜底窗口 8192 + 硬上限 6000）
        RagContextBudgeter.Result budgetResult = RagContextBudgeter.trimWithDefaults(matches, 0);
        matches = budgetResult.kept();
        log.info("工作流 RETRIEVAL 上下文预算: budget={}, 原始 {} 条/{} tokens, 保留 {} 条/{} tokens, 丢弃 {} 条, 截断={}",
                budgetResult.budgetTokens(), budgetResult.originalCount(), budgetResult.originalTokens(),
                budgetResult.keptCount(), budgetResult.keptTokens(), budgetResult.dropped().size(),
                budgetResult.truncated());

        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> sources = new ArrayList<>();
        int idx = 0;
        for (EmbeddingMatch<TextSegment> match : matches) {
            idx++;
            String content = match.embedded() != null ? match.embedded().text() : "";
            String fileName = null;
            Integer chunkIndex = null;
            if (match.embedded() != null && match.embedded().metadata() != null) {
                fileName = match.embedded().metadata().getString("fileName");
                chunkIndex = match.embedded().metadata().getInteger("chunkIndex");
            }
            text.append("【片段 ").append(idx).append("】")
                    .append(fileName != null ? fileName : "未知来源")
                    .append(chunkIndex != null ? "#" + chunkIndex : "")
                    .append("\n").append(content).append("\n\n");

            Map<String, Object> src = new LinkedHashMap<>();
            src.put("fileName", fileName);
            src.put("chunkIndex", chunkIndex);
            src.put("score", match.score());
            sources.add(src);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text.toString().trim());
        out.put("sources", sources);
        out.put("count", sources.size());
        return NodeResult.builder().success(true).output(out).build();
    }
}
