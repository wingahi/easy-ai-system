package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.kb.entity.Tag;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.TagMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 标签服务：CRUD + 知识库/文件标签绑定 + 按标签过滤
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper tagMapper;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public PageResult<Tag> page(Long page, Long size, String keyword) {
        LambdaQueryWrapper<Tag> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like(Tag::getName, keyword);
        }
        qw.orderByAsc(Tag::getName);
        Page<Tag> p = tagMapper.selectPage(Page.of(page, size), qw);
        return new PageResult<>(p.getTotal(), p.getCurrent(), p.getSize(), p.getRecords());
    }

    public List<Tag> listAll() {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>().orderByAsc(Tag::getName));
    }

    public Tag getById(Long id) {
        Tag t = tagMapper.selectById(id);
        if (t == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "标签不存在");
        return t;
    }

    @Transactional
    public Tag create(Tag tag) {
        tag.setId(null);
        if (tag.getName() == null || tag.getName().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标签名不能为空");
        }
        Long dup = tagMapper.selectCount(new LambdaQueryWrapper<Tag>().eq(Tag::getName, tag.getName()));
        if (dup != null && dup > 0) {
            throw new BusinessException("标签名已存在: " + tag.getName());
        }
        tagMapper.insert(tag);
        return tag;
    }

    @Transactional
    public Tag update(Tag tag) {
        getById(tag.getId());
        Tag dup = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, tag.getName()).last("LIMIT 1"));
        if (dup != null && !dup.getId().equals(tag.getId())) {
            throw new BusinessException("标签名已存在: " + tag.getName());
        }
        tagMapper.updateById(tag);
        return tag;
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        tagMapper.deleteById(id);
        // 清理关系表
        jdbcTemplate.update("DELETE FROM wgh_kb_tag_rel WHERE tag_id = ?", id);
        jdbcTemplate.update("DELETE FROM wgh_file_tag_rel WHERE tag_id = ?", id);
    }

    /**
     * 给知识库绑定/解绑标签（ids 为完整列表，内部按差分处理）
     */
    @Transactional
    public void setKbTags(Long kbId, List<?> rawTagIds) {
        jdbcTemplate.update("DELETE FROM wgh_kb_tag_rel WHERE kb_id = ?", kbId);
        if (rawTagIds == null || rawTagIds.isEmpty()) return;
        List<Long> tagIds = rawTagIds.stream().map(o -> ((Number) o).longValue()).toList();
        jdbcTemplate.batchUpdate("INSERT INTO wgh_kb_tag_rel(kb_id, tag_id) VALUES(?, ?)",
                tagIds, tagIds.size(), (ps, tagId) -> {
                    ps.setLong(1, kbId);
                    ps.setLong(2, tagId);
                });
    }

    /**
     * 给文件绑定标签
     */
    @Transactional
    public void setFileTags(Long fileId, List<?> rawTagIds) {
        jdbcTemplate.update("DELETE FROM wgh_file_tag_rel WHERE file_id = ?", fileId);
        if (rawTagIds == null || rawTagIds.isEmpty()) return;
        List<Long> tagIds = rawTagIds.stream().map(o -> ((Number) o).longValue()).toList();
        jdbcTemplate.batchUpdate("INSERT INTO wgh_file_tag_rel(file_id, tag_id) VALUES(?, ?)",
                tagIds, tagIds.size(), (ps, tagId) -> {
                    ps.setLong(1, fileId);
                    ps.setLong(2, tagId);
                });
    }

    /**
     * 列出知识库已绑定标签
     */
    public List<Tag> listKbTags(Long kbId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT tag_id FROM wgh_kb_tag_rel WHERE kb_id = ?", Long.class, kbId);
        if (ids.isEmpty()) return List.of();
        return tagMapper.selectBatchIds(ids);
    }

    /**
     * 列出文件已绑定标签
     */
    public List<Tag> listFileTags(Long fileId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT tag_id FROM wgh_file_tag_rel WHERE file_id = ?", Long.class, fileId);
        if (ids.isEmpty()) return List.of();
        return tagMapper.selectBatchIds(ids);
    }

    /**
     * 按标签 ID 过滤知识库（返回匹配的 kbId 列表，供调用方再分页查询）
     */
    public List<Long> filterKbIdsByTag(Long tagId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT kb_id FROM wgh_kb_tag_rel WHERE tag_id = ?", Long.class, tagId);
    }
}
