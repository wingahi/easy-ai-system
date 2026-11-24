package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.entity.AgentMemory;
import com.wgh.rag.kb.mapper.AgentMemoryMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 长期记忆清理器：按年龄（TTL）与单作用域条数淘汰记忆，并同步删除对应向量。
 *
 * <p>单飞保障：
 * <ol>
 *   <li>进程内用 {@link AtomicBoolean} 串行化，避免同一实例并发执行；</li>
 *   <li>跨实例使用 Postgres 事务级咨询锁 {@code pg_try_advisory_xact_lock}，
 *       拿不到锁直接跳过本轮；非 Postgres 数据源自动降级为仅进程内单飞；</li>
 *   <li>删除本身幂等，即使多实例同时执行也只会删除已过期的同一批行。</li>
 * </ol>
 *
 * <p>配置全部来自 {@link RagProperties.Memory} 的代码默认值，不依赖 application.yml。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMemoryCleaner {

    /** 咨询锁 key（常量，避免与其他业务锁冲突） */
    private static final long ADVISORY_LOCK_KEY = 0x4D454D31L; // "MEM1"

    private final AgentMemoryMapper memoryMapper;
    private final VectorStorePort vectorStoreService;
    private final RagProperties ragProperties;
    private final PlatformTransactionManager transactionManager;

    @Autowired(required = false)
    private RagMetrics metrics;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean advisoryLockSupported = true;

    /** 调度中心开启时由 {@code KB_AGENT_MEMORY_CLEANUP} 周期作业接管，本地定时器不启动。 */
    @Value("${wgh.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    void start() {
        RagProperties.Memory cfg = memoryConfig();
        if (!Boolean.TRUE.equals(cfg.getCleanupEnabled())) {
            log.info("长期记忆清理未启用（wgh.rag.memory.cleanup-enabled=false）");
            return;
        }
        if (schedulerEnabled) {
            log.info("长期记忆清理已交由调度中心（KB_AGENT_MEMORY_CLEANUP），本地定时器不启动");
            return;
        }
        int interval = Math.max(5, cfg.getCleanupIntervalMinutes() != null
                ? cfg.getCleanupIntervalMinutes() : 60);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("agent-memory-cleaner-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::cleanupQuietly,
                Instant.now().plus(Duration.ofMinutes(interval)),
                Duration.ofMinutes(interval));
        log.info("长期记忆清理已启用：保留 {} 天，单作用域上限 {} 条，间隔 {} 分钟",
                Math.max(1, cfg.getRetentionDays() != null ? cfg.getRetentionDays() : 30),
                Math.max(1, cfg.getMaxEntriesPerScope() != null ? cfg.getMaxEntriesPerScope() : 200),
                interval);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 执行一轮清理，返回删除条数。任何异常只记日志并返回 0。
     */
    @Transactional
    public int cleanupExpired() {
        RagProperties.Memory cfg = memoryConfig();
        if (!Boolean.TRUE.equals(cfg.getEnabled()) || !Boolean.TRUE.equals(cfg.getCleanupEnabled())) {
            return 0;
        }
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            if (!advisoryLockSupported) {
                return doCleanup();
            }
            try {
                Integer deleted = new TransactionTemplate(transactionManager).execute(status -> {
                    Boolean locked = memoryMapper.tryAdvisoryXactLock(ADVISORY_LOCK_KEY);
                    if (!Boolean.TRUE.equals(locked)) {
                        return 0;
                    }
                    return doCleanup();
                });
                return deleted != null ? deleted : 0;
            } catch (Exception e) {
                advisoryLockSupported = false;
                log.info("当前数据源不支持 PG 事务级咨询锁，降级为进程内单飞: {}", e.getMessage());
                return doCleanup();
            }
        } catch (Exception e) {
            log.warn("长期记忆清理失败: reason={}", e.getMessage());
            return 0;
        } finally {
            running.set(false);
        }
    }

    private int doCleanup() {
        RagProperties.Memory cfg = memoryConfig();
        int byAge = cleanupByAge(cfg);
        int byCapacity = cleanupByCapacity(cfg);
        if (metrics != null) {
            metrics.recordMemoryCleanupDeleted("age", byAge);
            metrics.recordMemoryCleanupDeleted("capacity", byCapacity);
        }
        int total = byAge + byCapacity;
        if (total > 0) {
            log.info("✓ 长期记忆清理完成: age={}, capacity={}, total={}", byAge, byCapacity, total);
        }
        return total;
    }

    /** 按年龄清理：删除早于保留期的记忆（单批上限）。 */
    private int cleanupByAge(RagProperties.Memory cfg) {
        Integer days = cfg.getRetentionDays();
        if (days == null || days <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<AgentMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .lt(AgentMemory::getCreateTime, cutoff)
                .orderByAsc(AgentMemory::getCreateTime)
                .last("LIMIT " + batchSize(cfg)));
        return deleteRows(rows);
    }

    /** 按条数清理：每个作用域保留最新 N 条，删除更旧的部分。 */
    private int cleanupByCapacity(RagProperties.Memory cfg) {
        Integer max = cfg.getMaxEntriesPerScope();
        if (max == null || max <= 0) {
            return 0;
        }
        int batch = batchSize(cfg);
        List<Map<String, Object>> groups = memoryMapper.selectScopeGroups(batch);
        if (groups == null || groups.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Map<String, Object> group : groups) {
            String scopeType = stringValue(group, "scopeType");
            String scopeId = stringValue(group, "scopeId");
            if (scopeType == null || scopeId == null) {
                continue;
            }
            try {
                Long total = memoryMapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
                        .eq(AgentMemory::getScopeType, scopeType)
                        .eq(AgentMemory::getScopeId, scopeId));
                long overflow = (total != null ? total : 0) - max;
                if (overflow <= 0) {
                    continue;
                }
                List<AgentMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                        .eq(AgentMemory::getScopeType, scopeType)
                        .eq(AgentMemory::getScopeId, scopeId)
                        .orderByAsc(AgentMemory::getCreateTime)
                        .orderByAsc(AgentMemory::getId)
                        .last("LIMIT " + Math.min(overflow, batch)));
                deleted += deleteRows(rows);
            } catch (Exception e) {
                log.warn("长期记忆容量清理失败: scope={}:{}, reason={}", scopeType, scopeId, e.getMessage());
            }
        }
        return deleted;
    }

    /** 物理删除记忆行并清理对应向量；向量删除失败不影响行删除。 */
    private int deleteRows(List<AgentMemory> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<String> embeddingIds = new ArrayList<>();
        int deleted = 0;
        for (AgentMemory row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            try {
                deleted += memoryMapper.physicalDeleteById(row.getId());
                if (row.getEmbeddingId() != null && !row.getEmbeddingId().isBlank()) {
                    embeddingIds.add(row.getEmbeddingId());
                }
            } catch (Exception e) {
                log.warn("长期记忆行删除失败: id={}, reason={}", row.getId(), e.getMessage());
            }
        }
        if (!embeddingIds.isEmpty()) {
            try {
                vectorStoreService.deleteByEmbeddingIds(AgentMemoryService.VEC_TABLE, embeddingIds);
            } catch (Exception e) {
                log.warn("长期记忆向量删除失败（行已删除）: count={}, reason={}",
                        embeddingIds.size(), e.getMessage());
            }
        }
        return deleted;
    }

    private void cleanupQuietly() {
        try {
            cleanupExpired();
        } catch (Exception e) {
            log.warn("长期记忆清理任务异常: {}", e.getMessage());
        }
    }

    private int batchSize(RagProperties.Memory cfg) {
        Integer batch = cfg.getCleanupBatchSize();
        return batch != null && batch > 0 ? batch : 200;
    }

    private static String stringValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() != null ? String.valueOf(entry.getValue()) : null;
            }
        }
        return null;
    }

    private RagProperties.Memory memoryConfig() {
        RagProperties.Memory cfg = ragProperties.getMemory();
        return cfg != null ? cfg : new RagProperties.Memory();
    }
}
