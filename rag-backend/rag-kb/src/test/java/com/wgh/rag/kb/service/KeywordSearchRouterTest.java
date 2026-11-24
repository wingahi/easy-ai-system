package com.wgh.rag.kb.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.KeywordSearchProperties;
import com.wgh.rag.kb.port.KeywordFilter;
import com.wgh.rag.kb.port.KeywordHit;
import com.wgh.rag.kb.port.KeywordSearchBackend;
import com.wgh.rag.kb.port.vector.VectorScoreType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词路由单测：默认行为、按 KB 灰度、双读对比不改变返回、异常降级
 */
class KeywordSearchRouterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RagMetrics metrics = new RagMetrics(registry);

    private static KeywordHit hit(String id) {
        return new KeywordHit(id, 0.6, VectorScoreType.KEYWORD, "text-" + id, java.util.Map.of(), List.of());
    }

    /** 记录调用次数并返回固定结果的测试后端。 */
    private static final class FakeBackend implements KeywordSearchBackend {
        private final String name;
        private final List<KeywordHit> hits;
        private final boolean fail;
        private final AtomicInteger invocations = new AtomicInteger();

        private FakeBackend(String name, List<KeywordHit> hits, boolean fail) {
            this.name = name;
            this.hits = hits;
            this.fail = fail;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public List<KeywordHit> search(String collection, String query, int topN, KeywordFilter filter) {
            invocations.incrementAndGet();
            if (fail) throw new IllegalStateException(name + " unavailable");
            return hits;
        }
    }

    private KeywordSearchRouter router(KeywordSearchProperties props, FakeBackend... backends) {
        List<KeywordSearchBackend> implementations = new ArrayList<>(List.of(backends));
        return new KeywordSearchRouter(implementations, props, metrics);
    }

    private static KeywordSearchProperties baseProps() {
        KeywordSearchProperties props = new KeywordSearchProperties();
        props.setBackend("a");
        props.setFallbackBackend("b");
        props.setCompareBackend("b");
        return props;
    }

    @Test
    void default_returns_active_backend_results() {
        FakeBackend active = new FakeBackend("a", List.of(hit("x")), false);
        FakeBackend fallback = new FakeBackend("b", List.of(hit("y")), false);

        List<KeywordHit> result = router(baseProps(), active, fallback).search("c", "q", 5, null, 1L);

        assertThat(result).extracting(KeywordHit::id).containsExactly("x");
        assertThat(fallback.invocations.get()).isZero();
    }

    @Test
    void gray_kb_whitelist_routes_to_gray_backend() {
        KeywordSearchProperties props = baseProps();
        props.setGrayBackend("b");
        props.setGrayKbIds(List.of(7L));
        FakeBackend active = new FakeBackend("a", List.of(hit("x")), false);
        FakeBackend gray = new FakeBackend("b", List.of(hit("y")), false);
        KeywordSearchRouter router = router(props, active, gray);

        assertThat(router.search("c", "q", 5, null, 7L)).extracting(KeywordHit::id).containsExactly("y");
        assertThat(router.search("c", "q", 5, null, 8L)).extracting(KeywordHit::id).containsExactly("x");
    }

    @Test
    void compare_runs_both_backends_but_returns_primary() {
        KeywordSearchProperties props = baseProps();
        props.setCompareEnabled(true);
        FakeBackend active = new FakeBackend("a", List.of(hit("x")), false);
        FakeBackend compare = new FakeBackend("b", List.of(hit("y")), false);

        List<KeywordHit> result = router(props, active, compare).search("c", "q", 5, null, 1L);

        assertThat(result).extracting(KeywordHit::id).containsExactly("x");
        assertThat(compare.invocations.get()).isEqualTo(1);
        assertThat(registry.get(RagMetrics.RETRIEVAL_COMPARE_QUERIES)
                .tags("active", "a", "compare", "b").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(RagMetrics.RETRIEVAL_COMPARE_MISMATCHES)
                .tags("active", "a", "compare", "b", "kind", "top1").counter().count()).isEqualTo(1.0);
    }

    @Test
    void compare_failure_does_not_affect_return() {
        KeywordSearchProperties props = baseProps();
        props.setCompareEnabled(true);
        FakeBackend active = new FakeBackend("a", List.of(hit("x")), false);
        FakeBackend compare = new FakeBackend("b", List.of(), true);

        List<KeywordHit> result = router(props, active, compare).search("c", "q", 5, null, 1L);

        assertThat(result).extracting(KeywordHit::id).containsExactly("x");
    }

    @Test
    void active_failure_degrades_to_fallback_and_records_metric() {
        FakeBackend active = new FakeBackend("a", List.of(), true);
        FakeBackend fallback = new FakeBackend("b", List.of(hit("y")), false);

        List<KeywordHit> result = router(baseProps(), active, fallback).search("c", "q", 5, null, 1L);

        assertThat(result).extracting(KeywordHit::id).containsExactly("y");
        assertThat(registry.get(RagMetrics.RETRIEVAL_DEGRADED)
                .tag("reason", "keyword-fallback").counter().count()).isEqualTo(1.0);
    }
}
