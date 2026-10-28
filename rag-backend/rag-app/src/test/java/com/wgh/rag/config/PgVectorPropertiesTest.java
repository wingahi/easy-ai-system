package com.wgh.rag.config;

import com.wgh.rag.config.property.PgVectorProperties;
import com.wgh.rag.config.property.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgVectorProperties / RagProperties 配置属性测试
 */
@DisplayName("PgVectorProperties 配置测试")
class PgVectorPropertiesTest {

    /** 验证 PgVectorProperties 各配置字段的默认值符合预期。 */
    @Test
    @DisplayName("PgVectorProperties 默认值")
    void pgVectorProperties_defaultValues() {
        PgVectorProperties props = new PgVectorProperties();
        assertThat(props.getDefaultDimension()).isEqualTo(1536);
        assertThat(props.getCreateTable()).isTrue();
        assertThat(props.getSkipCreateVectorExtension()).isFalse();
        assertThat(props.getUseIndex()).isFalse();
        assertThat(props.getIndexListSize()).isEqualTo(100);
        assertThat(props.getDefaultTableName()).isEqualTo("rag_vector_store");
        assertThat(props.getConnectOnStartup()).isTrue();
        assertThat(props.getAllowFallbackToInMemory()).isFalse();
    }

    /** 验证 setter 能正确覆盖 PgVectorProperties 的默认值。 */
    @Test
    @DisplayName("PgVectorProperties setter 覆盖默认值")
    void pgVectorProperties_override() {
        PgVectorProperties props = new PgVectorProperties();
        props.setDefaultDimension(768);
        props.setCreateTable(true);
        props.setSkipCreateVectorExtension(true);
        props.setUseIndex(true);
        props.setIndexListSize(500);
        props.setDefaultTableName("kb_vectors");
        props.setConnectOnStartup(false);
        props.setAllowFallbackToInMemory(true);

        assertThat(props.getDefaultDimension()).isEqualTo(768);
        assertThat(props.getSkipCreateVectorExtension()).isTrue();
        assertThat(props.getUseIndex()).isTrue();
        assertThat(props.getIndexListSize()).isEqualTo(500);
        assertThat(props.getDefaultTableName()).isEqualTo("kb_vectors");
        assertThat(props.getConnectOnStartup()).isFalse();
        assertThat(props.getAllowFallbackToInMemory()).isTrue();
    }

    /** 验证 RagProperties 的 retrieve、split、context 嵌套属性可正确设置与读取。 */
    @Test
    @DisplayName("RagProperties 嵌套属性 - retrieve / split / context")
    void ragProperties_nested() {
        RagProperties props = new RagProperties();
        props.getRetrieve().setTopK(10);
        props.getRetrieve().setMinScore(0.5);
        props.getRetrieve().setEnableRerank(true);
        props.getSplit().setMaxLength(1000);
        props.getSplit().setOverlapLength(100);
        props.getSplit().setDefaultStrategy("CHAPTER");
        props.getContext().setMaxHistoryMessages(30);
        props.getContext().setMaxHistoryTokens(8000);
        props.getContext().setEnableCompression(true);

        assertThat(props.getRetrieve().getTopK()).isEqualTo(10);
        assertThat(props.getRetrieve().getMinScore()).isEqualTo(0.5);
        assertThat(props.getRetrieve().getEnableRerank()).isTrue();
        assertThat(props.getSplit().getMaxLength()).isEqualTo(1000);
        assertThat(props.getSplit().getOverlapLength()).isEqualTo(100);
        assertThat(props.getSplit().getDefaultStrategy()).isEqualTo("CHAPTER");
        assertThat(props.getContext().getMaxHistoryMessages()).isEqualTo(30);
        assertThat(props.getContext().getMaxHistoryTokens()).isEqualTo(8000);
        assertThat(props.getContext().getEnableCompression()).isTrue();
    }
}
