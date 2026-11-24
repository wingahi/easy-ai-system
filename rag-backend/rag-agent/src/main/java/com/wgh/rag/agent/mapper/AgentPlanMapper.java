package com.wgh.rag.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.agent.entity.AgentPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentPlanMapper extends BaseMapper<AgentPlan> {

    /**
     * HITL 确认的原子闸门：仅 PAUSED 计划的确认请求可推进为 RUNNING。
     *
     * @return 1 表示本次成功推进；0 表示已被其它请求推进/非暂停态
     */
    @Update("""
            UPDATE wgh_agent_plan
               SET status = 'RUNNING',
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND status = 'PAUSED'
               AND deleted = 0
            """)
    int claimPausedForResume(@Param("id") Long id);
}
