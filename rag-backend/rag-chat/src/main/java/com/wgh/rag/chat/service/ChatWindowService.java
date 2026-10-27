package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.mapper.ChatWindowMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 聊天窗口管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatWindowService {

    private final ChatWindowMapper windowMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    public PageResult<ChatWindow> page(Long page, Long size, String keyword) {
        LambdaQueryWrapper<ChatWindow> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ChatWindow::getName, keyword)
                    .or().like(ChatWindow::getDescription, keyword);
        }
        wrapper.orderByDesc(ChatWindow::getCreateTime);
        Page<ChatWindow> result = windowMapper.selectPage(Page.of(page, size), wrapper);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public ChatWindow getById(Long id) {
        ChatWindow window = windowMapper.selectById(id);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");
        return window;
    }

    @Transactional
    public ChatWindow create(ChatWindow window) {
        // 生成 share token
        window.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        window.setShareEnabled(false);
        window.setStatus(window.getStatus() != null ? window.getStatus() : 1);

        if (window.getMaxHistoryMessages() == null) window.setMaxHistoryMessages(20);
        if (window.getTemperature() == null) window.setTemperature(0.7);
        if (window.getEvaluationScheme() == null || window.getEvaluationScheme().isBlank()) {
            window.setEvaluationScheme("NONE");
        }

        windowMapper.insert(window);
        return window;
    }

    @Transactional
    public ChatWindow update(ChatWindow window) {
        ChatWindow existing = windowMapper.selectById(window.getId());
        if (existing == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");

        windowMapper.updateById(window);
        return window;
    }

    /**
     * 软删除
     */
    @Transactional
    public void delete(Long id) {
        ChatWindow window = windowMapper.selectById(id);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");
        // 删除时强制关闭分享
        window.setShareEnabled(false);
        windowMapper.updateById(window);
        windowMapper.deleteById(id);
    }

    /**
     * 停用窗口（停用后分享链接也不可访问）
     */
    @Transactional
    public void disable(Long id) {
        ChatWindow window = windowMapper.selectById(id);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");
        window.setStatus(0);
        window.setShareEnabled(false);
        windowMapper.updateById(window);
    }

    /**
     * 启用窗口
     */
    @Transactional
    public void enable(Long id) {
        ChatWindow window = windowMapper.selectById(id);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");
        window.setStatus(1);
        windowMapper.updateById(window);
    }

    /**
     * 启用分享
     */
    @Transactional
    public ChatWindow enableShare(Long id) {
        ChatWindow window = windowMapper.selectById(id);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");
        if (window.getStatus() == 0) throw new BusinessException("窗口已停用，无法启用分享");

        window.setShareEnabled(true);
        if (window.getShareToken() == null) {
            window.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        }
        windowMapper.updateById(window);
        return window;
    }

    /**
     * 禁用分享
     */
    @Transactional
    public ChatWindow disableShare(Long id) {
        ChatWindow window = windowMapper.selectById(id);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");
        window.setShareEnabled(false);
        windowMapper.updateById(window);
        return window;
    }

    /**
     * 重新生成分享链接 —— 把 shareToken 改为新生成的 UUID，旧链接立即失效。
     * 历史会话与消息关联的是 chat_window_id，不受 token 更换影响，会随新链接正常展示。
     */
    @Transactional
    public ChatWindow regenerateShareToken(Long id) {
        ChatWindow window = windowMapper.selectById(id);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");
        if (!Boolean.TRUE.equals(window.getShareEnabled())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "窗口未开启分享，无法重新生成链接");
        }
        window.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        windowMapper.updateById(window);
        return window;
    }

    /**
     * 根据 shareToken 查找（分享页面用）
     */
    public ChatWindow getByShareToken(String shareToken) {
        ChatWindow w = windowMapper.selectOne(new LambdaQueryWrapper<ChatWindow>()
                .eq(ChatWindow::getShareToken, shareToken)
                .eq(ChatWindow::getShareEnabled, 1)
                .eq(ChatWindow::getStatus, 1));
        if (w == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "分享链接已失效或窗口已关闭分享");
        return w;
    }

    /**
     * 分页查询聊天消息
     * 根据 windowId + userIdentifier 找到会话，返回分页消息（倒序，最新在前）
     */
    public PageResult<ChatMessage> pageMessages(Long windowId, String userIdentifier, Long page, Long size) {
        return pageMessages(windowId, userIdentifier, null, page, size);
    }

    /**
     * 分页查询聊天消息
     * sessionId 为空时取该用户在此窗口下最新的会话；否则加载指定会话的消息
     */
    public PageResult<ChatMessage> pageMessages(Long windowId, String userIdentifier, Long sessionId,
                                                Long page, Long size) {
        // 1. 确定会话：显式指定 sessionId 优先，否则取最新会话
        ChatSession session;
        if (sessionId != null) {
            session = sessionMapper.selectById(sessionId);
            if (session == null || !windowId.equals(session.getChatWindowId())
                    || !userIdentifier.equals(session.getUserIdentifier())) {
                return new PageResult<>(0L, page, size, List.of());
            }
        } else {
            session = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getChatWindowId, windowId)
                    .eq(ChatSession::getUserIdentifier, userIdentifier)
                    .eq(ChatSession::getStatus, "ACTIVE")
                    .orderByDesc(ChatSession::getUpdateTime)
                    .last("LIMIT 1"));
        }

        if (session == null) {
            // 无会话 → 返回空分页
            return new PageResult<>(0L, page, size, List.of());
        }

        // 2. 分页查询消息（按创建时间倒序，最新的在前面）
        Page<ChatMessage> result = messageMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, session.getId())
                        .orderByDesc(ChatMessage::getCreateTime));

        // 3. 反转列表，使最旧的消息在前（符合阅读习惯）
        List<ChatMessage> reversed = new java.util.ArrayList<>(result.getRecords());
        java.util.Collections.reverse(reversed);

        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), reversed);
    }

    /**
     * 列出某窗口下当前用户的会话（按更新时间倒序，最多 50 条）
     */
    public List<ChatSession> listSessions(Long windowId, String userIdentifier) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getChatWindowId, windowId)
                .eq(ChatSession::getUserIdentifier, userIdentifier)
                .eq(ChatSession::getStatus, "ACTIVE")
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT 50"));
    }

    /**
     * 显式新建会话（供页面「新建会话」按钮使用）
     */
    @Transactional
    public ChatSession createSession(Long windowId, String userIdentifier) {
        ChatWindow window = windowMapper.selectById(windowId);
        if (window == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");

        ChatSession session = new ChatSession();
        session.setChatWindowId(windowId);
        session.setTitle("新对话");
        session.setUserIdentifier(userIdentifier);
        session.setPersistent(true);
        session.setMessageCount(0);
        session.setTotalTokens(0);
        session.setStatus("ACTIVE");
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 删除会话（软删除会话，并物理删除其消息 —— 消息随会话生命周期结束）
     * 只允许删除属于自己的会话（userIdentifier 隔离），不存在的会话视为删除成功（幂等）
     */
    @Transactional
    public void deleteSession(Long sessionId, String userIdentifier) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) return;
        if (!userIdentifier.equals(session.getUserIdentifier())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "无权删除他人会话");
        }
        // 物理删除消息，避免大量孤儿消息堆积
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
        // 逻辑删除会话
        sessionMapper.deleteById(sessionId);
    }
}
