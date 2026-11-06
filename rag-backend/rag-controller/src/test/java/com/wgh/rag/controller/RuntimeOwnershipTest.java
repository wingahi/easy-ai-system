package com.wgh.rag.controller;

import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.service.AgentOrchestrator;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.controller.support.RunAccessGuard;
import com.wgh.rag.llm.service.ModelRouter;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.service.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P1 运行态越权回归测试：A 用户不能查询/操作 B 用户的 Agent plan 与 Workflow run。
 */
@ExtendWith(MockitoExtension.class)
class RuntimeOwnershipTest {

    @Mock
    private WorkflowService workflowService;
    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private AgentPlanMapper planMapper;
    @Mock
    private AgentStepMapper stepMapper;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private AgentProperties agentProperties;
    @Mock
    private RagChatService ragChatService;
    @Mock
    private ChatWindowService chatWindowService;
    @Mock
    private ModelRouter modelRouter;

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ==================== Workflow run ====================

    @Test
    void userCannotReadWorkflowRunOfOtherUsersSession() {
        loginAs("user-a", Set.of());
        WorkflowRun run = new WorkflowRun();
        run.setId(99L);
        run.setSessionId(2L);
        when(workflowService.getRun(99L)).thenReturn(run);
        when(chatSessionMapper.selectById(2L)).thenReturn(sessionOwnedBy("user-b"));

        BusinessException ex = catchThrowableOfType(
                () -> runAccessGuard().assertAccessible(99L), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
    }

    @Test
    void ownerCanReadWorkflowRunOfOwnSession() {
        loginAs("user-a", Set.of());
        WorkflowRun run = new WorkflowRun();
        run.setId(99L);
        run.setSessionId(2L);
        when(workflowService.getRun(99L)).thenReturn(run);
        when(chatSessionMapper.selectById(2L)).thenReturn(sessionOwnedBy("user-a"));

        runAccessGuard().assertAccessible(99L);
    }

    @Test
    void userCannotReadOtherUsersDirectRun() {
        loginAs("user-a", Set.of());
        WorkflowRun run = new WorkflowRun();
        run.setId(88L);
        run.setCreateBy("user-b");
        when(workflowService.getRun(88L)).thenReturn(run);

        BusinessException ex = catchThrowableOfType(
                () -> runAccessGuard().assertAccessible(88L), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
    }

    @Test
    void adminCanReadLegacyRunWithoutOwner() {
        loginAs("admin", Set.of(RoleCodes.ADMIN));
        WorkflowRun run = new WorkflowRun();
        run.setId(77L);
        run.setCreateBy("system");
        when(workflowService.getRun(77L)).thenReturn(run);

        runAccessGuard().assertAccessible(77L);
    }

    // ==================== Agent plan ====================

    @Test
    void userCannotReadOtherUsersAgentPlan() {
        loginAs("user-a", Set.of());
        when(planMapper.selectById(5L)).thenReturn(planOwnedBy());
        when(chatWindowService.getOwnedSession(10L, 20L, "user-a"))
                .thenThrow(new BusinessException(3003, "会话不存在或无权访问"));

        BusinessException ex = catchThrowableOfType(
                () -> agentController().getPlanState(5L), BusinessException.class);

        assertThat(ex).isNotNull();
    }

    @Test
    void ownerCanReadOwnAgentPlan() {
        loginAs("user-a", Set.of());
        when(planMapper.selectById(5L)).thenReturn(planOwnedBy());
        ChatSession session = sessionOwnedBy("user-a");
        session.setChatWindowId(10L);
        when(chatWindowService.getOwnedSession(10L, 20L, "user-a")).thenReturn(session);
        when(stepMapper.selectList(any())).thenReturn(List.of());

        Result<java.util.Map<String, Object>> result = agentController().getPlanState(5L);

        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().get("planId")).isEqualTo(5L);
    }

    private RunAccessGuard runAccessGuard() {
        return new RunAccessGuard(workflowService, chatSessionMapper);
    }

    private AgentController agentController() {
        return new AgentController(agentOrchestrator, planMapper, stepMapper, agentProperties,
                ragChatService, chatWindowService, modelRouter);
    }

    private AgentPlan planOwnedBy() {
        AgentPlan plan = new AgentPlan();
        plan.setId(5L);
        plan.setSessionId(20L);
        plan.setChatWindowId(10L);
        plan.setCreateBy("user-b");
        return plan;
    }

    private ChatSession sessionOwnedBy(String userIdentifier) {
        ChatSession session = new ChatSession();
        session.setId(2L);
        session.setChatWindowId(10L);
        session.setUserIdentifier(userIdentifier);
        session.setStatus("ACTIVE");
        return session;
    }

    private void loginAs(String username, Set<String> roles) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setRoles(roles);
        user.setPermissions(Set.of());
        LoginUser.set(user);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        user, null, List.of()));
    }
}
