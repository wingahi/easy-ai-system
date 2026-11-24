package com.wgh.rag.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.scheduler.entity.ExecutionEventRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行事件 Mapper。
 *
 * <p>重放按 {@code (execution_id, seq)} 唯一索引顺序读取；写入冲突按幂等处理（同一事件重复落库忽略）。
 */
public interface ExecutionEventMapper extends BaseMapper<ExecutionEventRecord> {

    /** 读取指定序号之后的事件，按 seq 升序。 */
    @Select("""
            SELECT id, execution_id, seq, event_type, payload_json, create_time
            FROM wgh_execution_event
            WHERE execution_id = #{executionId} AND seq > #{fromSeq}
            ORDER BY seq ASC
            LIMIT #{limit}
            """)
    List<ExecutionEventRecord> selectAfter(@Param("executionId") String executionId,
                                           @Param("fromSeq") long fromSeq,
                                           @Param("limit") int limit);

    /** 清理终态执行的历史事件，避免事件表无限增长。 */
    @Delete("""
            DELETE FROM wgh_execution_event
            WHERE create_time < #{before}
            """)
    int purgeBefore(@Param("before") LocalDateTime before);
}
