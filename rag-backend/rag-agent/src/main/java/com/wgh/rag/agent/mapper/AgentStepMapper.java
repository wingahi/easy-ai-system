package com.wgh.rag.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.agent.entity.AgentStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStep> {

    /**
     * HITL 确认的原子闸门：仅待确认步骤可迁移为 SUCCESS，并写入用户输入。
     *
     * @return 1 表示本次成功确认；0 表示已被其它请求确认
     */
    @Update("""
            UPDATE wgh_agent_step
               SET status = 'SUCCESS',
                   result_summary = #{resultSummary},
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND status = 'HITL_PENDING'
               AND deleted = 0
            """)
    int claimHitlPending(@Param("id") Long id, @Param("resultSummary") String resultSummary);
}
