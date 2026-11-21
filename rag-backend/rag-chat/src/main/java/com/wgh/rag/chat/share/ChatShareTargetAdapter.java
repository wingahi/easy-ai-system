package com.wgh.rag.chat.share;

import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.common.share.ChatShareTargetPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 分享链接聊天域端口实现（由 rag-chat 提供，rag-public-access 只依赖端口接口）。
 */
@Component
@RequiredArgsConstructor
public class ChatShareTargetAdapter implements ChatShareTargetPort {

    private final ChatSessionMapper sessionMapper;
    private final ChatWindowService windowService;

    @Override
    public Optional<SessionTarget> findSession(Long sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(new SessionTarget(session.getChatWindowId(), session.getStatus()));
    }

    @Override
    public boolean windowExists(Long windowId) {
        return windowId != null && windowService.findById(windowId) != null;
    }
}
