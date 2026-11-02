package com.wgh.rag.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 成本统计：按天 + 模型聚合助手消息（token 与成本，cost 为 NULL 的按 0 计）
     */
    @Select("""
            SELECT to_char(create_time, 'YYYY-MM-DD') AS "date",
                   COALESCE(model_name, 'unknown') AS "modelName",
                   COUNT(*) AS "messages",
                   COALESCE(SUM(token_count), 0) AS "tokens",
                   COALESCE(SUM(cost), 0) AS "cost"
            FROM wgh_chat_message
            WHERE role = 'ASSISTANT' AND deleted = 0
              AND create_time >= CAST(#{from} AS DATE)
              AND create_time < CAST(#{to} AS DATE) + INTERVAL '1 day'
            GROUP BY 1, 2
            ORDER BY 1, 2
            """)
    List<Map<String, Object>> costStatsByDayAndModel(@Param("from") String from, @Param("to") String to);
}
