package com.wgh.rag.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.chat.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * Prompt 模板 Mapper
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
}
