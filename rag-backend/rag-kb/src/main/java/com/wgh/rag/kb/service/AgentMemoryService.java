package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.AgentMemory;
import com.wgh.rag.kb.mapper.AgentMemoryMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.kb.port.vector.VectorFilter;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorRecord;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.llm.mapper.EmbeddingModelMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索式记忆服务 - 会话/任务级记忆的写入与召回。
 *
 * <p>向量存 pgvector 独立表 wgh_agent_memory_vec（metadata.scopeKey 隔离作用域），
 * 正文与元数据存 wgh_agent_memory；embedding 使用系统默认（第一个启用的）向量模型。
 * 任何异常只记日志并降级为空结果，不影响聊天主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    /** pgvector 向量表名（由 PgVectorEmbeddingStore 自动建表） */
    public static final String VEC_TABLE = "wgh_agent_memory_vec";

    public enum ScopeType { SESSION, TASK }

    /** 召回结果 */
    public record MemoryHit(String content, double score) {}

    private final AgentMemoryMapper memoryMapper;
    private final EmbeddingModelMapper embeddingModelMapper;
    private final VectorStorePort vectorStoreService;
    private final ModelCacheManager modelCacheManager;
    private final RagProperties ragProperties;

    /** 可选摘要器与指标：缺失时分别回退到截断与不埋点（保持单测可直接 new） */
    @Autowired(required = false)
    private AgentMemorySummarizer summarizer;

    @Autowired(required = false)
    private RagMetrics metrics;

    /** 写入一条记忆；同作用域同正文自动去重，失败只记日志。 */
    public void remember(ScopeType scopeType, String scopeId, String agentId,
                         String memoryType, String content, Long sourceMessageId) {
        if (!enabled() || scopeType == null || isBlank(scopeId) || isBlank(content)) {
            return;
        }
        String hash = sha256(content);
        try {
            Long exists = memoryMapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
                    .eq(AgentMemory::getScopeType, scopeType.name())
                    .eq(AgentMemory::getScopeId, scopeId)
                    .eq(AgentMemory::getContentHash, hash)
                    .eq(AgentMemory::getDeleted, 0));
            if (exists != null && exists > 0) {
                log.debug("记忆已存在，跳过写入: scope={}:{}, hash={}", scopeType, scopeId, hash);
                if (metrics != null) metrics.recordMemoryDedupSkip(scopeType.name());
                return;
            }
            EmbeddingModelConfig config = resolveEmbeddingConfig();
            if (config == null) {
                log.warn("无可用的 embedding 模型，跳过记忆写入: scope={}:{}", scopeType, scopeId);
                return;
            }
            EmbeddingModel embeddingModel = modelCacheManager.getEmbeddingModel(config.getId());
            Embedding embedding = embeddingModel.embed(content).content();
            String scopeKey = scopeKey(scopeType, scopeId);
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("scopeKey", scopeKey);
            // 共享向量集合必须显式写入租户，避免不同租户的记忆在同一集合内串读
            metadata.put("tenantId", TenantContext.tenantIdOrDefault());
            metadata.put("memoryType", memoryType != null ? memoryType : "SUMMARY");
            if (!isBlank(agentId)) {
                metadata.put("agentId", agentId);
            }
            int dimension = config.getDimension() != null ? config.getDimension() : 1024;
            java.util.List<String> ids = vectorStoreService.upsert(VEC_TABLE,
                    java.util.List.of(VectorRecord.of(content, metadata, embedding.vector())), dimension);
            String embeddingId = ids.isEmpty() ? null : ids.get(0);

            AgentMemory memory = new AgentMemory();
            memory.setScopeType(scopeType.name());
            memory.setScopeId(scopeId);
            memory.setAgentId(agentId);
            memory.setMemoryType(memoryType != null ? memoryType : "SUMMARY");
            memory.setContent(content);
            memory.setContentHash(hash);
            memory.setEmbeddingId(embeddingId);
            memory.setSourceMessageId(sourceMessageId);
            memory.setTokenCount(estimateTokens(content));
            memoryMapper.insert(memory);
            if (metrics != null) metrics.recordMemoryWrite(scopeType.name());
            log.debug("✓ 写入记忆: scope={}:{}, type={}, tokens={}", scopeType, scopeId, memoryType,
                    memory.getTokenCount());
        } catch (Exception e) {
            log.warn("记忆写入失败（忽略）: scope={}:{}, reason={}", scopeType, scopeId, e.getMessage());
        }
    }

    /**
     * 写入任务级记忆：先做摘要（LLM 可选，失败回退截断），再写入 TASK 作用域；
     * 传了 sessionId 时同步镜像到 SESSION 作用域，使任务结束后仍可被同会话召回。
     */
    public void rememberTask(String taskId, Long sessionId, String agentId,
                             String memoryType, String content) {
        if (!enabled() || isBlank(taskId) || isBlank(content)) {
            return;
        }
        String summary = summarizeOrTruncate(content);
        remember(ScopeType.TASK, taskId, agentId, memoryType, summary, null);
        if (sessionId != null && sessionId > 0) {
            remember(ScopeType.SESSION, String.valueOf(sessionId), agentId, memoryType, summary, null);
        }
    }

    /** 记忆正文准备：优先 LLM 摘要，关闭或失败时按配置截断。 */
    String summarizeOrTruncate(String content) {
        RagProperties.Memory cfg = memoryConfig();
        if (summarizer != null) {
            String summary = summarizer.summarize(content);
            if (!isBlank(summary)) {
                return summary;
            }
        }
        int maxChars = cfg.getFallbackSummaryChars() != null
                ? Math.max(1, cfg.getFallbackSummaryChars()) : 800;
        return content.length() > maxChars ? content.substring(0, maxChars) : content;
    }

    /**
     * 按作用域召回记忆：scopeKey 过滤 + 相似度阈值 + Token 预算裁剪。
     */
    public List<MemoryHit> recall(ScopeType scopeType, String scopeId, String query) {
        if (!enabled() || scopeType == null || isBlank(scopeId) || isBlank(query)) {
            return List.of();
        }
        RagProperties.Memory cfg = memoryConfig();
        try {
            EmbeddingModelConfig config = resolveEmbeddingConfig();
            if (config == null) {
                return List.of();
            }
            EmbeddingModel embeddingModel = modelCacheManager.getEmbeddingModel(config.getId());
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            List<VectorMatch> matches = vectorStoreService.search(VEC_TABLE, queryEmbedding.vector(),
                    cfg.getTopK(), cfg.getMinScore(), scopeFilter(scopeType, scopeId));
            if (matches.isEmpty()) {
                return List.of();
            }
            // 逻辑删除兜底：向量表不感知 deleted，按 embeddingId 回查业务表
            Set<String> alive = aliveEmbeddingIds(matches);
            int maxChars = cfg.getMaxTokens() * 2;
            List<MemoryHit> hits = new ArrayList<>();
            int used = 0;
            for (VectorMatch match : matches) {
                if (!alive.contains(match.id())) {
                    continue;
                }
                String text = match.text();
                if (isBlank(text)) {
                    continue;
                }
                int cost = estimateTokens(text);
                if (used + cost > cfg.getMaxTokens()) {
                    break;
                }
                used += cost;
                hits.add(new MemoryHit(text, match.score()));
            }
            log.debug("✓ 召回记忆: scope={}:{}, hits={}, tokens={}", scopeType, scopeId, hits.size(), used);
            if (metrics != null) metrics.recordMemoryRecallHits(scopeType.name(), hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("记忆召回失败（降级为空）: scope={}:{}, reason={}", scopeType, scopeId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 作用域过滤条件：scopeKey + 非默认租户断言。
     * <p>默认租户不加租户条件，兼容改造前未写 tenantId 的存量记忆；非默认租户强制按租户过滤。</p>
     */
    private VectorFilter scopeFilter(ScopeType scopeType, String scopeId) {
        java.util.Map<String, Object> equals = new java.util.HashMap<>();
        equals.put("scopeKey", scopeKey(scopeType, scopeId));
        String tenantId = TenantContext.currentTenantId();
        boolean scoped = tenantId != null && !tenantId.isBlank()
                && !TenantContext.DEFAULT_TENANT.equals(tenantId);
        return new VectorFilter(scoped ? tenantId : null, equals, java.util.Map.of());
    }

    private Set<String> aliveEmbeddingIds(List<VectorMatch> matches) {
        List<String> ids = matches.stream().map(VectorMatch::id).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        try {
            return memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                            .in(AgentMemory::getEmbeddingId, ids)
                            .eq(AgentMemory::getDeleted, 0))
                    .stream().map(AgentMemory::getEmbeddingId).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("记忆存活校验失败，按向量结果返回: reason={}", e.getMessage());
            return Set.copyOf(ids);
        }
    }

    private EmbeddingModelConfig resolveEmbeddingConfig() {
        List<EmbeddingModelConfig> list = embeddingModelMapper.selectList(
                new LambdaQueryWrapper<EmbeddingModelConfig>()
                        .eq(EmbeddingModelConfig::getStatus, 1)
                        .orderByAsc(EmbeddingModelConfig::getId)
                        .last("LIMIT 1"));
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private boolean enabled() {
        return memoryConfig().getEnabled();
    }

    private RagProperties.Memory memoryConfig() {
        RagProperties.Memory cfg = ragProperties.getMemory();
        return cfg != null ? cfg : new RagProperties.Memory();
    }

    private static String scopeKey(ScopeType scopeType, String scopeId) {
        return scopeType.name() + ":" + scopeId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 估算 Token：CJK 按 1 字 ≈ 1 token，其他按 4 字符 ≈ 1 token */
    static int estimateTokens(String text) {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x2E80) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
