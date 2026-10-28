package com.wgh.rag.llm.factory;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatModelFactoryRegistry 路由测试：provider 大小写不敏感、未知 provider 报错与原 switch default 分支一致。
 */
@DisplayName("模型工厂注册表路由测试")
class ChatModelFactoryRegistryTest {

    static ChatModelFactoryRegistry defaultRegistry() {
        return new ChatModelFactoryRegistry(List.of(
                new OpenAiModelFactory(),
                new DeepSeekModelFactory(),
                new CustomOpenAiModelFactory(),
                new DashScopeModelFactory(),
                new QwenModelFactory()));
    }

    private final ChatModelFactoryRegistry registry = defaultRegistry();

    @Test
    @DisplayName("get - 各 provider 路由到对应工厂")
    void get_routesToProviderFactory() {
        assertThat(registry.get("OPENAI")).isInstanceOf(OpenAiModelFactory.class);
        assertThat(registry.get("DEEPSEEK")).isInstanceOf(DeepSeekModelFactory.class);
        assertThat(registry.get("CUSTOM_OPENAI")).isInstanceOf(CustomOpenAiModelFactory.class);
        assertThat(registry.get("DASHSCOPE")).isInstanceOf(DashScopeModelFactory.class);
        assertThat(registry.get("QWEN")).isInstanceOf(QwenModelFactory.class);
    }

    @Test
    @DisplayName("get - provider 大小写不敏感")
    void get_caseInsensitive() {
        assertThat(registry.get("openai")).isInstanceOf(OpenAiModelFactory.class);
        assertThat(registry.get("DashScope")).isInstanceOf(DashScopeModelFactory.class);
        assertThat(registry.get("qwen")).isInstanceOf(QwenModelFactory.class);
    }

    @Test
    @DisplayName("get - 未知 provider 抛 BusinessException（消息同原逻辑）")
    void get_unknownProvider() {
        assertThatThrownBy(() -> registry.get("GOOGLE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的LLM提供商: GOOGLE");
    }

    @Test
    @DisplayName("getEmbeddingFactory - 未知 provider 抛 BusinessException（嵌入专用消息）")
    void getEmbeddingFactory_unknownProvider() {
        assertThatThrownBy(() -> registry.getEmbeddingFactory("XYZ"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: XYZ");
    }

    @Test
    @DisplayName("getEmbeddingFactory - DEEPSEEK 有工厂但 buildEmbedding 抛不支持异常")
    void getEmbeddingFactory_deepSeekEmbeddingUnsupported() {
        ChatModelFactory factory = registry.getEmbeddingFactory("DEEPSEEK");
        assertThat(factory).isInstanceOf(DeepSeekModelFactory.class);

        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setProvider("DEEPSEEK");
        config.setModelName("deepseek-embedding");
        assertThatThrownBy(() -> factory.buildEmbedding(config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的嵌入模型提供商: DEEPSEEK");
    }

    @Test
    @DisplayName("get - provider 为 null 抛 NullPointerException（与原 toUpperCase 行为一致）")
    void get_nullProvider() {
        assertThatThrownBy(() -> registry.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.getEmbeddingFactory(null)).isInstanceOf(NullPointerException.class);
    }
}
