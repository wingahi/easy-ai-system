package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.mapper.ChatWindowMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.service.WorkflowService;
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
    private final WorkflowService workflowService;

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

    /**
     * 查询窗口，不存在（含已软删除）时返回 null。
     * 用于恢复类入口：历史计划对应的窗口可能已删除，不应阻断恢复。
     */
    public ChatWindow findById(Long id) {
        return id == null ? null : windowMapper.selectById(id);
    }

    @Transactional
    public ChatWindow create(ChatWindow window) {
        validateWorkflow(window);

        // 生成 share token
        window.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        window.setShareEnabled(false);
        window.setStatus(window.getStatus() != null ? window.getStatus() : ChatConstants.DEFAULT_STATUS_ENABLED);

        if (window.getMaxHistoryMessages() == null) window.setMaxHistoryMessages(ChatConstants.DEFAULT_MAX_HISTORY_MESSAGES);
        if (window.getTemperature() == null) window.setTemperature(ChatConstants.DEFAULT_TEMPERATURE);
        if (window.getEvaluationScheme() == null || window.getEvaluationScheme().isBlank()) {
            window.setEvaluationScheme(ChatConstants.EVALUATION_SCHEME_NONE);
        }

        windowMapper.insert(window);
        return window;
    }

    @Transactional
    public ChatWindow update(ChatWindow window) {
        ChatWindow existing = windowMapper.selectById(window.getId());
        if (existing == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "聊天窗口不存在");

        validateWorkflow(window);
        windowMapper.updateById(window);

        // MyBatis-Plus updateById 会忽略 null 字段：切换回 RAG/Agent 模式时需显式清空 workflow_id
        if (window.getWorkflowId() == null) {
            windowMapper.update(null, new LambdaUpdateWrapper<ChatWindow>()
                    .eq(ChatWindow::getId, window.getId())
                    .set(ChatWindow::getWorkflowId, null));
        }
        // 同理：前端清空重排序模型下拉时需显式置空 rerank_model_id
        if (window.getRerankModelId() == null) {
            windowMapper.update(null, new LambdaUpdateWrapper<ChatWindow>()
                    .eq(ChatWindow::getId, window.getId())
                    .set(ChatWindow::getRerankModelId, null));
        }
        // 同理：清空查询变换模型下拉时需显式置空 rewrite_model_id
        if (window.getRewriteModelId() == null) {
            windowMapper.update(null, new LambdaUpdateWrapper<ChatWindow>()
                    .eq(ChatWindow::getId, window.getId())
                    .set(ChatWindow::getRewriteModelId, null));
        }
        return window;
    }

    /**
     * 校验绑定的工作流存在且启用；工作流模式下不与 Agent 模式并存。
     */
    private void validateWorkflow(ChatWindow window) {
        if (window.getWorkflowId() == null) return;
        Workflow wf = workflowService.getById(window.getWorkflowId());
        if (wf.getStatus() == null || wf.getStatus() != 1) {
            throw new BusinessException("所选工作流已停用，无法绑定");
        }
        // 工作流模式下每条消息直接运行工作流，不走 Agent 规划链路
        window.setAgentEnabled(0);
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
                throw new BusinessException(ResultCode.PERMISSION_DENIED, "会话不存在或无权访问");
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
     * 校验会话归属（窗口 + 用户），用于工作流运行状态查询与 HITL 确认
     */
    public ChatSession getOwnedSession(Long windowId, Long sessionId, String userIdentifier) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !windowId.equals(session.getChatWindowId())
                || !userIdentifier.equals(session.getUserIdentifier())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "会话不存在或无权访问");
        }
        return session;
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
        deleteSession(null, sessionId, userIdentifier);
    }

    /**
     * 删除会话（限定窗口 + 用户归属校验）；窗口 ID 为空时仅校验用户归属。
     */
    @Transactional
    public void deleteSession(Long windowId, Long sessionId, String userIdentifier) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) return;
        boolean ownerMatches = userIdentifier != null && userIdentifier.equals(session.getUserIdentifier());
        boolean windowMatches = windowId == null || windowId.equals(session.getChatWindowId());
        if (!ownerMatches || !windowMatches) {
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权删除他人会话");
        }
        // 物理删除消息，避免大量孤儿消息堆积
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
        // 逻辑删除会话
        sessionMapper.deleteById(sessionId);
    }
}
