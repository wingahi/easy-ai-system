package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.KeywordSearchProperties;
import com.wgh.rag.kb.port.KeywordIndexMaintenance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 关键词索引维护的双路路由：删除/重建动作同时施加到主后端与对照后端（默认 pgtrgm）。
 *
 * <p>V1 只有 pgtrgm 实现，两路同名时按后端名去重，实际只执行一次；
 * 接入独立搜索引擎后，配置 {@code wgh.search.backend} 与 {@code wgh.search.compare-backend}
 * 即可让删除与重建同时作用于两套索引。</p>
 */
@Slf4j
@Service
public class KeywordIndexMaintenanceRouter {

    private final List<KeywordIndexMaintenance> paths;

    public KeywordIndexMaintenanceRouter(List<KeywordIndexMaintenance> implementations,
                                         KeywordSearchProperties props) {
        Map<String, KeywordIndexMaintenance> byName = new HashMap<>();
        for (KeywordIndexMaintenance impl : implementations) {
            byName.put(impl.backendName(), impl);
        }
        String activeName = props.getBackend() == null || props.getBackend().isBlank()
                ? "pgtrgm" : props.getBackend().trim();
        String secondaryName = props.getCompareBackend() == null || props.getCompareBackend().isBlank()
                ? "pgtrgm" : props.getCompareBackend().trim();

        List<KeywordIndexMaintenance> resolved = new ArrayList<>(2);
        KeywordIndexMaintenance active = byName.get(activeName);
        if (active == null) {
            log.warn("未找到关键词索引维护实现 backend={}，删除/重建将不维护关键词索引", activeName);
        } else {
            resolved.add(active);
        }
        KeywordIndexMaintenance secondary = byName.get(secondaryName);
        if (secondary == null) {
            log.debug("对照关键词索引维护实现 {} 未装配，仅维护主后端", secondaryName);
        } else if (!secondary.backendName().equals(activeName)) {
            resolved.add(secondary);
        }
        this.paths = List.copyOf(resolved);
        log.info("关键词索引维护路径: {}", paths.stream().map(KeywordIndexMaintenance::backendName).toList());
    }

    public void deleteByFileId(String collection, Long fileId) {
        applyAll(path -> path.deleteByFileId(collection, fileId), "deleteByFileId");
    }

    public void dropCollection(String collection) {
        applyAll(path -> path.dropCollection(collection), "dropCollection");
    }

    public void ensureIndex(String collection) {
        applyAll(path -> path.ensureIndex(collection), "ensureIndex");
    }

    private void applyAll(Consumer<KeywordIndexMaintenance> action, String operation) {
        for (KeywordIndexMaintenance path : paths) {
            try {
                action.accept(path);
            } catch (RuntimeException e) {
                log.warn("关键词索引维护失败 op={}, backend={}: {}", operation, path.backendName(), e.getMessage());
            }
        }
    }
}
