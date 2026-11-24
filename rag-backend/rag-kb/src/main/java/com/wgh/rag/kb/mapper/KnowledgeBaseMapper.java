package com.wgh.rag.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.kb.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 平台级向量表数量：走 SECURITY DEFINER 计数函数，跨租户只返回 count。
     *
     * <p>函数无参数、不返回行，也不依赖 {@code app.tenant_id}；调用方不得在事务内
     * 依赖该值做租户判断。</p>
     */
    @Select("SELECT public.wgh_kb_vector_table_count()")
    Long selectVectorTableCountCrossTenant();
}
