package com.wgh.rag.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.workflow.entity.WorkflowRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    /**
     * HITL 确认的原子闸门：仅 PAUSED 可推进为 RUNNING。
     * <p>同一轮确认在并发/重试下只有第一个请求能拿到 1 行，避免重复执行与重复扣费。</p>
     *
     * @return 1 表示本次成功推进；0 表示已被其它请求推进/非暂停态
     */
    @Update("""
            UPDATE wgh_workflow_run
               SET status = 'RUNNING',
                   context_json = #{contextJson},
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{runId}
               AND status = 'PAUSED'
               AND deleted = 0
            """)
    int claimPausedForResume(@Param("runId") Long runId, @Param("contextJson") String contextJson);
}
