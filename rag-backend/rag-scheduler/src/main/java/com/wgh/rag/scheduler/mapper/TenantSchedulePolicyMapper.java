package com.wgh.rag.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.scheduler.entity.TenantSchedulePolicy;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 租户调度策略 Mapper。
 *
 * <p>写入使用 {@code version} 乐观锁；影响行数为 0 表示已被并发修改，由管理端提示刷新。
 */
public interface TenantSchedulePolicyMapper extends BaseMapper<TenantSchedulePolicy> {

    /** 全量策略（策略缓存刷新使用，按租户与车道排序便于稳定快照）。 */
    @Select("""
            SELECT * FROM wgh_tenant_schedule_policy
            WHERE deleted = 0
            ORDER BY tenant_id, lane
            """)
    List<TenantSchedulePolicy> selectAllPolicies();

    /** 单租户全部车道策略。 */
    @Select("""
            SELECT * FROM wgh_tenant_schedule_policy
            WHERE tenant_id = #{tenantId} AND deleted = 0
            ORDER BY lane
            """)
    List<TenantSchedulePolicy> selectByTenant(@Param("tenantId") String tenantId);

    /** 乐观锁更新；返回 0 表示版本冲突。 */
    @Update("""
            UPDATE wgh_tenant_schedule_policy
            SET enabled = #{policy.enabled},
                max_concurrent = #{policy.maxConcurrent},
                max_queue = #{policy.maxQueue},
                weight = #{policy.weight},
                priority_boost = #{policy.priorityBoost},
                reject_policy = #{policy.rejectPolicy},
                queue_timeout_seconds = #{policy.queueTimeoutSeconds},
                daily_job_limit = #{policy.dailyJobLimit},
                rate_per_minute = #{policy.ratePerMinute},
                time_window_json = CAST(#{policy.timeWindowJson,jdbcType=VARCHAR} AS jsonb),
                remark = #{policy.remark,jdbcType=VARCHAR},
                version = version + 1,
                update_time = now()
            WHERE id = #{policy.id} AND version = #{expectedVersion} AND deleted = 0
            """)
    int updateWithVersion(@Param("policy") TenantSchedulePolicy policy,
                          @Param("expectedVersion") int expectedVersion);
}
