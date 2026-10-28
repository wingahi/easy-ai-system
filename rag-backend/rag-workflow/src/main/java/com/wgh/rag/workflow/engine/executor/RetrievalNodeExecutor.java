package com.wgh.rag.workflow.engine.executor;

import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.workflow.dto.NodeResult;
import com.wgh.rag.workflow.dto.RunContext;
import com.wgh.rag.workflow.engine.VariableResolver;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RETRIEVAL 节点 —— 知识库检索并拼接片段文本。
 */
@Component
@RequiredArgsConstructor
public class RetrievalNodeExecutor implements NodeExecutor {

    private final VariableResolver variableResolver;
    private final RagRetrieveService ragRetrieveService;

    @Override
    public String supportedType() {
        return "RETRIEVAL";
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
