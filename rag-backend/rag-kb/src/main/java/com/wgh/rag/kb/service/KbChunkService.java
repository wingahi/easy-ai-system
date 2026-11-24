package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.config.property.VectorStoreProperties;
import com.wgh.rag.kb.entity.KbChunk;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KbChunkMapper;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.kb.port.vector.VectorRecord;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * chunk 事实层读写与索引补偿。
 *
 * <p>事实层是原文与元数据的唯一副本；向量/关键词索引可随时按事实层重建。
 * 默认关闭（{@code wgh.vector.fact-layer-enabled=false}），关闭时所有方法为空操作，
 * 现有入库链路行为不变。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbChunkService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SYNCED = "SYNCED";
    public static final String STATUS_STALE = "STALE";

    private final KbChunkMapper chunkMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final VectorStorePort vectorStore;
    private final ModelCacheManager modelCacheManager;
    private final VectorStoreProperties properties;
    private final ObjectMapper objectMapper;

    public boolean enabled() {
        return Boolean.TRUE.equals(properties.getFactLayerEnabled());
    }

    /**
     * 写入文件当前版本的 chunk 事实（先删旧版本，再全部置 PENDING）。
     * 返回是否实际写入；关闭事实层时返回 false。
     */
    public boolean replaceFileChunks(Long kbId, Long fileId, List<TextSegment> segments,
                                     Long embeddingModelId, Integer dimension) {
        if (!enabled() || kbId == null || fileId == null || segments == null) {
            return false;
        }
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, kbId)
                .eq(KbChunk::getFileId, fileId));
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Map<String, Object> metadata = segment.metadata() == null
                    ? Map.of() : segment.metadata().toMap();
            KbChunk chunk = new KbChunk();
            chunk.setTenantId(metadata.get("tenantId") == null
                    ? "default" : String.valueOf(metadata.get("tenantId")));
            chunk.setKbId(kbId);
            chunk.setFileId(fileId);
            chunk.setChunkIndex(intValue(metadata.get("chunkIndex"), i));
            chunk.setContent(segment.text());
            chunk.setContentHash(stringValue(metadata.get("contentHash")));
            chunk.setMetadataJson(toJson(metadata));
            chunk.setEmbeddingModelId(embeddingModelId);
            chunk.setDimension(dimension);
            chunk.setVectorStatus(STATUS_PENDING);
            chunk.setVersion(1);
            chunkMapper.insert(chunk);
        }
        log.debug("✓ 事实层写入 kbId={}, fileId={}, chunks={}", kbId, fileId, segments.size());
        return true;
    }

    /**
     * 索引写入成功后回填 embedding_id 并置 SYNCED（按 kb/file/chunkIndex 定位）。
     */
    @Transactional
    public void markSynced(Long kbId, Long fileId, List<TextSegment> segments, List<String> embeddingIds) {
        if (!enabled() || segments == null || embeddingIds == null) {
            return;
        }
        for (int i = 0; i < segments.size() && i < embeddingIds.size(); i++) {
            Map<String, Object> metadata = segments.get(i).metadata() == null
                    ? Map.of() : segments.get(i).metadata().toMap();
            Integer chunkIndex = intValue(metadata.get("chunkIndex"), i);
            chunkMapper.update(null, new LambdaUpdateWrapper<KbChunk>()
                    .eq(KbChunk::getKbId, kbId)
                    .eq(KbChunk::getFileId, fileId)
                    .eq(KbChunk::getChunkIndex, chunkIndex)
                    .set(KbChunk::getEmbeddingId, embeddingIds.get(i))
                    .set(KbChunk::getVectorStatus, STATUS_SYNCED));
        }
    }

    /** 文件删除/重切时清理事实层。 */
    @Transactional
    public void markFileDeleted(Long kbId, Long fileId) {
        if (!enabled() || fileId == null) {
            return;
        }
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(kbId != null, KbChunk::getKbId, kbId)
                .eq(KbChunk::getFileId, fileId));
    }

    /**
     * 回填 PENDING/STALE chunk：按 (kb, model, dimension) 分组批量 embedding 后 upsert。
     *
     * @param kbId  为空表示跨知识库回填
     * @param limit 单次最多处理条数
     * @return 实际回填条数
     */
    @Transactional
    public int backfill(Long kbId, int limit) {
        if (!enabled()) {
            log.debug("事实层未启用，跳过向量回填");
            return 0;
        }
        int batch = limit > 0 ? limit : (properties.getFactBackfillBatchSize() != null
                ? properties.getFactBackfillBatchSize() : 200);
        List<KbChunk> pending = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(kbId != null, KbChunk::getKbId, kbId)
                .in(KbChunk::getVectorStatus, STATUS_PENDING, STATUS_STALE)
                .orderByAsc(KbChunk::getId)
                .last("LIMIT " + batch));
        if (pending.isEmpty()) {
            return 0;
        }

        Map<String, List<KbChunk>> groups = new LinkedHashMap<>();
        for (KbChunk chunk : pending) {
            groups.computeIfAbsent(chunk.getKbId() + "#" + chunk.getEmbeddingModelId()
                    + "#" + chunk.getDimension(), k -> new ArrayList<>()).add(chunk);
        }

        int synced = 0;
        for (List<KbChunk> group : groups.values()) {
            KbChunk first = group.get(0);
            KnowledgeBase kb = kbMapper.selectById(first.getKbId());
            if (kb == null || kb.getCollectionName() == null) {
                log.warn("事实层回填跳过：知识库 {} 不存在或无集合", first.getKbId());
                continue;
            }
            EmbeddingModel model = modelCacheManager.getEmbeddingModel(first.getEmbeddingModelId());
            if (model == null) {
                log.warn("事实层回填跳过：EmbeddingModel {} 不可用", first.getEmbeddingModelId());
                continue;
            }
            List<TextSegment> segments = group.stream()
                    .map(c -> TextSegment.from(c.getContent(), dev.langchain4j.data.document.Metadata.from(
                            parseMetadata(c.getMetadataJson()))))
                    .toList();
            List<Embedding> embeddings = model.embedAll(segments).content();
            List<VectorRecord> records = new ArrayList<>(group.size());
            for (int i = 0; i < group.size(); i++) {
                String stableId = UUID.nameUUIDFromBytes(
                        ("kbchunk:" + group.get(i).getId()).getBytes(StandardCharsets.UTF_8)).toString();
                // 存量事实层 metadata 可能没有 tenantId（改造前写入），按事实行 tenant_id 补齐，
                // 保证重建出的向量与新建链路口径一致，非默认租户仍能被检索层断言命中。
                Map<String, Object> metadata = new java.util.HashMap<>(
                        parseMetadata(group.get(i).getMetadataJson()));
                if (!metadata.containsKey("tenantId")) {
                    metadata.put("tenantId", group.get(i).getTenantId() != null
                            ? group.get(i).getTenantId()
                            : com.wgh.rag.common.security.TenantContext.tenantIdOrDefault());
                }
                records.add(new VectorRecord(stableId, group.get(i).getContent(),
                        metadata, embeddings.get(i).vector()));
            }
            int dimension = first.getDimension() != null ? first.getDimension() : 1536;
            List<String> ids = vectorStore.upsert(kb.getCollectionName(), records, dimension);
            for (int i = 0; i < group.size(); i++) {
                KbChunk patch = new KbChunk();
                patch.setId(group.get(i).getId());
                patch.setEmbeddingId(ids.get(i));
                patch.setVectorStatus(STATUS_SYNCED);
                chunkMapper.updateById(patch);
            }
            synced += group.size();
        }
        if (synced > 0) {
            log.info("✓ 事实层向量回填 {} 条（候选 {}）", synced, pending.size());
        }
        return synced;
    }

    /**
     * 对账：事实层与向量层行数；向量少于事实时把该库 SYNCED 事实置 STALE 等待回填。
     */
    @Transactional(readOnly = true)
    public ReconcileResult reconcile(Long kbId) {
        if (!enabled() || kbId == null) {
            return new ReconcileResult(kbId, 0, 0, false);
        }
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null || kb.getCollectionName() == null) {
            return new ReconcileResult(kbId, 0, 0, false);
        }
        Long factCount = chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, kbId));
        int vectorCount = vectorStore.countVectors(kb.getCollectionName());
        int facts = factCount == null ? 0 : factCount.intValue();
        boolean mismatch = facts > vectorCount;
        if (mismatch) {
            chunkMapper.update(null, new LambdaUpdateWrapper<KbChunk>()
                    .eq(KbChunk::getKbId, kbId)
                    .eq(KbChunk::getVectorStatus, STATUS_SYNCED)
                    .set(KbChunk::getVectorStatus, STATUS_STALE));
            log.warn("事实层对账不一致 kbId={}: facts={}, vectors={}，已置 STALE 等待回填",
                    kbId, facts, vectorCount);
        }
        return new ReconcileResult(kbId, facts, vectorCount, mismatch);
    }

    public record ReconcileResult(Long kbId, int factCount, int vectorCount, boolean mismatch) {
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
