package com.wgh.rag.tool.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.mapper.ToolConfigMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具配置归属与可见性测试。
 */
@ExtendWith(MockitoExtension.class)
class ToolConfigServiceVisibilityTest {

    @Mock
    private ToolConfigMapper toolMapper;

    private ToolConfigService service() {
        return new ToolConfigService(toolMapper);
    }

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        DataScopeContext.clear();
    }

    @Test
    void privateOtherIsInvisibleAsNotFound() {
        loginAs("user-b");
        when(toolMapper.selectOne(any())).thenReturn(tool("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().getVisible(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
    }

    @Test
    void publicOtherIsReadable() {
        loginAs("user-b");
        when(toolMapper.selectOne(any())).thenReturn(tool("user-a", "PUBLIC"));

        assertThat(service().getVisible(1L).getName()).isEqualTo("tool");
    }

    @Test
    void cannotUpdateOthersPublicTool() {
        loginAs("user-b");
        when(toolMapper.selectById(1L)).thenReturn(tool("user-a", "PUBLIC"));
        ToolConfig payload = new ToolConfig();
        payload.setId(1L);

        BusinessException ex = catchThrowableOfType(() -> service().update(payload), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1004);
        verify(toolMapper, never()).updateById(any(ToolConfig.class));
    }

    @Test
    void cannotDeleteOthersPrivateTool() {
        loginAs("user-b");
        when(toolMapper.selectById(1L)).thenReturn(tool("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().delete(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
        verify(toolMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void ownerCanChangeVisibility() {
        loginAs("user-a");
        when(toolMapper.selectOne(any())).thenReturn(tool("user-a", "PRIVATE"));

        ToolConfig updated = service().updateVisibility(1L, "PUBLIC");

        assertThat(updated.getVisibility()).isEqualTo("PUBLIC");
        verify(toolMapper).update(isNull(), any());
    }

    private ToolConfig tool(String owner, String visibility) {
        ToolConfig tool = new ToolConfig();
        tool.setId(1L);
        tool.setName("tool");
        tool.setCreateBy(owner);
        tool.setVisibility(visibility);
        return tool;
    }

    private void loginAs(String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setRoles(Set.of(roles));
        LoginUser.set(user);
    }
}
