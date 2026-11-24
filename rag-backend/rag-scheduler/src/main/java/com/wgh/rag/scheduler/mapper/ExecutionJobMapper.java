package com.wgh.rag.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 作业表 Mapper。
 *
 * <p>状态迁移全部使用带条件的原子 UPDATE（{@code WHERE id=? AND lease_owner=? AND status IN (...)}），
 * 以「影响行数为 0」表达租约已被接管，从而避免租约过期后的双写。
 */
public interface ExecutionJobMapper extends BaseMapper<ExecutionJob> {

    /**
     * 领取一条到期作业：{@code FOR UPDATE SKIP LOCKED} 保证多实例并发领取不重不漏。
     *
     * <p>领取即把状态置为 LEASED、写入租约、尝试次数 +1。
     *
     * @return 被领取作业的主键；无可用作业返回 {@code null}
     */
    @Select("""
            UPDATE wgh_execution_job
            SET status = 'LEASED',
                lease_owner = #{owner},
                lease_expire_time = now() + make_interval(secs => #{leaseTtlSeconds}),
                heartbeat_time = now(),
                attempt = attempt + 1,
                version = version + 1,
                update_time = now()
            WHERE id = (
                SELECT id FROM wgh_execution_job
                WHERE status = 'PENDING'
                  AND lane = #{lane}
                  AND deleted = 0
                  AND scheduled_at <= now()
                  AND (not_before IS NULL OR not_before <= now())
                ORDER BY priority DESC, scheduled_at ASC, id ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            AND status = 'PENDING'
            RETURNING id
            """)
    Long claimNext(@Param("lane") String lane,
                   @Param("owner") String owner,
                   @Param("leaseTtlSeconds") long leaseTtlSeconds);

