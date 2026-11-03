package com.wgh.rag.kb.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.Tag;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.TagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TagService 知识库标签列表单测：正常路径 + kbId 不存在路径
 */
class TagServiceTest {

    private final TagMapper tagMapper = Mockito.mock(TagMapper.class);
    private final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
    private final KnowledgeBaseMapper kbMapper = Mockito.mock(KnowledgeBaseMapper.class);

    private TagService service;

    @BeforeEach
    void setUp() {
        service = new TagService(tagMapper, jdbcTemplate, kbMapper);
    }

    @Test
    @DisplayName("kb 存在：按关系表 tagId 返回标签数组")
    void listKbTags_existingKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(3L);
        when(kbMapper.selectById(3L)).thenReturn(kb);
        when(jdbcTemplate.queryForList("SELECT tag_id FROM wgh_kb_tag_rel WHERE kb_id = ?",
                Long.class, 3L)).thenReturn(List.of(1L, 2L));
        Tag t1 = tag(1L, "简历");
        Tag t2 = tag(2L, "技术");
        when(tagMapper.selectBatchIds(any())).thenReturn(List.of(t1, t2));

        List<Tag> tags = service.listKbTags(3L);

        assertThat(tags).extracting(Tag::getName).containsExactly("简历", "技术");
    }

    @Test
    @DisplayName("kb 存在但未绑定标签：返回空数组")
    void listKbTags_existingKbWithoutTags() {
        when(kbMapper.selectById(9L)).thenReturn(new KnowledgeBase());
        when(jdbcTemplate.queryForList("SELECT tag_id FROM wgh_kb_tag_rel WHERE kb_id = ?",
                Long.class, 9L)).thenReturn(List.of());

        assertThat(service.listKbTags(9L)).isEmpty();
        verify(tagMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("kb 不存在：抛出 3001 业务异常，且不再查询关系表")
    void listKbTags_missingKb() {
        when(kbMapper.selectById(99999L)).thenReturn(null);

        assertThatThrownBy(() -> service.listKbTags(99999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode()))
                .hasMessage("知识库不存在");

        verifyNoInteractions(jdbcTemplate);
    }

    private static Tag tag(Long id, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }
}
