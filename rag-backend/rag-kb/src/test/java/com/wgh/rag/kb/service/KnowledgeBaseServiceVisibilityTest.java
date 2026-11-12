package com.wgh.rag.kb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.llm.service.LlmModelService;
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
 * 知识库归属与可见性测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceVisibilityTest {

    @Mock
    private KnowledgeBaseMapper kbMapper;
    @Mock
    private KnowledgeFileMapper fileMapper;
    @Mock
    private LlmModelService llmModelService;
    @Mock
    private VectorStorePort vectorStoreService;
    @Mock
    private SemanticCacheService semanticCacheService;

    private KnowledgeBaseService service() {
        return new KnowledgeBaseService(kbMapper, fileMapper, llmModelService,
                vectorStoreService, semanticCacheService);
    }

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        DataScopeContext.clear();
    }

    @Test
    void privateOtherIsInvisibleAsNotFound() {
        loginAs("user-b");
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().getVisible(1L), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(3001);
    }

    @Test
    void publicOtherIsReadable() {
        loginAs("user-b");
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PUBLIC"));

        assertThat(service().getVisible(1L).getName()).isEqualTo("kb");
    }

    @Test
    void adminSeesPrivateOthers() {
        loginAs("admin", RoleCodes.ADMIN);
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PRIVATE"));

        assertThat(service().getVisible(1L).getCreateBy()).isEqualTo("user-a");
    }

    @Test
    void cannotUpdateOthersPublicResource() {
        loginAs("user-b");
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PUBLIC"));
        KnowledgeBase payload = new KnowledgeBase();
        payload.setId(1L);
        payload.setName("changed");

        BusinessException ex = catchThrowableOfType(() -> service().update(payload), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
        verify(kbMapper, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void cannotUpdateOthersPrivateResource() {
        loginAs("user-b");
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PRIVATE"));
        KnowledgeBase payload = new KnowledgeBase();
        payload.setId(1L);

        BusinessException ex = catchThrowableOfType(() -> service().update(payload), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
    }

    @Test
    void cannotDeleteOthersPrivateResource() {
        loginAs("user-b");
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().delete(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
        verify(kbMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void ownerCanChangeVisibility() {
        loginAs("user-a");
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PRIVATE"));

        KnowledgeBase updated = service().updateVisibility(1L, "public");

        assertThat(updated.getVisibility()).isEqualTo("PUBLIC");
        verify(kbMapper).update(isNull(), any());
    }

    @Test
    void invalidVisibilityIsRejected() {
        loginAs("user-a");
        when(kbMapper.selectById(1L)).thenReturn(kb("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(
                () -> service().updateVisibility(1L, "FRIENDS"), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(2001);
    }

    @Test
    void pageRunsInsideDataScopeAndRestoresAfterwards() {
        loginAs("user-a");
        doAnswer(invocation -> {
            assertThat(DataScopeContext.isActive()).isTrue();
            assertThat(DataScopeContext.current().username()).isEqualTo("user-a");
            return new Page<KnowledgeBase>(1, 10);
        }).when(kbMapper).selectPage(any(), any());

        service().page(1L, 10L, null);

        assertThat(DataScopeContext.isActive()).isFalse();
    }

    private KnowledgeBase kb(String owner, String visibility) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("kb");
        kb.setCreateBy(owner);
        kb.setVisibility(visibility);
        return kb;
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