    /** LEASED → RUNNING；返回 0 表示租约已不属于当前持有者。 */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'RUNNING',
                start_time = COALESCE(start_time, now()),
                heartbeat_time = now(),
                update_time = now()
            WHERE id = #{id} AND lease_owner = #{owner} AND status = 'LEASED' AND deleted = 0
            """)
    int markRunning(@Param("id") Long id, @Param("owner") String owner);

    /** 续租；返回 0 表示租约已丢失。 */
    @Update("""
            UPDATE wgh_execution_job
            SET lease_expire_time = now() + make_interval(secs => #{leaseTtlSeconds}),
                heartbeat_time = now(),
                update_time = now()
            WHERE id = #{id} AND lease_owner = #{owner}
              AND status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    int renewLease(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("leaseTtlSeconds") long leaseTtlSeconds);

    /** 成功终态；返回 0 表示租约已丢失（结果丢弃，不覆盖接管者）。 */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'SUCCEEDED',
                result_json = CAST(#{resultJson,jdbcType=VARCHAR} AS jsonb),
                lease_owner = NULL,
                lease_expire_time = NULL,
                finish_time = now(),
                version = version + 1,
                update_time = now()
            WHERE id = #{id} AND lease_owner = #{owner}
              AND status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    int complete(@Param("id") Long id,
                 @Param("owner") String owner,
                 @Param("resultJson") String resultJson);

    /** 是否存在已持久化指定 runId 且仍活跃的作业（用于中断扫描排除正常执行中的 run）。 */
    @Select("""
            SELECT COUNT(*) FROM wgh_execution_job
            WHERE deleted = 0
              AND job_type = #{jobType}
              AND status IN ('PENDING', 'LEASED', 'RUNNING')
              AND result_json ->> 'runId' = #{runId}
            """)
    long countActiveByResultRunId(@Param("jobType") String jobType, @Param("runId") String runId);

    /** 执行中持久化进度（如 runId）；返回 0 表示租约已丢失。 */
    @Update("""
            UPDATE wgh_execution_job
            SET result_json = CAST(#{resultJson,jdbcType=VARCHAR} AS jsonb),
                update_time = now()
            WHERE id = #{id} AND lease_owner = #{owner}
              AND status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    int updateResult(@Param("id") Long id,
                     @Param("owner") String owner,
                     @Param("resultJson") String resultJson);

    /** 失败/死信；返回 0 表示租约已丢失。 */
    @Update("""
            UPDATE wgh_execution_job
            SET status = #{status},
                error_code = #{errorCode,jdbcType=VARCHAR},
                error_message = #{errorMessage,jdbcType=VARCHAR},
                not_before = #{notBefore,jdbcType=TIMESTAMP},
                lease_owner = NULL,
                lease_expire_time = NULL,
                finish_time = #{finishTime,jdbcType=TIMESTAMP},
                version = version + 1,
                update_time = now()
            WHERE id = #{id} AND lease_owner = #{owner}
              AND status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    int fail(@Param("id") Long id,
             @Param("owner") String owner,
             @Param("status") String status,
             @Param("errorCode") String errorCode,
             @Param("errorMessage") String errorMessage,
             @Param("notBefore") java.time.LocalDateTime notBefore,
             @Param("finishTime") java.time.LocalDateTime finishTime);

    /** 持有者主动取消（协作取消已生效后落终态）；返回 0 表示租约已丢失。 */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'CANCELLED',
                cancel_requested = 1,
                cancel_reason = #{reason,jdbcType=VARCHAR},
                error_code = 'CANCELLED',
                lease_owner = NULL,
                lease_expire_time = NULL,
                finish_time = now(),
                version = version + 1,
                update_time = now()
            WHERE id = #{id} AND lease_owner = #{owner}
              AND status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    int cancelOwned(@Param("id") Long id, @Param("owner") String owner, @Param("reason") String reason);

    /**
     * 注销交互式运行态：物理删除仍在运行且仍由本实例持有的记录。
     *
     * <p>用于「进行中」登记，不保留终态历史；已被回收（{@code lease_owner} 清空）的记录不会误删。
     */
    @Delete("""
            DELETE FROM wgh_execution_job
            WHERE id = #{id} AND lease_owner = #{owner}
              AND status IN ('LEASED', 'RUNNING')
            """)
    int deleteRunningInteractive(@Param("id") Long id, @Param("owner") String owner);

    /** 物理清理崩溃残留的终态交互式运行态记录；只作用于 INTERACTIVE 车道。 */
    @Delete("""
            DELETE FROM wgh_execution_job
            WHERE lane = 'INTERACTIVE'
              AND status IN ('SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED')
              AND update_time < now() - make_interval(secs => #{retentionSeconds})
            """)
    int purgeTerminalInteractiveRuns(@Param("retentionSeconds") long retentionSeconds);

    /** 排队中取消：PENDING → CANCELLED。 */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'CANCELLED',
                cancel_requested = 1,
                cancel_reason = #{reason,jdbcType=VARCHAR},
                error_code = 'CANCELLED',
                finish_time = now(),
                version = version + 1,
                update_time = now()
            WHERE id = #{id} AND status = 'PENDING' AND deleted = 0
            """)
    int cancelPending(@Param("id") Long id, @Param("reason") String reason);

    /** 运行中取消：只置标记，由持有者协作退出。 */
    @Update("""
            UPDATE wgh_execution_job
            SET cancel_requested = 1,
                cancel_reason = #{reason,jdbcType=VARCHAR},
                update_time = now()
            WHERE id = #{id} AND status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    int requestCancel(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 单飞锁未抢到或 Redis 不可用：放弃本次领取（回滚 attempt），稍后再试。
     *
     * <p>放回队列时刷新 {@code scheduled_at}：排队超时从本次重排起算，
     * 避免被 {@code expireQueued} 按旧入队时刻立即判成排队超时。
     */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'PENDING',
                attempt = CASE WHEN attempt > 0 THEN attempt - 1 ELSE 0 END,
                scheduled_at = now(),
                lease_owner = NULL,
                lease_expire_time = NULL,
                not_before = #{notBefore,jdbcType=TIMESTAMP},
                version = version + 1,
                update_time = now()
            WHERE id = #{id} AND lease_owner = #{owner} AND status = 'LEASED' AND deleted = 0
            """)
    int releaseClaim(@Param("id") Long id,
                     @Param("owner") String owner,
                     @Param("notBefore") java.time.LocalDateTime notBefore);

    /** 租约过期回收：未超上限回到 PENDING，超上限置 DEAD(LEASE_LOST)。 */
    @Update("""
            UPDATE wgh_execution_job
            SET status = CASE WHEN attempt >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                error_code = 'LEASE_LOST',
                error_message = '租约过期，由回收器回收',
                scheduled_at = CASE WHEN attempt >= max_attempts THEN scheduled_at ELSE now() END,
                lease_owner = NULL,
                lease_expire_time = NULL,
                finish_time = CASE WHEN attempt >= max_attempts THEN now() ELSE NULL END,
                version = version + 1,
                update_time = now()
            WHERE status IN ('LEASED', 'RUNNING')
              AND lease_expire_time IS NOT NULL
              AND lease_expire_time < now()
              AND deleted = 0
            """)
    int reclaimExpired();

    /**
     * 失联实例提前回收：不等租约自然到期，回收指定实例持有的活跃作业。
     *
     * <p>仍复用与租约过期回收相同的终态口径：未超上限回 PENDING 重试，超上限置 DEAD。
     * 旧实例此后的终态/进度写入因 {@code lease_owner} 已清空而失败，不会覆盖接管者。
     *
     * <p>回到 PENDING 时同时刷新 {@code scheduled_at}：重排的是「此刻」的队列，否则
     * {@code expireQueued} 会按旧的入队时刻立即把它判成排队超时，{@code LEASE_LOST}
     * 重试分支实际不可达。
     *
     * @return 被回收的作业数
     */
    @Update("""
            UPDATE wgh_execution_job
            SET status = CASE WHEN attempt >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                error_code = CASE WHEN attempt >= max_attempts THEN 'INSTANCE_LOST' ELSE 'LEASE_LOST' END,
                error_message = '所属实例失联，提前回收',
                scheduled_at = CASE WHEN attempt >= max_attempts THEN scheduled_at ELSE now() END,
                lease_owner = NULL,
                lease_expire_time = NULL,
                finish_time = CASE WHEN attempt >= max_attempts THEN now() ELSE NULL END,
                version = version + 1,
                update_time = now()
            WHERE status IN ('LEASED', 'RUNNING')
              AND lease_owner = #{owner}
              AND deleted = 0
            """)
    int reclaimByOwner(@Param("owner") String owner);

    /**
     * 对账候选：指定类型、最近窗口内更新过的作业（含全部状态，由对账侧判定一致性）。
     */
    @Select("""
            SELECT * FROM wgh_execution_job
            WHERE deleted = 0
              AND job_type = #{jobType}
              AND update_time >= #{since}
              AND status IN ('PENDING', 'LEASED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED')
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    java.util.List<ExecutionJob> selectReconcileCandidates(@Param("jobType") String jobType,
                                                           @Param("since") java.time.LocalDateTime since,
                                                           @Param("limit") int limit);

    /**
     * 可重试失败 → PENDING。
     *
     * <p>重排时刷新 {@code scheduled_at}：排队超时从本次重排起算，否则
     * {@code expireQueued} 会按旧的入队时刻把刚放回队列的作业立即判成排队超时。
     */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'PENDING',
                scheduled_at = now(),
                not_before = NULL,
                lease_owner = NULL,
                lease_expire_time = NULL,
                update_time = now()
            WHERE status = 'FAILED'
              AND attempt < max_attempts
              AND (error_code IS NULL OR error_code <> 'JOB_QUEUE_TIMEOUT')
              AND (not_before IS NULL OR not_before <= now())
              AND deleted = 0
            """)
    int requeueFailed();

    /**
     * 管理端重试：失败/死信/取消 → PENDING，重置尝试次数与错误信息。
     *
     * @return 0 表示状态已变化（已被重试或不符合重试条件）
     */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'PENDING',
                attempt = 0,
                scheduled_at = now(),
                not_before = NULL,
                lease_owner = NULL,
                lease_expire_time = NULL,
                heartbeat_time = NULL,
                error_code = NULL,
                error_message = NULL,
                cancel_requested = 0,
                cancel_reason = NULL,
                finish_time = NULL,
                version = version + 1,
                update_time = now()
            WHERE id = #{id} AND deleted = 0
              AND status IN ('FAILED', 'DEAD', 'CANCELLED')
            """)
    int retryJob(@Param("id") Long id);

    /**
     * 管理端强制回收：活跃作业（LEASED/RUNNING）回到 PENDING，由调度循环重新领取。
     *
     * @return 0 表示作业已不在活跃状态
     */
    @Update("""
            UPDATE wgh_execution_job
            SET status = 'PENDING',
                attempt = 0,
                scheduled_at = now(),
                not_before = NULL,
                lease_owner = NULL,
                lease_expire_time = NULL,
                heartbeat_time = NULL,
                error_code = 'MANUAL_RECLAIM',
                error_message = #{reason,jdbcType=VARCHAR},
                finish_time = NULL,
                version = version + 1,
                update_time = now()
            WHERE id = #{id} AND deleted = 0
              AND status IN ('LEASED', 'RUNNING')
            """)
    int reclaimJob(@Param("id") Long id, @Param("reason") String reason);

    /** 排队超时 → FAILED(JOB_QUEUE_TIMEOUT)，不进入重试。 */    @Update("""
            UPDATE wgh_execution_job
            SET status = 'FAILED',
                error_code = 'JOB_QUEUE_TIMEOUT',
                error_message = '排队超过上限，未被执行',
                finish_time = now(),
                version = version + 1,
                update_time = now()
            WHERE status = 'PENDING'
              AND scheduled_at < now() - make_interval(secs => #{queueTimeoutSeconds})
              AND deleted = 0
            """)
    int expireQueued(@Param("queueTimeoutSeconds") long queueTimeoutSeconds);

    /** 取消标记轮询。 */
    @Select("""
            SELECT cancel_requested FROM wgh_execution_job
            WHERE id = #{id} AND deleted = 0
            """)
    Integer selectCancelRequested(@Param("id") Long id);

    @Select("""
            SELECT * FROM wgh_execution_job
            WHERE execution_id = #{executionId} AND deleted = 0
            LIMIT 1
            """)
    ExecutionJob selectByExecutionId(@Param("executionId") String executionId);

    @Select("""
            SELECT * FROM wgh_execution_job
            WHERE job_type = #{jobType} AND biz_key = #{bizKey} AND deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    ExecutionJob selectByBizKey(@Param("jobType") String jobType, @Param("bizKey") String bizKey);

    @Select("""
            SELECT COUNT(*) FROM wgh_execution_job
            WHERE lane = #{lane} AND status = 'PENDING' AND deleted = 0
            """)
    long countPending(@Param("lane") String lane);

    @Select("""
            SELECT COUNT(*) FROM wgh_execution_job
            WHERE tenant_id = #{tenantId} AND lane = #{lane}
              AND status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    long countTenantActive(@Param("tenantId") String tenantId, @Param("lane") String lane);

    /** 指定租户车道的排队数（租户策略 max_queue 判定）。 */
    @Select("""
            SELECT COUNT(*) FROM wgh_execution_job
            WHERE tenant_id = #{tenantId} AND lane = #{lane}
              AND status = 'PENDING' AND deleted = 0
            """)
    long countPendingByTenant(@Param("tenantId") String tenantId, @Param("lane") String lane);

    // ===== 管理端只读聚合（数据来源统一为作业表） =====

    /** 按状态分组计数。 */
    @Select("""
            SELECT status, COUNT(*) AS cnt FROM wgh_execution_job
            WHERE deleted = 0
            GROUP BY status
            """)
    java.util.List<java.util.Map<String, Object>> countGroupByStatus();

    /** 按车道分组计数。 */
    @Select("""
            SELECT lane, COUNT(*) AS cnt FROM wgh_execution_job
            WHERE deleted = 0
            GROUP BY lane
            """)
    java.util.List<java.util.Map<String, Object>> countGroupByLane();

    /** 作业总数。 */
    @Select("SELECT COUNT(*) FROM wgh_execution_job WHERE deleted = 0")
    long countAll();

    /** 待执行数（仅 PENDING，已领取但未开始计入在途）。 */
    @Select("SELECT COUNT(*) FROM wgh_execution_job WHERE status = 'PENDING' AND deleted = 0")
    long countPendingAll();

    /** 在途数（LEASED + RUNNING）。 */
    @Select("""
            SELECT COUNT(*) FROM wgh_execution_job
            WHERE status IN ('LEASED', 'RUNNING') AND deleted = 0
            """)
    long countInFlight();

    /** 失败数（可重试的 FAILED）。 */
    @Select("SELECT COUNT(*) FROM wgh_execution_job WHERE status = 'FAILED' AND deleted = 0")
    long countFailedAll();

    /** 死信数。 */
    @Select("SELECT COUNT(*) FROM wgh_execution_job WHERE status = 'DEAD' AND deleted = 0")
    long countDeadAll();

    /** 超时或租约过期数：租约已过期未回收，或已落超时/租约丢失错误码。 */
    @Select("""
            SELECT COUNT(*) FROM wgh_execution_job
            WHERE deleted = 0 AND (
                (status IN ('LEASED', 'RUNNING') AND lease_expire_time IS NOT NULL AND lease_expire_time < now())
                OR error_code IN ('LEASE_LOST', 'JOB_QUEUE_TIMEOUT', 'EXECUTION_TIMEOUT')
            )
            """)
    long countTimeoutOrLeaseExpired();
}
