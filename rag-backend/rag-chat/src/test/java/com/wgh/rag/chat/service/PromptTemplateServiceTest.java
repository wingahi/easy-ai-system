package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.entity.PromptTemplate;
import com.wgh.rag.chat.mapper.PromptTemplateMapper;
import com.wgh.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PromptTemplateService 单测：渲染、template: 解析、变量提取、内置保护、版本升级
 */
class PromptTemplateServiceTest {

    private final PromptTemplateMapper mapper = Mockito.mock(PromptTemplateMapper.class);
    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        service = new PromptTemplateService(mapper, new ObjectMapper());
    }

    private PromptTemplate tpl(long id, String name, String content, boolean builtin) {
        PromptTemplate t = new PromptTemplate();
        t.setId(id);
        t.setName(name);
        t.setContent(content);
        t.setBuiltin(builtin);
        t.setVersion(1);
        return t;
    }

    @Test
    void render_replaces_vars_and_blanks_missing() {
        String out = service.render("你好{{userName}}，来自{{kbName}}。{{unknown}}", 
                Map.of("userName", "admin", "kbName", "产品库"));
        assertThat(out).isEqualTo("你好admin，来自产品库。");
    }

    @Test
    void resolve_returns_raw_when_no_prefix() {
        assertThat(service.resolveSystemPrompt("你是助手", Map.of())).isEqualTo("你是助手");
        assertThat(service.resolveSystemPrompt(null, Map.of())).isNull();
    }

    @Test
    void resolve_renders_template_by_name() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(tpl(1L, "RAG问答助手", "知识库「{{kbName}}」助手", true));
        String out = service.resolveSystemPrompt("template:RAG问答助手", Map.of("kbName", "产品库"));
        assertThat(out).isEqualTo("知识库「产品库」助手");
    }

    @Test
    void resolve_throws_when_template_missing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.resolveSystemPrompt("template:不存在", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板不存在");
    }

    @Test
    void extract_variables_in_order_deduped() {
        assertThat(service.extractVariables("{{a}} {{b}} {{a}} {{ c }}"))
                .containsExactly("a", "b", "c");
        assertThat(service.extractVariables("无变量")).isEmpty();
        assertThat(service.extractVariables(null)).isEmpty();
    }

    @Test
    void create_auto_extracts_variables() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        PromptTemplate t = tpl(0, "t1", "你好{{name}}", false);
        service.create(t);
        verify(mapper).insert(any(PromptTemplate.class));
        assertThat(t.getVariables()).isEqualTo("[\"name\"]");
        assertThat(t.getVersion()).isEqualTo(1);
    }

    @Test
    void create_rejects_duplicate_name() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tpl(9L, "t1", "x", false));
        assertThatThrownBy(() -> service.create(tpl(0, "t1", "y", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void update_bumps_version_only_on_content_change() {
        PromptTemplate existing = tpl(1L, "t1", "旧内容", false);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // 内容未变：版本不变
        service.update(tpl(1L, "t1", "旧内容", false));
        assertThat(existing.getVersion()).isEqualTo(1);

        // 内容变化：版本 +1
        service.update(tpl(1L, "t1", "新内容{{v}}", false));
        assertThat(existing.getVersion()).isEqualTo(2);
        assertThat(existing.getVariables()).isEqualTo("[\"v\"]");
    }

    @Test
    void builtin_cannot_rename_or_delete() {
        PromptTemplate existing = tpl(1L, "内置", "内容", true);
        when(mapper.selectById(1L)).thenReturn(existing);

        assertThatThrownBy(() -> service.update(tpl(1L, "改名", "内容", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可改名");

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可删除");
        verify(mapper, never()).deleteById(any(Long.class));
    }
}
