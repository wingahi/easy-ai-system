package com.wgh.rag.chat.dto;

import java.util.List;

/**
 * Prompt 模板前缀联想候选（轻量返回体）
 * <p>
 * 仅用于输入框前缀触发时的候选面板展示与一键填入，不参与模板 CRUD / 版本逻辑。
 *
 * @param id          模板 ID
 * @param name        模板名
 * @param description 简述（取 remark，为空时回退 category）
 * @param category    分类（作为标签参与模糊匹配）
 * @param variables   变量名列表（来自 variables 字段，缺失时按 content 兜底提取）
 * @param version     当前版本号
 * @param content     模板内容（选中后直接填入输入框，避免二次请求）
 */
public record PromptTemplateSuggestion(
        Long id,
        String name,
        String description,
        String category,
        List<String> variables,
        Integer version,
        String content) {
}
