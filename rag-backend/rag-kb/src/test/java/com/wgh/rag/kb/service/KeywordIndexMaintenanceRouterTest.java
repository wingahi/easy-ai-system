package com.wgh.rag.kb.service;

import com.wgh.rag.config.property.KeywordSearchProperties;
import com.wgh.rag.kb.port.KeywordIndexMaintenance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词索引双路维护单测：删除/重建同时作用于主后端与对照后端，同名去重
 */
class KeywordIndexMaintenanceRouterTest {

    private static final class FakeMaintenance implements KeywordIndexMaintenance {
        private final String name;
        private final AtomicInteger deletes = new AtomicInteger();
        private final AtomicInteger drops = new AtomicInteger();
        private final AtomicInteger ensures = new AtomicInteger();

        private FakeMaintenance(String name) {
            this.name = name;
        }

        @Override
        public String backendName() {
            return name;
        }

        @Override
        public void deleteByFileId(String collection, Long fileId) {
            deletes.incrementAndGet();
        }

        @Override
        public void dropCollection(String collection) {
            drops.incrementAndGet();
        }

        @Override
        public void ensureIndex(String collection) {
            ensures.incrementAndGet();
        }
    }

    @Test
    void applies_to_both_paths_when_backends_differ() {
        KeywordSearchProperties props = new KeywordSearchProperties();
        props.setBackend("a");
        props.setCompareBackend("b");
        FakeMaintenance active = new FakeMaintenance("a");
        FakeMaintenance compare = new FakeMaintenance("b");
        KeywordIndexMaintenanceRouter router = new KeywordIndexMaintenanceRouter(
                new ArrayList<>(List.of(active, compare)), props);

        router.deleteByFileId("c", 1L);
        router.dropCollection("c");
        router.ensureIndex("c");

        assertThat(active.deletes.get()).isEqualTo(1);
        assertThat(compare.deletes.get()).isEqualTo(1);
        assertThat(active.drops.get()).isEqualTo(1);
        assertThat(compare.drops.get()).isEqualTo(1);
        assertThat(active.ensures.get()).isEqualTo(1);
        assertThat(compare.ensures.get()).isEqualTo(1);
    }

    @Test
    void deduplicates_when_both_paths_resolve_to_same_backend() {
        KeywordSearchProperties props = new KeywordSearchProperties();
        props.setBackend("pgtrgm");
        props.setCompareBackend("pgtrgm");
        FakeMaintenance pgtrgm = new FakeMaintenance("pgtrgm");
        KeywordIndexMaintenanceRouter router = new KeywordIndexMaintenanceRouter(
                new ArrayList<>(List.of(pgtrgm)), props);

        router.deleteByFileId("c", 1L);
        router.dropCollection("c");

        assertThat(pgtrgm.deletes.get()).isEqualTo(1);
        assertThat(pgtrgm.drops.get()).isEqualTo(1);
    }
}
