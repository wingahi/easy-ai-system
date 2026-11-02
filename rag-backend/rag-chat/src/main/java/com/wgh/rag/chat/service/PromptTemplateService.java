package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.entity.PromptTemplate;
import com.wgh.rag.chat.mapper.PromptTemplateMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Prompt 模板中心
 * - {{var}} 占位符渲染（复用 rag-common TemplateRenderer）
 * - 聊天窗口 system_prompt 支持 template:模板名 语法，运行时解析
 * - 内置模板（builtin=true）不可删除、不可改名
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    /** system_prompt 引用模板的前缀 */
    public static final String TEMPLATE_PREFIX = "template:";

    private final PromptTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    public PageResult<PromptTemplate> page(Long page, Long size, String keyword, String category) {
        LambdaQueryWrapper<PromptTemplate> qw = new LambdaQueryWrapper<PromptTemplate>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(PromptTemplate::getName, keyword)
                        .or().like(PromptTemplate::getContent, keyword))
                .eq(category != null && !category.isBlank(), PromptTemplate::getCategory, category)
                .orderByDesc(PromptTemplate::getBuiltin)
                .orderByDesc(PromptTemplate::getUpdateTime);
        Page<PromptTemplate> result = mapper.selectPage(Page.of(page, size), qw);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public List<PromptTemplate> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<PromptTemplate>()
                .orderByDesc(PromptTemplate::getBuiltin)
                .orderByAsc(PromptTemplate::getName));
    }

    public PromptTemplate getById(Long id) {
        PromptTemplate t = mapper.selectById(id);
        if (t == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "模板不存在");
        }
        return t;
    }

    @Transactional
    public PromptTemplate create(PromptTemplate t) {
        validate(t);
        if (findByName(t.getName()) != null) {
            throw new BusinessException("模板名已存在: " + t.getName());
        }
        t.setId(null);
        t.setVersion(1);
        t.setBuiltin(false);
        t.setVariables(variablesJson(t.getContent()));
        mapper.insert(t);
        return t;
    }

    @Transactional
    public PromptTemplate update(PromptTemplate t) {
        if (t.getId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "缺少模板 ID");
        }
        PromptTemplate existing = getById(t.getId());
        // 内置模板锁定名字（防止 template: 引用断裂）
        if (Boolean.TRUE.equals(existing.getBuiltin()) && !existing.getName().equals(t.getName())) {
            throw new BusinessException("内置模板不可改名");
        }
        validate(t);
        PromptTemplate dup = findByName(t.getName());
        if (dup != null && !dup.getId().equals(t.getId())) {
            throw new BusinessException("模板名已存在: " + t.getName());
        }
        existing.setName(t.getName());
        existing.setCategory(t.getCategory());
        existing.setRemark(t.getRemark());
        // 内容变更才升级版本号
        if (!existing.getContent().equals(t.getContent())) {
            existing.setContent(t.getContent());
            existing.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
        }
        existing.setVariables(variablesJson(existing.getContent()));
        mapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void delete(Long id) {
        PromptTemplate existing = getById(id);
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new BusinessException("内置模板不可删除");
        }
        mapper.deleteById(id);
    }

    /**
     * 渲染指定模板的变量并返回结果（预览用）
     */
    public String renderById(Long id, Map<String, Object> vars) {
        return render(getById(id).getContent(), vars);
    }

    /**
     * 渲染模板内容中的 {{var}} 占位符；未提供的变量渲染为空串
     */
    public String render(String content, Map<String, Object> vars) {
        Map<String, Object> safeVars = vars == null ? Map.of() : vars;
        return TemplateRenderer.render(content, safeVars::get);
    }

    /**
     * 解析 system_prompt：template:模板名 → 查库渲染；否则原样返回
     */
    public String resolveSystemPrompt(String systemPrompt, Map<String, Object> vars) {
        if (systemPrompt == null || !systemPrompt.trim().startsWith(TEMPLATE_PREFIX)) {
            return systemPrompt;
        }
        String templateName = systemPrompt.trim().substring(TEMPLATE_PREFIX.length()).trim();
        PromptTemplate t = findByName(templateName);
        if (t == null) {
            throw new BusinessException("Prompt 模板不存在: " + templateName);
        }
        return render(t.getContent(), vars);
    }

    public PromptTemplate findByName(String name) {
        return mapper.selectOne(new LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getName, name).last("LIMIT 1"));
    }

    /**
     * 从模板内容提取 {{var}} 变量名（保持出现顺序去重）
     */
    public List<String> extractVariables(String content) {
        Set<String> vars = new LinkedHashSet<>();
        if (content != null) {
            Matcher m = TemplateRenderer.VAR_PATTERN.matcher(content);
            while (m.find()) {
                vars.add(m.group(1));
            }
        }
        return List.copyOf(vars);
    }

    private String variablesJson(String content) {
        try {
            return objectMapper.writeValueAsString(extractVariables(content));
        } catch (Exception e) {
            return "[]";
        }
    }

    private void validate(PromptTemplate t) {
        if (t.getName() == null || t.getName().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "模板名不能为空");
        }
        if (t.getContent() == null || t.getContent().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "模板内容不能为空");
        }
    }
}
