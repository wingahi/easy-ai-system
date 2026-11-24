package com.wgh.rag.kb.service.memory;

import com.wgh.rag.kb.port.VectorStoreBackend;
import com.wgh.rag.kb.port.vector.CollectionInfo;
import com.wgh.rag.kb.port.vector.ScanPage;
import com.wgh.rag.kb.port.vector.VectorFilter;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorRange;
import com.wgh.rag.kb.port.vector.VectorRecord;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量后端（开发/测试与端口切片测试用，不用于生产）。
 *
 * <p>用于验证「换后端不改业务代码」：通过 {@code wgh.vector.backend=memory} 启用。
 * 进程内保存，重启即丢失；不具备 ANN 索引与持久化能力。</p>
 */
@Slf4j
@Service("memoryVectorStore")
@RequiredArgsConstructor
public class InMemoryVectorStoreService implements VectorStoreBackend {

    private final ModelCacheManager modelCacheManager;

    private final Map<String, CollectionState> collections = new ConcurrentHashMap<>();

    @Override
    public String backendName() {
        return "memory";
    }

    @Override
    public void ensureCollection(String collection, Integer dimension) {
        if (collection == null) return;
        collections.computeIfAbsent(collection, k -> new CollectionState(dimension));
    }

    @Override
    public void dropCollection(String collection) {
        if (collection != null) {
            collections.remove(collection);
        }
    }

    @Override
    public void deleteByFileId(String collection, Long fileId) {
        CollectionState state = collections.get(collection);
        if (state == null || fileId == null) return;
        state.records.entrySet().removeIf(e -> String.valueOf(fileId)
                .equals(String.valueOf(e.getValue().metadata().get("fileId"))));
    }

    @Override
    public void deleteByEmbeddingIds(String collection, Collection<String> embeddingIds) {
        CollectionState state = collections.get(collection);
        if (state == null || embeddingIds == null) return;
        for (String id : embeddingIds) {
            state.records.remove(id);
        }
    }

    @Override
    public List<String> insertVectors(String collection, List<TextSegment> segments,
                                      Long embeddingModelId, Integer dimension) {
        if (segments == null || segments.isEmpty()) return List.of();
        int dim = dimension != null ? dimension : 1024;
        ensureCollection(collection, dim);
        EmbeddingModel model = modelCacheManager.getEmbeddingModel(embeddingModelId);
        if (model == null) {
            throw new IllegalStateException("找不到 EmbeddingModel id=" + embeddingModelId);
        }
        List<Embedding> embeddings = model.embedAll(segments).content();
        List<VectorRecord> records = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            records.add(VectorRecord.of(segment.text(), segment.metadata().toMap(),
                    embeddings.get(i).vector()));
        }
        return upsert(collection, records, dim);
    }

    @Override
    public Set<String> existingContentHashes(String collection) {
        CollectionState state = collections.get(collection);
        if (state == null) return Set.of();
        Set<String> hashes = new HashSet<>();
        for (VectorRecord record : state.records.values()) {
            Object hash = record.metadata().get("contentHash");
            if (hash != null && !String.valueOf(hash).isBlank()) {
                hashes.add(String.valueOf(hash));
            }
        }
        return hashes;
    }

    @Override
    public int countVectors(String collection) {
        CollectionState state = collections.get(collection);
        return state == null ? 0 : state.records.size();
    }

    @Override
    public List<VectorMatch> search(String collection, float[] queryVector, int topK,
                                    double minScore, VectorFilter filter) {
        CollectionState state = collections.get(collection);
        if (state == null || queryVector == null || topK <= 0) return List.of();
        List<VectorMatch> matches = new ArrayList<>();
        for (VectorRecord record : state.records.values()) {
            if (record.vector() == null || !matches(record.metadata(), filter)) continue;
            double score = cosine(queryVector, record.vector());
            if (score < minScore) continue;
            matches.add(new VectorMatch(record.id(), score, VectorScoreType.COSINE,
                    record.text(), record.metadata()));
        }
        matches.sort(Comparator.comparingDouble(VectorMatch::score).reversed());
        return matches.size() <= topK ? matches : matches.subList(0, topK);
    }

    @Override
    public List<String> upsert(String collection, List<VectorRecord> records, int dimension) {
        if (records == null || records.isEmpty()) return List.of();
        CollectionState state = collections.computeIfAbsent(collection,
                k -> new CollectionState(dimension));
        List<String> ids = new ArrayList<>(records.size());
        for (VectorRecord record : records) {
            String id = record.id() != null && !record.id().isBlank()
                    ? record.id() : UUID.randomUUID().toString();
            ids.add(id);
            state.records.put(id, new VectorRecord(id, record.text(), record.metadata(), record.vector()));
        }
        log.debug("memory 后端 upsert {} 条到 {}", ids.size(), collection);
        return ids;
    }

    @Override
    public ScanPage scan(String collection, String cursor, int batchSize) {
        CollectionState state = collections.get(collection);
        if (state == null || batchSize <= 0) return ScanPage.empty();
        List<VectorRecord> sorted = new ArrayList<>(state.records.values());
        sorted.sort(Comparator.comparing(VectorRecord::id));
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            while (start < sorted.size() && sorted.get(start).id().compareTo(cursor) <= 0) {
                start++;
            }
        }
        int end = Math.min(start + batchSize, sorted.size());
        List<VectorRecord> page = new ArrayList<>(sorted.subList(start, end));
        String next = end < sorted.size() && !page.isEmpty() ? page.get(page.size() - 1).id() : null;
        return new ScanPage(page, next);
    }

    @Override
    public CollectionInfo describe(String collection) {
        CollectionState state = collections.get(collection);
        if (state == null) {
            return new CollectionInfo(collection, backendName(), null, 0);
        }
        return new CollectionInfo(collection, backendName(), state.dimension, state.records.size());
    }

    private static boolean matches(Map<String, Object> metadata, VectorFilter filter) {
        if (filter == null || filter.isEmpty()) return true;
        if (filter.tenantId() != null
                && !String.valueOf(filter.tenantId()).equals(String.valueOf(metadata.get("tenantId")))) {
            return false;
        }
        for (Map.Entry<String, Object> entry : filter.equals().entrySet()) {
            if (entry.getValue() == null) continue;
            if (!String.valueOf(entry.getValue()).equals(String.valueOf(metadata.get(entry.getKey())))) {
                return false;
            }
        }
        for (Map.Entry<String, VectorRange> entry : filter.ranges().entrySet()) {
            Object raw = metadata.get(entry.getKey());
            if (raw == null) return false;
            double value;
            try {
                value = Double.parseDouble(String.valueOf(raw));
            } catch (NumberFormatException e) {
                return false;
            }
            VectorRange range = entry.getValue();
            if (range != null && range.min() != null && value < range.min()) return false;
            if (range != null && range.max() != null && value > range.max()) return false;
        }
        return true;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static final class CollectionState {
        private final Integer dimension;
        private final Map<String, VectorRecord> records = new ConcurrentHashMap<>();

        private CollectionState(Integer dimension) {
            this.dimension = dimension;
        }
    }
}
