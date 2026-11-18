package com.wgh.rag.llm.service;

import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型能力画像与切链降级测试。
 */
@DisplayName("模型能力降级")
class ModelRouterCapabilityTest {

    private final LlmModelMapper mapper = Mockito.mock(LlmModelMapper.class);
    private final ModelRouter router = new ModelRouter(mapper);

    private static LlmModel model(long id, int tier, boolean supportsTools) {
        LlmModel m = new LlmModel();
        m.setId(id);
        m.setModelName("m" + id);
        m.setCapabilityTier(tier);
        m.setSupportsTools(supportsTools);
        return m;
    }

    @Test
    @DisplayName("只降不升 - 排除高于主模型的候选")
    void neverUpgrades() {
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(List.of(
                model(1, 3, true), model(2, 5, true), model(3, 2, true)));

        List<LlmModel> chain = router.resolveChain("g", 1L);

        assertThat(chain).extracting(LlmModel::getId).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("需要工具 - 排除不支持 function calling 的候选")
    void filtersToolCapability() {
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(List.of(
                model(1, 3, true), model(2, 2, false), model(3, 1, true)));

        List<LlmModel> chain = router.resolveChain("g", 1L, true);

        assertThat(chain).extracting(LlmModel::getId).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("主模型始终保留 - 即使能力画像不满足")
    void keepsPrimary() {
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(List.of(
                model(1, 3, false), model(2, 2, true)));

        List<LlmModel> chain = router.resolveChain("g", 1L, true);

        assertThat(chain).extracting(LlmModel::getId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("能力字段缺失 - 按默认档位 3 处理")
    void defaultsMissingTier() {
        LlmModel noTier = new LlmModel();
        noTier.setId(2L);
        noTier.setSupportsTools(true);
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(List.of(
                model(1, 3, true), noTier, model(3, 4, true)));

        List<LlmModel> chain = router.resolveChain("g", 1L);

        assertThat(chain).extracting(LlmModel::getId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("主模型不满足工具要求 - 仍保留，仅排除其他不合格候选")
    void keepsPrimaryEvenIfNotToolCapable() {
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(List.of(
                model(1, 5, false), model(2, 5, false)));

        List<LlmModel> chain = router.resolveChain("g", 1L, true);

        assertThat(chain).extracting(LlmModel::getId).containsExactly(1L);
    }
}
