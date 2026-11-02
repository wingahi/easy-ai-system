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

    /**
     * 窗口反馈计数（按 feedback 值统计助手消息）
     */
    @Select("""
            SELECT COUNT(*) FROM wgh_chat_message m
            JOIN wgh_chat_session s ON m.session_id = s.id
            WHERE s.chat_window_id = #{windowId} AND m.deleted = 0 AND m.feedback = #{feedback}
            """)
    long countFeedbackByWindow(@Param("windowId") Long windowId, @Param("feedback") int feedback);

    /**
     * 差评分页：助手消息 + 所属窗口 + 触发该回复的用户问题
     */
    @Select("""
            SELECT m.id AS "id", m.session_id AS "sessionId", s.chat_window_id AS "windowId",
                   w.name AS "windowName", m.content AS "answer", m.feedback_comment AS "feedbackComment",
                   m.create_time AS "createTime",
                   (SELECT um.content FROM wgh_chat_message um
                    WHERE um.session_id = m.session_id AND um.role = 'USER' AND um.deleted = 0
                      AND um.create_time <= m.create_time
                    ORDER BY um.create_time DESC LIMIT 1) AS "userQuery"
            FROM wgh_chat_message m
            JOIN wgh_chat_session s ON m.session_id = s.id
            JOIN wgh_chat_window w ON s.chat_window_id = w.id
            WHERE m.feedback = -1 AND m.deleted = 0
            ORDER BY m.create_time DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<Map<String, Object>> dislikePage(@Param("size") long size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM wgh_chat_message WHERE feedback = -1 AND deleted = 0")
    long dislikeCount();
}
