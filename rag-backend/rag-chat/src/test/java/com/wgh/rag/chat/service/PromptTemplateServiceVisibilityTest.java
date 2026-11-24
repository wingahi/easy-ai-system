package com.wgh.rag.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.entity.PromptTemplate;
import com.wgh.rag.chat.mapper.PromptTemplateMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.data.scope.DataScopeContext;
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
 * Prompt 模板归属、可见性与内置模板只读约束测试。
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceVisibilityTest {

    @Mock
    private PromptTemplateMapper mapper;

    private PromptTemplateService service() {
        return new PromptTemplateService(mapper, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        DataScopeContext.clear();
    }

    @Test
    void privateOtherIsInvisibleAsNotFound() {
        loginAs("user-b");
        when(mapper.selectOne(any())).thenReturn(template("user-a", "PRIVATE", false));

        BusinessException ex = catchThrowableOfType(() -> service().getVisible(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
    }

    @Test
    void publicOtherIsReadable() {
        loginAs("user-b");
        when(mapper.selectOne(any())).thenReturn(template("user-a", "PUBLIC", false));

        assertThat(service().getVisible(1L).getName()).isEqualTo("tpl");
    }

    @Test
    void nonAdminCannotEditBuiltinTemplate() {
        loginAs("editor");
        when(mapper.selectOne(any())).thenReturn(template("system", "PUBLIC", true));
        PromptTemplate payload = new PromptTemplate();
        payload.setId(1L);
        payload.setName("tpl");
        payload.setContent("new");

        BusinessException ex = catchThrowableOfType(() -> service().update(payload), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1004);
        verify(mapper, never()).updateById(any(PromptTemplate.class));
    }

    @Test
    void builtinVisibilityCannotBeChanged() {
        loginAs("admin", "admin");
        when(mapper.selectOne(any())).thenReturn(template("system", "PUBLIC", true));

        BusinessException ex = catchThrowableOfType(
                () -> service().updateVisibility(1L, "PRIVATE"), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3003);
    }

    @Test
    void ownerCanChangeVisibility() {
        loginAs("user-a");
        when(mapper.selectOne(any())).thenReturn(template("user-a", "PRIVATE", false));

        PromptTemplate updated = service().updateVisibility(1L, "team");

        assertThat(updated.getVisibility()).isEqualTo("TEAM");
        verify(mapper).update(isNull(), any());
    }

    @Test
    void createDefaultsToPrivate() {
        loginAs("user-a");
        when(mapper.selectOne(any())).thenReturn(null);
        PromptTemplate payload = new PromptTemplate();
        payload.setName("new-tpl");
        payload.setContent("你好 {{name}}");

        PromptTemplate created = service().create(payload);

        assertThat(created.getVisibility()).isEqualTo("PRIVATE");
        assertThat(created.getBuiltin()).isFalse();
    }

    private PromptTemplate template(String owner, String visibility, boolean builtin) {
        PromptTemplate template = new PromptTemplate();
        template.setId(1L);
        template.setName("tpl");
        template.setContent("hi");
        template.setVersion(1);
        template.setCreateBy(owner);
        template.setVisibility(visibility);
        template.setBuiltin(builtin);
        return template;
    }

    private void loginAs(String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setRoles(Set.of(roles));
        LoginUser.set(user);
    }
}
