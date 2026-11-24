package com.wgh.rag.scheduler.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户集群运行槽 Mapper（wgh_tenant_runtime_slot）。
 *
 * <p>槽位只用于多副本下的租户并发约束与漂移观测：执行前原子占用、结束后释放，
 * 周期性用作业表实际在跑数校正，进程异常退出造成的泄漏最多保留一个对账周期。
 */
public interface TenantRuntimeSlotMapper {

    /**
     * 原子占槽：未达上限则 +1。
     *
     * @return 1 表示占槽成功；0 表示已达上限
     */
    @Update("""
            INSERT INTO wgh_tenant_runtime_slot (tenant_id, lane, running, update_time)
            VALUES (#{tenantId}, #{lane}, 1, now())
            ON CONFLICT (tenant_id, lane) DO UPDATE
            SET running = wgh_tenant_runtime_slot.running + 1,
                update_time = now()
            WHERE wgh_tenant_runtime_slot.running < #{limit}
            """)
    int tryAcquire(@Param("tenantId") String tenantId,
                   @Param("lane") String lane,
                   @Param("limit") int limit);

    /** 释放槽位；重复释放不会产生负数。 */
    @Update("""
            UPDATE wgh_tenant_runtime_slot
            SET running = CASE WHEN running > 0 THEN running - 1 ELSE 0 END,
                update_time = now()
            WHERE tenant_id = #{tenantId} AND lane = #{lane}
            """)
    int release(@Param("tenantId") String tenantId, @Param("lane") String lane);

    /**
     * 槽位与作业表实际在跑数的偏差：所有 (tenant, lane) 键的绝对差之和。
     *
     * <p>键集合取「槽位表 ∪ 作业表」并集，避免槽位表缺行时漏算。
     */
    @Select("""
            SELECT COALESCE(SUM(ABS(COALESCE(s.running, 0) - COALESCE(a.cnt, 0))), 0)
            FROM (
                SELECT tenant_id, lane FROM wgh_tenant_runtime_slot
                WHERE lane IN ('BACKGROUND', 'MAINTENANCE')
                UNION
                SELECT tenant_id, lane FROM wgh_execution_job
                WHERE deleted = 0 AND status IN ('LEASED', 'RUNNING')
                  AND lane IN ('BACKGROUND', 'MAINTENANCE')
            ) k
            LEFT JOIN wgh_tenant_runtime_slot s
              ON s.tenant_id = k.tenant_id AND s.lane = k.lane
            LEFT JOIN (
                SELECT tenant_id, lane, COUNT(*) AS cnt FROM wgh_execution_job
                WHERE deleted = 0 AND status IN ('LEASED', 'RUNNING')
                  AND lane IN ('BACKGROUND', 'MAINTENANCE')
                GROUP BY tenant_id, lane
            ) a ON a.tenant_id = k.tenant_id AND a.lane = k.lane
            """)
    long currentDrift();

    /** 用作业表实际在跑数覆盖已有槽位行。 */
    @Update("""
            INSERT INTO wgh_tenant_runtime_slot (tenant_id, lane, running, update_time)
            SELECT tenant_id, lane, COUNT(*), now() FROM wgh_execution_job
            WHERE deleted = 0 AND status IN ('LEASED', 'RUNNING')
              AND lane IN ('BACKGROUND', 'MAINTENANCE')
            GROUP BY tenant_id, lane
            ON CONFLICT (tenant_id, lane) DO UPDATE
            SET running = EXCLUDED.running,
                update_time = now()
            """)
    int refreshFromJobs();

    /** 无在跑作业的槽位归零，保留行便于管理端观测。 */
    @Update("""
            UPDATE wgh_tenant_runtime_slot s
            SET running = 0,
                update_time = now()
            WHERE s.lane IN ('BACKGROUND', 'MAINTENANCE')
              AND NOT EXISTS (
                  SELECT 1 FROM wgh_execution_job j
                  WHERE j.tenant_id = s.tenant_id AND j.lane = s.lane
                    AND j.deleted = 0 AND j.status IN ('LEASED', 'RUNNING')
              )
            """)
    int zeroIdleSlots();

    /** 全部槽位（管理端租户视图使用）。 */
    @Select("""
            SELECT tenant_id, lane, running, update_time FROM wgh_tenant_runtime_slot
            ORDER BY tenant_id, lane
            """)
    java.util.List<java.util.Map<String, Object>> selectAllSlots();
}
