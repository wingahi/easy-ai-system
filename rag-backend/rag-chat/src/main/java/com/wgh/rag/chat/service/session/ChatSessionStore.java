package com.wgh.rag.chat.service.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话生命周期：归属校验、创建、分享态复用与首条消息自动标题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionStore {

    private final ChatSessionMapper sessionMapper;

    /** 按 ID 查询会话（不存在返回 null）。 */
    @Transactional(readOnly = true)
    public ChatSession findById(Long sessionId) {
        return sessionId == null ? null : sessionMapper.selectById(sessionId);
    }

    /** 确保会话存在且归属当前身份；sessionId 为空时复用/创建该窗口下当前身份的会话。 */
    @Transactional
    public ChatSession ensureSession(Long sessionId, ChatWindow window, String userIdentifier) {
        if (sessionId != null) {
            ChatSession session = sessionMapper.selectById(sessionId);
            if (session == null || !window.getId().equals(session.getChatWindowId())
                    || !userIdentifier.equals(session.getUserIdentifier())
                    || !ChatConstants.SESSION_STATUS_ACTIVE.equals(session.getStatus())) {
                throw new BusinessException(ResultCode.PERMISSION_DENIED, "会话不存在或无权访问");
            }
            return session;
        }

        ChatSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getChatWindowId, window.getId())
                .eq(ChatSession::getUserIdentifier, userIdentifier)
                .eq(ChatSession::getStatus, ChatConstants.SESSION_STATUS_ACTIVE)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT 1"));

        return existing != null ? existing : createSession(window, userIdentifier);
    }

    /** 分享态会话：指定会话需校验归属，否则复用该匿名标识最近一次会话。 */
    @Transactional
    public ChatSession resolveShareSession(ChatWindow window, Long sessionId, String userIdentifier) {
        if (sessionId != null) {
            ChatSession target = sessionMapper.selectById(sessionId);
            if (target != null && window.getId().equals(target.getChatWindowId())
                    && userIdentifier.equals(target.getUserIdentifier())
                    && ChatConstants.SESSION_STATUS_ACTIVE.equals(target.getStatus())) {
                return target;
            }
        }

        ChatSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getChatWindowId, window.getId())
                .eq(ChatSession::getUserIdentifier, userIdentifier)
                .eq(ChatSession::getStatus, ChatConstants.SESSION_STATUS_ACTIVE)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT 1"));
        return existing != null ? existing : createSession(window, userIdentifier);
    }

    @Transactional
    public ChatSession createSession(ChatWindow window, String userIdentifier) {
        ChatSession session = new ChatSession();
        session.setChatWindowId(window.getId());
        session.setTitle("新对话");
        session.setUserIdentifier(userIdentifier);
        session.setPersistent(true);
        session.setMessageCount(0);
        session.setTotalTokens(0);
        session.setStatus(ChatConstants.SESSION_STATUS_ACTIVE);
        sessionMapper.insert(session);
        return session;
    }

    /** 首条消息生成会话标题，避免历史会话列表全是「新对话」。 */
    @Transactional
    public void applyAutoTitle(ChatSession session, String userMessage) {
        if (session.getTitle() != null && !"新对话".equals(session.getTitle())) {
            return;
        }
        String title = userMessage == null ? "" : userMessage.trim().replaceAll("\\s+", " ");
        if (title.length() > 20) title = title.substring(0, 20) + "…";
        if (!title.isEmpty()) {
            session.setTitle(title);
            sessionMapper.updateById(session);
        }
    }
}
