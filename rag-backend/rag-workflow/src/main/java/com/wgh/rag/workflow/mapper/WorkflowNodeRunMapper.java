package com.wgh.rag.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.workflow.entity.WorkflowNodeRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkflowNodeRunMapper extends BaseMapper<WorkflowNodeRun> {

    /**
     * HITL 确认的原子闸门：仅待确认的节点可迁移为 SUCCESS。
     *
     * @return 1 表示本次成功确认；0 表示已被其它请求确认
     */
    @Update("""
            UPDATE wgh_workflow_node_run
               SET status = 'SUCCESS',
                   output_json = #{outputJson},
                   end_time = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND status = 'HITL_PENDING'
               AND deleted = 0
            """)
    int claimHitlPending(@Param("id") Long id, @Param("outputJson") String outputJson);
}
