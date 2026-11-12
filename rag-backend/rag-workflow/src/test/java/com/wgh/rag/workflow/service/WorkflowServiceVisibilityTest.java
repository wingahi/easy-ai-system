package com.wgh.rag.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流归属与可见性测试。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceVisibilityTest {

    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private WorkflowVersionMapper versionMapper;
    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private WorkflowNodeRunMapper nodeRunMapper;
    @Mock
    private GraphValidator graphValidator;
    @Mock
    private WorkflowEngine engine;
    @Mock
    private ThreadPoolTaskExecutor workflowExecutor;

    private WorkflowService service() {
        return new WorkflowService(workflowMapper, versionMapper, runMapper, nodeRunMapper,
                graphValidator, engine, new ObjectMapper(), workflowExecutor);
    }

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        DataScopeContext.clear();
    }

    @Test
    void privateOtherIsInvisibleAsNotFound() {
        loginAs("user-b");
        when(workflowMapper.selectById(1L)).thenReturn(workflow("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().getVisible(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
    }

    @Test
    void publicOtherIsReadable() {
        loginAs("user-b");
        when(workflowMapper.selectById(1L)).thenReturn(workflow("user-a", "PUBLIC"));

        assertThat(service().getVisible(1L).getName()).isEqualTo("wf");
    }

    @Test
    void cannotUpdateOthersPublicWorkflow() {
        loginAs("user-b");
        when(workflowMapper.selectById(1L)).thenReturn(workflow("user-a", "PUBLIC"));

        BusinessException ex = catchThrowableOfType(
                () -> service().update(1L, new Workflow()), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1004);
        verify(workflowMapper, never()).updateById(any(Workflow.class));
    }

    @Test
    void cannotDeleteOthersPrivateWorkflow() {
        loginAs("user-b");
        when(workflowMapper.selectById(1L)).thenReturn(workflow("user-a", "PRIVATE"));

        BusinessException ex = catchThrowableOfType(() -> service().delete(1L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3001);
        verify(workflowMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void ownerCanChangeVisibility() {
        loginAs("user-a");
        when(workflowMapper.selectById(1L)).thenReturn(workflow("user-a", "PRIVATE"));

        Workflow updated = service().updateVisibility(1L, "public");

        assertThat(updated.getVisibility()).isEqualTo("PUBLIC");
        verify(workflowMapper).update(isNull(), any());
    }

    private Workflow workflow(String owner, String visibility) {
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        workflow.setName("wf");
        workflow.setStatus(1);
        workflow.setCreateBy(owner);
        workflow.setVisibility(visibility);
        return workflow;
    }

    private void loginAs(String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setRoles(Set.of(roles));
        LoginUser.set(user);
    }
}
