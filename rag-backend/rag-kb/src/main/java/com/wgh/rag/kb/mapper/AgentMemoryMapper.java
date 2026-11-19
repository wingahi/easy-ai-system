package com.wgh.rag.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.kb.entity.AgentMemory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 检索式记忆 Mapper
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {

    /** 物理删除（TTL / 容量淘汰用，避免逻辑删除行无限堆积） */
    @Delete("DELETE FROM wgh_agent_memory WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /** 列出存在记忆的作用域（容量清理按作用域统计条数） */
    @Select("SELECT scope_type AS scopeType, scope_id AS scopeId FROM wgh_agent_memory "
            + "WHERE deleted = 0 GROUP BY scope_type, scope_id LIMIT #{limit}")
    List<Map<String, Object>> selectScopeGroups(@Param("limit") int limit);

    /**
     * Postgres 事务级咨询锁（跨实例单飞，随事务提交自动释放，避免连接归还后锁泄漏）。
     * <p>非 Postgres 数据源（如 H2 测试库）会抛异常，由调用方降级为进程内单飞。
     */
    @Select("SELECT pg_try_advisory_xact_lock(#{key})")
    Boolean tryAdvisoryXactLock(@Param("key") long key);
}
