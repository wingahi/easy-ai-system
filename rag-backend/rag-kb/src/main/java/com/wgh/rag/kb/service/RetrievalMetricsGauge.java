package com.wgh.rag.kb.service;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 注册检索相关 Gauge。
 *
 * <p>向量表数量来自 `wgh_kb_vector_table_count()` 计数函数（SECURITY DEFINER，跨租户只返回 count），
 * 用 60 秒 TTL 缓存，避免 Prometheus 每次抓取都查库；查询失败时沿用上次值。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalMetricsGauge {

    private static final long REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final RagMetrics metrics;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final AtomicLong cachedTableCount = new AtomicLong(0);
    private final AtomicLong lastRefreshNanos = new AtomicLong(0);

    @PostConstruct
    void register() {
        metrics.registerVectorTableCount(this::tableCount);
    }

    private long tableCount() {
        long now = System.nanoTime();
        long last = lastRefreshNanos.get();
        if (now - last >= REFRESH_INTERVAL_NANOS && lastRefreshNanos.compareAndSet(last, now)) {
            try {
                // 跨租户口径由数据库函数保证：应用侧没有租户上下文，直查会被 RLS 过滤为 0
                Long count = knowledgeBaseMapper.selectVectorTableCountCrossTenant();
                cachedTableCount.set(count == null ? 0 : count);
            } catch (RuntimeException e) {
                log.warn("统计向量表数量失败，沿用上次值 {}: {}", cachedTableCount.get(), e.getMessage());
            }
        }
        return cachedTableCount.get();
    }
}
