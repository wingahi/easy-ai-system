package com.wgh.rag.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.kb.entity.SemanticCache;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SemanticCacheMapper extends BaseMapper<SemanticCache> {

    /** 物理删除（缓存淘汰用，不走逻辑删除避免死数据堆积） */
    @Delete("DELETE FROM wgh_semantic_cache WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /** 查询未删除的聊天窗口及其知识库 ID 列表（知识库变更时定位需失效的窗口缓存） */
    @Select("SELECT id AS windowId, knowledge_base_ids AS kbIds FROM wgh_chat_window WHERE deleted = 0")
    List<Map<String, Object>> selectWindowKbMappings();
}
