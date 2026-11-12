package com.wgh.rag.chat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.mapper.ChatWindowMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.workflow.service.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天窗口归属与可见性测试。
 */
@ExtendWith(MockitoExtension.class)
class ChatWindowServiceVisibilityTest {

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

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        DataScopeContext.clear();
    }

    @Test
    void privateOtherIsInvisibleAsNotFound() {
        loginAs("user-b");
        when(windowMapper.selectById(1L)).thenReturn(window("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().getVisible(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
    }

    @Test
    void publicOtherIsReadable() {
        loginAs("user-b");
        when(windowMapper.selectById(1L)).thenReturn(window("user-a", "PUBLIC"));

        assertThat(service().getVisible(1L).getName()).isEqualTo("window");
    }

    @Test
    void cannotUpdateOthersPublicWindow() {
        loginAs("user-b");
        when(windowMapper.selectById(1L)).thenReturn(window("user-a", "PUBLIC"));
        ChatWindow payload = new ChatWindow();
        payload.setId(1L);
        payload.setName("changed");

        BusinessException ex = catchThrowableOfType(() -> service().update(payload), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1004);
        verify(windowMapper, never()).updateById(any(ChatWindow.class));
    }

    @Test
    void cannotDeleteOthersPrivateWindow() {
        loginAs("user-b");
        when(windowMapper.selectById(1L)).thenReturn(window("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().delete(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
        verify(windowMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void ownerCanChangeVisibility() {
        loginAs("user-a");
        when(windowMapper.selectById(1L)).thenReturn(window("user-a", "PRIVATE"));

        ChatWindow updated = service().updateVisibility(1L, "TEAM");

        assertThat(updated.getVisibility()).isEqualTo("TEAM");
        verify(windowMapper).update(isNull(), any());
    }

    @Test
    void pageRunsInsideDataScopeAndRestoresAfterwards() {
        loginAs("user-a");
        doAnswer(invocation -> {
            assertThat(DataScopeContext.isActive()).isTrue();
            return new Page<ChatWindow>(1, 10);
        }).when(windowMapper).selectPage(any(), any());

        service().page(1L, 10L, null);

        assertThat(DataScopeContext.isActive()).isFalse();
    }

    private ChatWindow window(String owner, String visibility) {
        ChatWindow window = new ChatWindow();
        window.setId(1L);
        window.setName("window");
        window.setCreateBy(owner);
        window.setVisibility(visibility);
        return window;
    }

    private void loginAs(String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        Set<String> roleSet = new HashSet<>();
        roleSet.addAll(java.util.Arrays.asList(roles));
        user.setRoles(roleSet);
        LoginUser.set(user);
    }
}
