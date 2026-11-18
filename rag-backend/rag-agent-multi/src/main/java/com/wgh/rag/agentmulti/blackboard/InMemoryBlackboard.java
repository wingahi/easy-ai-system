package com.wgh.rag.agentmulti.blackboard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内黑板实现。
 *
 * <p>按 key 前缀隔离不同用途的条目（如 {@code card.} / {@code tool.}），
 * 容量上限内按写入顺序淘汰最旧条目，避免长任务无界增长。
 */
public class InMemoryBlackboard implements Blackboard {

    /** 默认容量 */
    public static final int DEFAULT_MAX_ENTRIES = 500;

    private final int maxEntries;
    private final Map<String, Object> entries = new ConcurrentHashMap<>();
    /** 写入顺序，仅用于淘汰（key 为业务 key，value 为时间序号） */
    private final Map<String, Long> writeSequence = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private long sequence;

    public InMemoryBlackboard() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public InMemoryBlackboard(int maxEntries) {
        this.maxEntries = Math.max(maxEntries, 1);
    }

    @Override
    public void put(String key, Object value) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            entries.put(key, value);
            writeSequence.put(key, ++sequence);
            evictIfNecessary();
        }
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public Map<String, Object> snapshot(String keyPrefix) {
        Map<String, Object> snap = new LinkedHashMap<>();
        entries.entrySet().stream()
                .filter(e -> keyPrefix == null || e.getKey().startsWith(keyPrefix))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> snap.put(e.getKey(), e.getValue()));
        return snap;
    }

    @Override
    public Map<String, Object> snapshot() {
        return snapshot(null);
    }

    @Override
    public void clear() {
        synchronized (lock) {
            entries.clear();
            writeSequence.clear();
        }
    }

    private void evictIfNecessary() {
        while (entries.size() > maxEntries) {
            String oldest = writeSequence.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            writeSequence.remove(oldest);
            entries.remove(oldest);
        }
    }
}
