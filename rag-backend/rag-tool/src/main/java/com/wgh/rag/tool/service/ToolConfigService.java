package com.wgh.rag.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.mapper.ToolConfigMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.data.scope.DataScopeGuard;
import com.wgh.rag.data.scope.ResourceVisibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工具管理服务 - Skill / MCP / HTTP接口 / Function Calling
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolConfigService {

    private final ToolConfigMapper toolMapper;

    public PageResult<ToolConfig> page(Long page, Long size, String toolType, String keyword) {
        return DataScopeContext.runWithScope(() -> {
            LambdaQueryWrapper<ToolConfig> wrapper = new LambdaQueryWrapper<>();
            if (toolType != null && !toolType.isBlank()) {
                wrapper.eq(ToolConfig::getToolType, toolType);
            }
            if (keyword != null && !keyword.isBlank()) {
                wrapper.like(ToolConfig::getName, keyword).or().like(ToolConfig::getDescription, keyword);
            }
            wrapper.orderByDesc(ToolConfig::getCreateTime);
            Page<ToolConfig> result = toolMapper.selectPage(Page.of(page, size), wrapper);
            return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
        });
    }

    public ToolConfig getById(Long id) {
        ToolConfig tool = toolMapper.selectById(id);
        if (tool == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "工具不存在");
        return tool;
    }

    /** 详情可见性校验：PRIVATE 他人工具按不存在处理 */
    public ToolConfig getVisible(Long id) {
        ToolConfig tool = getById(id);
        DataScopeGuard.assertReadable(tool.getCreateBy(), tool.getVisibility(), "工具");
        return tool;
    }

    @Transactional
    public ToolConfig create(ToolConfig tool) {
        tool.setStatus(tool.getStatus() != null ? tool.getStatus() : 1);
        tool.setIsExample(false);
        tool.setVisibility(ResourceVisibility.normalize(tool.getVisibility()));
        toolMapper.insert(tool);
        return tool;
    }

    @Transactional
    public ToolConfig update(ToolConfig tool) {
        ToolConfig existing = toolMapper.selectById(tool.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "工具不存在");
        }
        DataScopeGuard.assertWritable(existing.getCreateBy(), existing.getVisibility(), "工具");
        String visibility = ResourceVisibility.normalizeOptional(tool.getVisibility());
        if (visibility != null) {
            tool.setVisibility(visibility);
        }
        toolMapper.updateById(tool);
        return tool;
    }

    @Transactional
    public void delete(Long id) {
        ToolConfig existing = toolMapper.selectById(id);
        if (existing == null) {
            return;
        }
        DataScopeGuard.assertWritable(existing.getCreateBy(), existing.getVisibility(), "工具");
        toolMapper.deleteById(id);
    }

    /**
     * 列出启用状态的工具
     *
     * @param toolType 工具类型；为空则返回所有类型的启用工具（供聊天窗口选择工具使用）
     */
    public List<ToolConfig> listByType(String toolType) {
        return DataScopeContext.runWithScope(() -> {
            LambdaQueryWrapper<ToolConfig> wrapper = new LambdaQueryWrapper<ToolConfig>()
                    .eq(ToolConfig::getStatus, 1)
                    .orderByDesc(ToolConfig::getCreateTime);
            if (toolType != null && !toolType.isBlank()) {
                wrapper.eq(ToolConfig::getToolType, toolType);
            }
            return toolMapper.selectList(wrapper);
        });
    }

    /** 修改可见性：仅所有者/ADMIN，PRIVATE / TEAM / PUBLIC */
    @Transactional
    public ToolConfig updateVisibility(Long id, String visibility) {
        ToolConfig tool = getById(id);
        DataScopeGuard.assertWritable(tool.getCreateBy(), tool.getVisibility(), "工具");
        String normalized = ResourceVisibility.normalize(visibility);
        toolMapper.update(null, new UpdateWrapper<ToolConfig>()
                .eq("id", id)
                .set("visibility", normalized));
        tool.setVisibility(normalized);
        return tool;
    }

    /**
     * 按 ID 批量查询（供 RagChatService 加载窗口级工具使用）
     */
    public List<ToolConfig> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return toolMapper.selectBatchIds(ids);
    }

    /**
     * 按名称查找未删除的 SKILL 工具（用于导入时判重；不区分大小写）
     */
    public ToolConfig findSkillByName(String name) {
        if (name == null || name.isBlank()) return null;
        return toolMapper.selectOne(new LambdaQueryWrapper<ToolConfig>()
                .eq(ToolConfig::getToolType, "SKILL")
                .eq(ToolConfig::getName, name)
                .last("LIMIT 1"));
    }
}
