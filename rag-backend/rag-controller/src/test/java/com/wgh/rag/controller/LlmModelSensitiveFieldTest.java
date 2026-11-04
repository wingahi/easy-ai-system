package com.wgh.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.service.LlmModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P0-2 敏感字段泄露回归测试：
 * 1）模型列表/详情接口响应中不得出现 apiKey；
 * 2）模型读取接口必须声明 model:read 权限注解。
 */
@ExtendWith(MockitoExtension.class)
class LlmModelSensitiveFieldTest {

    @Mock
    private LlmModelService modelService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatModelListResponseMustNotLeakApiKey() throws Exception {
        LlmModel model = new LlmModel();
        model.setId(1L);
        model.setName("qwen-plus");
        model.setApiKey("sk-super-secret-key");
        when(modelService.listEnabledChatModels()).thenReturn(List.of(model));

        LlmModelController controller = new LlmModelController(modelService);
        Result<List<LlmModel>> result = controller.listEnabledChatModels();

        String json = objectMapper.writeValueAsString(result);
        assertThat(json).doesNotContain("apiKey");
        assertThat(json).doesNotContain("sk-super-secret-key");
        assertThat(json).contains("qwen-plus");
    }

    @Test
    void embeddingModelListResponseMustNotLeakApiKey() throws Exception {
        EmbeddingModelConfig config = new EmbeddingModelConfig();
        config.setId(1L);
        config.setName("text-embedding-v3");
        config.setApiKey("sk-embedding-secret");
        when(modelService.listEnabledEmbeddingModels()).thenReturn(List.of(config));

        LlmModelController controller = new LlmModelController(modelService);
        Result<List<EmbeddingModelConfig>> result = controller.listEnabledEmbeddingModels();

        String json = objectMapper.writeValueAsString(result);
        assertThat(json).doesNotContain("apiKey");
        assertThat(json).doesNotContain("sk-embedding-secret");
        assertThat(json).contains("text-embedding-v3");
    }

    @Test
    void modelReadEndpointsRequireModelReadPermission() throws Exception {
        assertPermission("pageChatModels", Long.class, Long.class);
        assertPermission("getChatModel", Long.class);
        assertPermission("listEnabledChatModels");
        assertPermission("pageEmbeddingModels", Long.class, Long.class);
        assertPermission("listEnabledEmbeddingModels");
    }

    private void assertPermission(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = LlmModelController.class.getMethod(methodName, parameterTypes);
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertThat(annotation).as("接口 %s 必须声明 @RequiresPermission", methodName).isNotNull();
        assertThat(annotation.value()).contains(AuthConstants.PERM_MODEL_READ);
    }
}
