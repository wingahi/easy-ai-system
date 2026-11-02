package com.wgh.rag.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.llm.entity.ModelPrice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型价格 Mapper
 */
@Mapper
public interface ModelPriceMapper extends BaseMapper<ModelPrice> {
}
