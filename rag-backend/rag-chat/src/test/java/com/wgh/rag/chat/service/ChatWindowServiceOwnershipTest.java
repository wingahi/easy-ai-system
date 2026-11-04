package com.wgh.rag.chat.service;

import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.mapper.ChatWindowMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.workflow.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-1 会话归属回归测试：A 用户不能读取 / 删除 B 用户的会话。
 */
@ExtendWith(MockitoExtension.class)
class ChatWindowServiceOwnershipTest {

    private static final Long WINDOW_ID = 10L;
    private static final Long SESSION_ID = 20L;

    @Mock
    private ChatWindowMapper windowMapper;
    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private WorkflowService workflowService;

    private ChatWindowService service() {
        return new ChatWindowService(windowMapper, sessionMapper, messageMapper, workflowService);
    }

    @Test
    void userCannotReadOtherUsersSession() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(sessionOwnedBy("user-b"));

        BusinessException ex = catchThrowableOfType(
                () -> service().pageMessages(WINDOW_ID, "user-a", SESSION_ID, 1L, 20L),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
    }

    @Test
    void userCannotDeleteOtherUsersSession() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(sessionOwnedBy("user-b"));

        BusinessException ex = catchThrowableOfType(
                () -> service().deleteSession(WINDOW_ID, SESSION_ID, "user-a"),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
        verify(messageMapper, never()).delete(any());
        verify(sessionMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void ownerCanReadOwnSession() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(sessionOwnedBy("user-a"));
        doReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>())
                .when(messageMapper).selectPage(any(), any());

        PageResult<ChatMessage> result = service().pageMessages(WINDOW_ID, "user-a", SESSION_ID, 1L, 20L);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isEmpty();
    }

    @Test
    void ownerCanDeleteOwnSession() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(sessionOwnedBy("user-a"));

        service().deleteSession(WINDOW_ID, SESSION_ID, "user-a");

        verify(messageMapper).delete(any());
        verify(sessionMapper).deleteById(SESSION_ID);
    }

    private ChatSession sessionOwnedBy(String userIdentifier) {
        ChatSession session = new ChatSession();
        session.setId(SESSION_ID);
        session.setChatWindowId(WINDOW_ID);
        session.setUserIdentifier(userIdentifier);
        session.setStatus("ACTIVE");
        return session;
    }
}
