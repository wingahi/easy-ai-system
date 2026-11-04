package com.wgh.rag.controller;

import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.auth.mapper.PermissionMapper;
import com.wgh.rag.auth.mapper.RoleMapper;
import com.wgh.rag.auth.mapper.RolePermissionMapper;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.auth.mapper.UserRoleMapper;
import com.wgh.rag.auth.security.PermissionAspect;
import com.wgh.rag.auth.service.AccountAuditService;
import com.wgh.rag.auth.service.LocalPasswordService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.tool.entity.ToolConfig;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-3 授权覆盖回归测试：
 * 只读账号（viewer）调用管理类接口必须被拒绝（业务码 1004 对应 HTTP 403 语义），
 * 具备管理权限的账号可正常通过；同时校验代表接口确实声明了对应权限码。
 */
class AdminEndpointPermissionTest {

    private final PermissionAspect aspect = new PermissionAspect();

    @AfterEach
    void tearDown() {
        LoginUser.clear();
    }

    @Test
    void viewerIsRejectedOnModelPriceAdminEndpoint() throws Throwable {
        loginAs("viewer", Set.of("kb:read", "agent:use", "workflow:use", "model:read"));

        ProceedingJoinPoint pjp = joinPoint(
                ModelPriceController.class.getMethod("page", long.class, long.class, String.class));

        BusinessException ex = catchThrowableOfType(() -> aspect.around(pjp), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
        verify(pjp, never()).proceed();
    }

    @Test
    void viewerIsRejectedOnApiKeyAdminEndpoint() throws Throwable {
        loginAs("viewer", Set.of("kb:read", "agent:use", "workflow:use", "model:read"));

        ProceedingJoinPoint pjp = joinPoint(
                ApiKeyAdminController.class.getMethod("page", Long.class, Long.class, String.class));

        BusinessException ex = catchThrowableOfType(() -> aspect.around(pjp), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
        verify(pjp, never()).proceed();
    }

    @Test
    void adminWithPermissionPasses() throws Throwable {
        loginAs("admin", Set.of(AuthConstants.PERM_PRICING_MANAGE));

        ProceedingJoinPoint pjp = joinPoint(
                ModelPriceController.class.getMethod("page", long.class, long.class, String.class));
        when(pjp.proceed()).thenReturn(null);

        assertThat(aspect.around(pjp)).isNull();
        verify(pjp).proceed();
    }

    @Test
    void managerEndpointsDeclareExpectedPermissions() throws Throwable {
        assertClassPermission(ApiKeyAdminController.class, AuthConstants.PERM_APIKEY_MANAGE);
        assertClassPermission(ShareLinkAdminController.class, AuthConstants.PERM_SHARE_MANAGE);
        assertClassPermission(ModelPriceController.class, AuthConstants.PERM_PRICING_MANAGE);
        assertClassPermission(AgentController.class, AuthConstants.PERM_AGENT_USE);

        assertMethodPermission(ChatController.class, "pageWindows", AuthConstants.PERM_CHAT_MANAGE,
                Long.class, Long.class, String.class);
        assertMethodPermission(ChatController.class, "deleteWindow", AuthConstants.PERM_CHAT_MANAGE, Long.class);
        assertMethodPermission(ChatController.class, "createTool", AuthConstants.PERM_CHAT_MANAGE, com.wgh.rag.tool.entity.ToolConfig.class);
        assertMethodPermission(WorkflowController.class, "delete", AuthConstants.PERM_WORKFLOW_EDIT, Long.class);
        assertMethodPermission(WorkflowController.class, "run", AuthConstants.PERM_WORKFLOW_USE,
                Long.class, java.util.Map.class);
        assertMethodPermission(PromptTemplateController.class, "delete", AuthConstants.PERM_PROMPT_EDIT, Long.class);
        assertMethodPermission(MultiAgentController.class, "deleteCrew", AuthConstants.PERM_AGENT_MANAGE, Long.class);
    }

    @Test
    void viewerCannotSeeToolExecutorConfig() {
        loginAs("viewer", Set.of(AuthConstants.PERM_TOOL_USE));
        List<ToolConfig> tools = new ArrayList<>(List.of(toolWithExecutorConfig()));

        ChatController.maskExecutorConfig(tools);

        assertThat(tools.get(0).getExecutorConfig()).isNull();
    }

    @Test
    void adminCanSeeToolExecutorConfig() {
        loginAs("admin", Set.of(AuthConstants.PERM_CHAT_MANAGE));
        List<ToolConfig> tools = new ArrayList<>(List.of(toolWithExecutorConfig()));

        ChatController.maskExecutorConfig(tools);

        assertThat(tools.get(0).getExecutorConfig()).isNotNull();
    }

    @Test
    void viewerAdminRolesRejectionMessageIsNotQuotaSpecific() {
        loginAs("viewer", Set.of("kb:read"));
        AdminRoleController controller = new AdminRoleController(
                mock(RoleMapper.class), mock(PermissionMapper.class), mock(RolePermissionMapper.class),
                mock(UserRoleMapper.class), new QuotaAdminGuard());

        BusinessException ex = catchThrowableOfType(() -> controller.listRoles(), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
        assertThat(ex.getMessage()).doesNotContain("配额").contains("无权限");
    }

    @Test
    void viewerAdminUsersRejectionMessageIsNotQuotaSpecific() {
        loginAs("viewer", Set.of("kb:read"));
        AdminUserController controller = new AdminUserController(
                mock(UserMapper.class), mock(UserRoleMapper.class), mock(RoleMapper.class),
                mock(QuotaService.class), new QuotaAdminGuard(), mock(PasswordEncoder.class),
                mock(LocalPasswordService.class), mock(AccountAuditService.class));

        BusinessException ex = catchThrowableOfType(
                () -> controller.list(1, 20, null, null, null, null), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1004);
        assertThat(ex.getMessage()).doesNotContain("配额").contains("无权限");
    }

    private ToolConfig toolWithExecutorConfig() {
        ToolConfig tool = new ToolConfig();
        tool.setExecutorConfig("{\"url\":\"http://internal.example.com\"}");
        return tool;
    }

    private void assertClassPermission(Class<?> type, String expected) {
        RequiresPermission annotation = AnnotationUtils.findAnnotation(type, RequiresPermission.class);
        assertThat(annotation).as("%s 必须声明类级权限注解", type.getSimpleName()).isNotNull();
        assertThat(annotation.value()).contains(expected);
    }

    private void assertMethodPermission(Class<?> type, String methodName, String expected,
                                        Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = type.getMethod(methodName, parameterTypes);
        RequiresPermission annotation = AnnotationUtils.findAnnotation(method, RequiresPermission.class);
        assertThat(annotation).as("%s#%s 必须声明权限注解", type.getSimpleName(), methodName).isNotNull();
        assertThat(annotation.value()).contains(expected);
    }

    private void loginAs(String username, Set<String> permissions) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        user.setPermissions(permissions);
        LoginUser.set(user);
    }

    private ProceedingJoinPoint joinPoint(Method method) {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        return pjp;
    }
}
