package com.wgh.rag.chat.service;

import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 反馈闭环服务：助手消息点赞/点踩、窗口满意度统计、差评分页查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final ChatMessageMapper messageMapper;

    /**
     * 提交反馈：feedback 取值 1（赞）/ -1（踩）/ 0（取消）；仅 ASSISTANT 消息可反馈
     */
    public void submitFeedback(Long messageId, Integer feedback, String comment) {
        if (messageId == null) {
            throw new BusinessException("消息 ID 不能为空");
        }
        if (feedback == null || (feedback != 1 && feedback != -1 && feedback != 0)) {
            throw new BusinessException("feedback 取值仅支持 1（赞）/ -1（踩）/ 0（取消）");
        }
        ChatMessage msg = messageMapper.selectById(messageId);
        if (msg == null) {
            throw new BusinessException("消息不存在");
        }
        if (!"ASSISTANT".equals(msg.getRole())) {
            throw new BusinessException("仅助手消息可反馈");
        }
        ChatMessage update = new ChatMessage();
        update.setId(messageId);
        update.setFeedback(feedback);
        // 差评可带补充说明（截断 500 字）；赞/取消清空说明
        update.setFeedbackComment(feedback == -1 && comment != null
                ? comment.substring(0, Math.min(500, comment.length())) : null);
        messageMapper.updateById(update);
        log.info("✓ 消息反馈: id={}, feedback={}", messageId, feedback);
    }

    /**
     * 窗口满意度统计：{likes, dislikes, rate}；rate = 赞 / (赞+踩)，无反馈时为 0
     */
    public Map<String, Object> windowStats(Long windowId) {
        long likes = messageMapper.countFeedbackByWindow(windowId, 1);
        long dislikes = messageMapper.countFeedbackByWindow(windowId, -1);
        double rate = (likes + dislikes) == 0 ? 0.0 : (double) likes / (likes + dislikes);
        Map<String, Object> stats = new HashMap<>();
        stats.put("likes", likes);
        stats.put("dislikes", dislikes);
        stats.put("rate", rate);
        return stats;
    }

    /**
     * 差评分页（运营闭环：差评消息 + 触发回复的用户问题 + 所属窗口）
     */
    public PageResult<Map<String, Object>> dislikePage(long page, long size) {
        long offset = (Math.max(page, 1) - 1) * size;
        return new PageResult<>(messageMapper.dislikeCount(), page, size,
                messageMapper.dislikePage(size, offset));
    }
}
