package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.VectorStoreProperties;
import com.wgh.rag.kb.port.VectorStoreBackend;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.kb.port.vector.CollectionInfo;
import com.wgh.rag.kb.port.vector.ScanPage;
import com.wgh.rag.kb.port.vector.VectorFilter;
import com.wgh.rag.kb.port.vector.VectorMatch;
import com.wgh.rag.kb.port.vector.VectorRecord;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 向量后端路由：按 {@code wgh.vector.backend} 选择具体实现，所有业务只注入本 Bean。
 *
 * <p>默认选择 pgvector，行为与未引入路由前一致；可选 {@code fallback-backend} 在主后端
 * 抛异常时降级（默认关闭）。各后端必须实现同一 {@link VectorStorePort} 语义。</p>
 */
@Slf4j
@Primary
@Service
public class VectorStoreRouter implements VectorStorePort {

    private final Map<String, VectorStoreBackend> backends;
    private final VectorStoreBackend active;
    private final VectorStoreBackend fallback;
    private final boolean degradeOnError;

    public VectorStoreRouter(List<VectorStoreBackend> backends, VectorStoreProperties props) {
        Map<String, VectorStoreBackend> byName = new HashMap<>();
        for (VectorStoreBackend backend : backends) {
            byName.put(backend.backendName(), backend);
        }
        this.backends = Map.copyOf(byName);
        String configured = props.getBackend() == null || props.getBackend().isBlank()
                ? "pgvector" : props.getBackend().trim();
        this.active = byName.get(configured);
        if (this.active == null) {
            throw new IllegalStateException("未找到向量后端 wgh.vector.backend=" + configured
                    + "，可用后端: " + byName.keySet());
        }
        String fallbackName = props.getFallbackBackend();
        this.fallback = fallbackName == null || fallbackName.isBlank() || fallbackName.equals(configured)
                ? null : byName.get(fallbackName);
        this.degradeOnError = Boolean.TRUE.equals(props.getDegradeOnError());
        log.info("向量后端已选择: backend={}, fallback={}, degradeOnError={}",
                active.backendName(), fallback == null ? "-" : fallback.backendName(), degradeOnError);
    }

    /** 当前生效的后端名（管理端/测试观测用）。 */
    public String activeBackendName() {
        return active.backendName();
    }

    @Override
    public String backendName() {
        return active.backendName();
    }

    @Override
    public void ensureCollection(String collection, Integer dimension) {
        delegate(b -> {
            b.ensureCollection(collection, dimension);
            return null;
        });
    }

    @Override
    public void dropCollection(String collection) {
        delegate(b -> {
            b.dropCollection(collection);
            return null;
        });
    }

    @Override
    public void deleteByFileId(String collection, Long fileId) {
        delegate(b -> {
            b.deleteByFileId(collection, fileId);
            return null;
        });
    }

    @Override
    public void deleteByEmbeddingIds(String collection, Collection<String> embeddingIds) {
        delegate(b -> {
            b.deleteByEmbeddingIds(collection, embeddingIds);
            return null;
        });
    }

    @Override
    public List<String> insertVectors(String collection, List<TextSegment> segments,
                                      Long embeddingModelId, Integer dimension) {
        return delegate(b -> b.insertVectors(collection, segments, embeddingModelId, dimension));
    }

    @Override
    public Set<String> existingContentHashes(String collection) {
        return delegate(b -> b.existingContentHashes(collection));
    }

    @Override
    public int countVectors(String collection) {
        return delegate(b -> b.countVectors(collection));
    }

    @Override
    public List<VectorMatch> search(String collection, float[] queryVector, int topK,
                                    double minScore, VectorFilter filter) {
        return delegate(b -> b.search(collection, queryVector, topK, minScore, filter));
    }

    @Override
    public List<String> upsert(String collection, List<VectorRecord> records, int dimension) {
        return delegate(b -> b.upsert(collection, records, dimension));
    }

    @Override
    public ScanPage scan(String collection, String cursor, int batchSize) {
        return delegate(b -> b.scan(collection, cursor, batchSize));
    }

    @Override
    public CollectionInfo describe(String collection) {
        return delegate(b -> b.describe(collection));
    }

    private <T> T delegate(Function<VectorStoreBackend, T> action) {
        try {
            return action.apply(active);
        } catch (RuntimeException e) {
            if (degradeOnError && fallback != null) {
                log.warn("向量后端 {} 调用失败，降级到 {}: {}",
                        active.backendName(), fallback.backendName(), e.getMessage());
                return action.apply(fallback);
            }
            throw e;
        }
    }
}
