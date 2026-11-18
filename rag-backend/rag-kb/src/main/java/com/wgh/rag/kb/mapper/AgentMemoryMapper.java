package com.wgh.rag.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.kb.entity.AgentMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索式记忆 Mapper
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {
}
