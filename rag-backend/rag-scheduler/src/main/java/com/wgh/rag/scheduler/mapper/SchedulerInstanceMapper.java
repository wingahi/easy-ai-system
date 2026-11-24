package com.wgh.rag.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.scheduler.entity.SchedulerInstance;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度实例 Mapper。
 *
 * <p>注册与心跳均使用数据库时间（{@code now()}），避免多实例时钟偏差影响存活判定。
 */
public interface SchedulerInstanceMapper extends BaseMapper<SchedulerInstance> {

    /** 进程启动注册：同 instanceId 覆盖启动时间并清除停机标记。 */
    @Update("""
            INSERT INTO wgh_scheduler_instance
                (instance_id, started_at, last_heartbeat, stopped_at, lanes_json, version, create_time, update_time)
            VALUES
                (#{instanceId}, now(), now(), NULL, CAST(#{lanesJson,jdbcType=VARCHAR} AS jsonb), 1, now(), now())
            ON CONFLICT (instance_id) DO UPDATE
            SET started_at = EXCLUDED.started_at,
                last_heartbeat = now(),
                stopped_at = NULL,
                lanes_json = EXCLUDED.lanes_json,
                version = wgh_scheduler_instance.version + 1,
                update_time = now()
            """)
    int register(@Param("instanceId") String instanceId, @Param("lanesJson") String lanesJson);

    /** 周期心跳：只刷新心跳时间与车道快照，不改启动时间。 */
    @Update("""
            UPDATE wgh_scheduler_instance
            SET last_heartbeat = now(),
                stopped_at = NULL,
                lanes_json = CAST(#{lanesJson,jdbcType=VARCHAR} AS jsonb),
                version = version + 1,
                update_time = now()
            WHERE instance_id = #{instanceId}
            """)
    int heartbeat(@Param("instanceId") String instanceId, @Param("lanesJson") String lanesJson);

    /** 标记实例主动下线。 */
    @Update("""
            UPDATE wgh_scheduler_instance
            SET stopped_at = now(),
                last_heartbeat = now(),
                version = version + 1,
                update_time = now()
            WHERE instance_id = #{instanceId} AND stopped_at IS NULL
            """)
    int markStopped(@Param("instanceId") String instanceId);

    /**
     * 失联实例：心跳超过存活阈值且尚未标记下线。
     *
     * <p>排除当前实例，避免自己回收自己。已被标记下线的实例不再返回，
     * 既避免重复扫描，也避免实例恢复心跳前被其它实例反复按「已下线」回收。
     */
    @Select("""
            SELECT * FROM wgh_scheduler_instance
            WHERE instance_id <> #{self}
              AND stopped_at IS NULL
              AND last_heartbeat < now() - make_interval(secs => #{livenessTtlSeconds})
            """)
    List<SchedulerInstance> selectDeadInstances(@Param("self") String self,
                                                @Param("livenessTtlSeconds") long livenessTtlSeconds);

    /** 全部实例（管理端集群视角）。 */
    @Select("SELECT * FROM wgh_scheduler_instance ORDER BY last_heartbeat DESC")
    List<SchedulerInstance> selectAllInstances();

    /** 清理长期未心跳的历史实例行，避免实例表无限增长。 */
    @Update("""
            DELETE FROM wgh_scheduler_instance
            WHERE last_heartbeat < now() - make_interval(secs => #{retentionSeconds})
            """)
    int purgeStale(@Param("retentionSeconds") long retentionSeconds);

    /**
     * 仅供管理端计算「失联时长」时取当前数据库时间。
     *
     * <p>PG 的 {@code now()} 返回 {@code timestamptz}，直接映射 {@link LocalDateTime} 会抛
     * {@code Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime}；
     * 显式 {@code ::timestamp} 按会话时区转成无时区时间，与实例表各 TIMESTAMP 列（同样由 {@code now()} 写入）同口径。
     */
    @Select("SELECT now()::timestamp")
    LocalDateTime now();
}
