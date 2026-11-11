package com.wgh.rag.llm.constant;

import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提供商能力目录测试：新增 provider 的能力标记、默认端点与错误路径。
 */
@DisplayName("模型提供商能力目录测试")
class ProviderCatalogTest {

    /** Azure / Ollama / 智谱三家新增提供商的能力标记正确。 */
    @Test
    @DisplayName("新增提供商 - 能力标记与默认值")
    void newProviders_capabilities() {
        ProviderCapability azure = ProviderCatalog.requireChat("AZURE_OPENAI");
        assertThat(azure.supportsStreaming()).isTrue();
        assertThat(azure.supportsEmbedding()).isTrue();
        assertThat(azure.requiresApiKey()).isTrue();
        assertThat(azure.defaultMaxContextTokens()).isEqualTo(128000);

        ProviderCapability ollama = ProviderCatalog.requireEmbedding("OLLAMA");
        assertThat(ollama.supportsChat()).isTrue();
        assertThat(ollama.supportsStreaming()).isTrue();
        assertThat(ollama.requiresApiKey()).isFalse();
        assertThat(ollama.defaultBaseUrl()).isEqualTo("http://localhost:11434/v1");
        assertThat(ollama.defaultMaxContextTokens()).isEqualTo(32768);

        ProviderCapability zhipu = ProviderCatalog.requireChat("ZHIPU");
        assertThat(zhipu.supportsEmbedding()).isTrue();
        assertThat(zhipu.defaultBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
    }

    /** 历史别名归一化：CUSTOM → CUSTOM_OPENAI，AZURE → AZURE_OPENAI，GLM → ZHIPU，大小写不敏感。 */
    @Test
    @DisplayName("normalize - 别名与大小写归一化")
    void normalize_aliases() {
        assertThat(ProviderCatalog.normalize("CUSTOM")).isEqualTo("CUSTOM_OPENAI");
        assertThat(ProviderCatalog.normalize(" azure ")).isEqualTo("AZURE_OPENAI");
        assertThat(ProviderCatalog.normalize("glm")).isEqualTo("ZHIPU");
        assertThat(ProviderCatalog.normalize("ollama")).isEqualTo("OLLAMA");
        assertThat(ProviderCatalog.normalize(null)).isNull();
    }

    /** 未知 provider 请求对话能力时抛出与工厂注册表一致的错误文案。 */
    @Test
    @DisplayName("requireChat - 未知 provider 抛 BusinessException")
    void requireChat_unknown() {
        assertThatThrownBy(() -> ProviderCatalog.requireChat("GOOGLE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的LLM提供商: GOOGLE");
    }

    /** 仅对话的 DEEPSEEK 请求嵌入能力时抛出嵌入专用错误文案。 */
    @Test
    @DisplayName("requireEmbedding - DEEPSEEK 不支持嵌入")
    void requireEmbedding_deepSeekUnsupported() {
        assertThatThrownBy(() -> ProviderCatalog.requireEmbedding("DEEPSEEK"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: DEEPSEEK");
    }

    /** 仅重排序的 CUSTOM_RERANK 不能用于对话。 */
    @Test
    @DisplayName("requireChat - 仅重排序 provider 不可用于对话")
    void requireChat_rerankOnly() {
        assertThatThrownBy(() -> ProviderCatalog.requireChat("CUSTOM_RERANK"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的LLM提供商: CUSTOM_RERANK");
    }

    /** 全部内置 provider 均可通过目录查询到。 */
    @Test
    @DisplayName("all - 覆盖全部内置 provider")
    void all_containsBuiltInProviders() {
        assertThat(ProviderCatalog.all())
                .containsKeys("OPENAI", "DASHSCOPE", "QWEN", "DEEPSEEK",
                        "AZURE_OPENAI", "OLLAMA", "ZHIPU", "CUSTOM_OPENAI", "CUSTOM_RERANK");
    }
}
