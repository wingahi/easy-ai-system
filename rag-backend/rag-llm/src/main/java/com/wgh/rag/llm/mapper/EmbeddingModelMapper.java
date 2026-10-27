package com.wgh.rag.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.llm.entity.EmbeddingModelConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmbeddingModelMapper extends BaseMapper<EmbeddingModelConfig> {
}
