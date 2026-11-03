package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.dto.PromptTemplateSuggestion;
import com.wgh.rag.chat.entity.PromptTemplate;
import com.wgh.rag.chat.mapper.PromptTemplateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PromptTemplateService.suggest 单测：模糊匹配条件、limit 归一化、空结果、变量解析
 */
class PromptTemplateSuggestTest {

    private final PromptTemplateMapper mapper = Mockito.mock(PromptTemplateMapper.class);
    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        // 单测无 Spring/MyBatis 上下文，Lambda 字段名解析需要先注册实体元信息，否则 getSqlSegment 抛 "can not find lambda cache"
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PromptTemplate.class);
        service = new PromptTemplateService(mapper, new ObjectMapper());
    }

    private PromptTemplate tpl(long id, String name, String content, String variables, String remark) {
        PromptTemplate t = new PromptTemplate();
        t.setId(id);
        t.setName(name);
        t.setContent(content);
        t.setVariables(variables);
        t.setRemark(remark);
        t.setCategory("客服");
        t.setVersion(2);
        return t;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPage(List<PromptTemplate> records) {
        Page<PromptTemplate> page = new Page<>(1, records.size());
        page.setRecords(records);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void suggest_filters_by_name_remark_and_category() {
        stubPage(List.of(tpl(1L, "客服专员", "你好{{userName}}", "[\"userName\"]", "客服话术")));

        List<PromptTemplateSuggestion> result = service.suggest("客服", 10);

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("name").contains("remark").contains("category").containsIgnoringCase("like");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("客服专员");
        assertThat(result.get(0).description()).isEqualTo("客服话术");
        assertThat(result.get(0).variables()).containsExactly("userName");
        assertThat(result.get(0).version()).isEqualTo(2);
        assertThat(result.get(0).content()).isEqualTo("你好{{userName}}");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void suggest_without_query_skips_keyword_condition() {
        stubPage(List.of(tpl(1L, "总结助手", "总结{{text}}", "[\"text\"]", null)));

        service.suggest("   ", 5);

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContainIgnoringCase("like");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void suggest_clamps_limit_to_max_50() {
        stubPage(List.of());

        service.suggest("q", 999);

        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(captor.capture(), any(LambdaQueryWrapper.class));
        assertThat(captor.getValue().getSize()).isEqualTo(PromptTemplateService.SUGGEST_MAX_LIMIT);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void suggest_defaults_limit_when_null_or_non_positive() {
        stubPage(List.of());

        service.suggest("q", null);
        ArgumentCaptor<Page> nullCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(nullCaptor.capture(), any(LambdaQueryWrapper.class));
        assertThat(nullCaptor.getValue().getSize()).isEqualTo(PromptTemplateService.SUGGEST_DEFAULT_LIMIT);

        service.suggest("q", 0);
        ArgumentCaptor<Page> zeroCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper, Mockito.times(2)).selectPage(zeroCaptor.capture(), any(LambdaQueryWrapper.class));
        assertThat(zeroCaptor.getAllValues().get(1).getSize()).isEqualTo(1);
    }

    @Test
    void suggest_returns_empty_when_no_match() {
        stubPage(List.of());
        assertThat(service.suggest("不存在", 10)).isEmpty();
        assertThat(service.suggest(null, null)).isEmpty();
    }

    @Test
    void suggest_falls_back_to_content_when_variables_json_invalid() {
        stubPage(List.of(tpl(1L, "坏变量", "你好{{name}}与{{city}}", "not-a-json", null)));

        List<PromptTemplateSuggestion> result = service.suggest("", 10);

        assertThat(result.get(0).variables()).containsExactly("name", "city");
        // remark 为空时回退 category
        assertThat(result.get(0).description()).isEqualTo("客服");
    }
}
