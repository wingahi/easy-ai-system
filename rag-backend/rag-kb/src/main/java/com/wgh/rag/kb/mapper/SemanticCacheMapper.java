package com.wgh.rag.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.kb.entity.SemanticCache;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SemanticCacheMapper extends BaseMapper<SemanticCache> {

    /** 物理删除（缓存淘汰用，不走逻辑删除避免死数据堆积） */
    @Delete("DELETE FROM wgh_semantic_cache WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);
}
