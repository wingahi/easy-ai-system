package com.wgh.rag.kb.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.KeywordSearchProperties;
import com.wgh.rag.kb.port.KeywordFilter;
import com.wgh.rag.kb.port.KeywordHit;
import com.wgh.rag.kb.port.KeywordSearchBackend;
import com.wgh.rag.kb.port.KeywordSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词后端路由：按 {@code wgh.search.backend} 选择具体实现。
 *
 * <p>默认 pgtrgm，行为与现有混合检索关键词路一致；关键词路失败时可降级到
 * {@code fallback-backend}（默认 pgtrgm），保证聊天主链路不因搜索引擎不可用而中断。</p>
 *
 * <p>V1 能力（全部默认关闭，不改变返回结果）：</p>
 * <ul>
 *   <li>按 KB 白名单灰度：{@code wgh.search.gray-kb-ids} 中的知识库走 {@code gray-backend}</li>
 *   <li>双读对比：{@code wgh.search.compare-enabled=true} 时同一查询在主后端与对照后端都执行，
 *       只记录差异指标与日志</li>
 * </ul>
 */
@Slf4j
@Primary
@Service
public class KeywordSearchRouter implements KeywordSearchPort {

    private final KeywordSearchBackend active;
    private final KeywordSearchBackend fallback;
    private final KeywordSearchBackend gray;
    private final KeywordSearchBackend compare;
    private final Set<Long> grayKbIds;
    private final boolean degradeOnError;
    private final boolean compareEnabled;
    private final RagMetrics metrics;

    public KeywordSearchRouter(List<KeywordSearchBackend> backends, KeywordSearchProperties props,
                               RagMetrics metrics) {
        Map<String, KeywordSearchBackend> byName = new HashMap<>();
        for (KeywordSearchBackend backend : backends) {
            byName.put(backend.backendName(), backend);
        }
        String configured = props.getBackend() == null || props.getBackend().isBlank()
                ? "pgtrgm" : props.getBackend().trim();
        this.active = byName.get(configured);
        if (this.active == null) {
            throw new IllegalStateException("未找到关键词后端 wgh.search.backend=" + configured
                    + "，可用后端: " + byName.keySet());
        }
        String fallbackName = props.getFallbackBackend();
        this.fallback = fallbackName == null || fallbackName.isBlank() || fallbackName.equals(configured)
                ? null : byName.get(fallbackName);

        String grayName = props.getGrayBackend() == null ? "" : props.getGrayBackend().trim();
        List<Long> configuredGrayKbIds = props.getGrayKbIds() == null ? List.of() : props.getGrayKbIds();
        boolean grayConfigured = !grayName.isEmpty() && !configuredGrayKbIds.isEmpty();
        KeywordSearchBackend grayBackend = grayConfigured ? byName.get(grayName) : null;
        if (grayConfigured && grayBackend == null) {
            log.warn("灰度后端 {} 未实现，灰度白名单暂不生效（可用后端: {}）", grayName, byName.keySet());
        }
        this.gray = grayBackend;
        this.grayKbIds = grayBackend == null ? Set.of() : new HashSet<>(configuredGrayKbIds);

        String compareName = props.getCompareBackend() == null ? "" : props.getCompareBackend().trim();
        this.compare = Boolean.TRUE.equals(props.getCompareEnabled()) && !compareName.isEmpty()
                ? byName.get(compareName) : null;
        if (Boolean.TRUE.equals(props.getCompareEnabled()) && this.compare == null && !compareName.isEmpty()) {
            log.warn("双读对照后端 {} 未实现，双读对比关闭（可用后端: {}）", compareName, byName.keySet());
        }
        this.compareEnabled = Boolean.TRUE.equals(props.getCompareEnabled());
        this.degradeOnError = Boolean.TRUE.equals(props.getDegradeOnError());
        this.metrics = metrics;
        log.info("关键词后端已选择: backend={}, fallback={}, gray={}, grayKbCount={}, compare={}, degradeOnError={}",
                active.backendName(), fallback == null ? "-" : fallback.backendName(),
                gray == null ? "-" : gray.backendName(), grayKbIds.size(),
                compare == null ? "-" : compare.backendName(), degradeOnError);
    }

    @Override
    public String backendName() {
        return active.backendName();
    }

    @Override
    public List<KeywordHit> search(String collection, String query, int topN, KeywordFilter filter) {
        return search(collection, query, topN, filter, null);
    }

    @Override
    public List<KeywordHit> search(String collection, String query, int topN,
                                   KeywordFilter filter, Long kbId) {
        KeywordSearchBackend chosen = select(kbId);
        List<KeywordHit> result = invoke(chosen, collection, query, topN, filter);
        metrics.recordKeywordHit(chosen.backendName(), !result.isEmpty());
        compareIfEnabled(chosen, collection, query, topN, filter, result);
        return result;
    }

    /** 灰度命中的 KB 走 gray 后端，其余走主后端。 */
    private KeywordSearchBackend select(Long kbId) {
        if (gray != null && kbId != null && grayKbIds.contains(kbId)) {
            return gray;
        }
        return active;
    }

    private List<KeywordHit> invoke(KeywordSearchBackend backend, String collection, String query,
                                    int topN, KeywordFilter filter) {
        try {
            return backend.search(collection, query, topN, filter);
        } catch (RuntimeException e) {
            if (degradeOnError && fallback != null && fallback != backend) {
                log.warn("关键词后端 {} 调用失败，降级到 {}: {}",
                        backend.backendName(), fallback.backendName(), e.getMessage());
                metrics.recordRetrievalDegraded("keyword-fallback");
                return fallback.search(collection, query, topN, filter);
            }
            throw e;
        }
    }

    /**
     * 双读对比：对照后端异常不影响主链路，只记 WARN。
     * 与主后端同名时不重复执行（没有对比价值）。
     */
    private void compareIfEnabled(KeywordSearchBackend chosen, String collection, String query,
                                  int topN, KeywordFilter filter, List<KeywordHit> primary) {
        if (!compareEnabled || compare == null || compare == chosen) return;
        List<KeywordHit> other;
        try {
            other = compare.search(collection, query, topN, filter);
        } catch (RuntimeException e) {
            log.warn("双读对比后端 {} 调用失败（不影响返回）: {}", compare.backendName(), e.getMessage());
            return;
        }
        boolean sameTop1 = sameTop1(primary, other);
        boolean sameSet = sameSet(primary, other);
        metrics.recordRetrievalCompare(chosen.backendName(), compare.backendName(), sameTop1, sameSet);
        if (!sameSet) {
            log.info("双读差异: active={}, compare={}, query={}, top1Same={}, primary={}, compareHits={}",
                    chosen.backendName(), compare.backendName(), query, sameTop1,
                    idsOf(primary), idsOf(other));
        }
    }

    private static boolean sameTop1(List<KeywordHit> a, List<KeywordHit> b) {
        return keyOf(a.isEmpty() ? null : a.get(0)).equals(keyOf(b.isEmpty() ? null : b.get(0)));
    }

    private static boolean sameSet(List<KeywordHit> a, List<KeywordHit> b) {
        Set<String> left = new HashSet<>(idsOf(a));
        Set<String> right = new HashSet<>(idsOf(b));
        return left.equals(right);
    }

    private static List<String> idsOf(List<KeywordHit> hits) {
        return hits.stream().map(KeywordSearchRouter::keyOf).toList();
    }

    private static String keyOf(KeywordHit hit) {
        if (hit == null) return "null";
        if (hit.id() != null) return hit.id();
        return hit.text() != null ? "text:" + hit.text() : "null";
    }
}
