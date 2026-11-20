package com.wgh.rag.controller;

import com.wgh.rag.controller.admin.AdminRoleController;
import com.wgh.rag.controller.admin.AdminUserController;
import com.wgh.rag.controller.agent.AgentController;
import com.wgh.rag.controller.agent.MultiAgentController;
import com.wgh.rag.controller.quota.QuotaAdminController;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wgh.rag.agentmulti.entity.MultiAgentCrew;
import com.wgh.rag.auth.entity.WghRole;
import com.wgh.rag.auth.mapper.RoleMapper;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.auth.mapper.UserRoleMapper;
import com.wgh.rag.auth.service.AccountAuditService;
import com.wgh.rag.auth.service.LocalPasswordService;
import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.controller.dto.AdminUserRequest;
import com.wgh.rag.controller.dto.PasswordResetRequest;
import com.wgh.rag.controller.dto.RolePermissionRequest;
import com.wgh.rag.controller.dto.RoleRequest;
import com.wgh.rag.log.annotation.Audit;
import com.wgh.rag.log.aspect.AuditAspect;
import com.wgh.rag.log.constant.AuditConstants;
import com.wgh.rag.log.entity.AuditLog;
import com.wgh.rag.log.service.AuditLogService;
import com.wgh.rag.quota.entity.UserQuotaConfig;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T07 审计覆盖回归测试：
 * 1) 管理类控制器的写接口必须声明 {@code @Audit}，读接口不得滥加；
 * 2) 切面被触发时应按注解写出一条审计记录（动作 / 资源类型 / 结果）；
 * 3) AdminUserController 的账号写操作走 {@link AccountAuditService} 显式审计，
 *    且审计内容不得包含口令明文（因此该控制器刻意不加 {@code @Audit}）。
 */
class AuditCoverageTest {

    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AuditAspect auditAspect = new AuditAspect(auditLogService);

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void roleWriteEndpointsDeclareAuditAndAspectRecordsEntry() throws Throwable {
        assertAudited(AdminRoleController.class, "createRole", new Class<?>[]{RoleRequest.class},
                "ROLE_CREATE", "ROLE", new Object[]{new RoleRequest()});
        assertAudited(AdminRoleController.class, "updateRole", new Class<?>[]{String.class, RoleRequest.class},
                "ROLE_UPDATE", "ROLE", new Object[]{"editor", new RoleRequest()});
        assertAudited(AdminRoleController.class, "deleteRole", new Class<?>[]{String.class},
                "ROLE_DELETE", "ROLE", new Object[]{"editor"});
        assertAudited(AdminRoleController.class, "updateRolePermissions",
                new Class<?>[]{String.class, RolePermissionRequest.class},
                "ROLE_PERMISSION_UPDATE", "ROLE", new Object[]{"editor", new RolePermissionRequest()});

        assertNotAudited(AdminRoleController.class, "listRoles");
        assertNotAudited(AdminRoleController.class, "listPermissions");
    }

    @Test
    void quotaWriteEndpointsDeclareAuditAndAspectRecordsEntry() throws Throwable {
        assertAudited(QuotaAdminController.class, "saveUser",
                new Class<?>[]{String.class, UserQuotaConfig.class},
                "QUOTA_CONFIG_SAVE", "QUOTA", new Object[]{"trial01", new UserQuotaConfig()});
        assertAudited(QuotaAdminController.class, "resetUsage", new Class<?>[]{String.class},
                "QUOTA_USAGE_RESET", "QUOTA", new Object[]{"trial01"});
        assertAudited(QuotaAdminController.class, "resetUser", new Class<?>[]{String.class},
                "QUOTA_CONFIG_RESET", "QUOTA", new Object[]{"trial01"});

        assertNotAudited(QuotaAdminController.class, "listUsers",
                long.class, long.class, String.class);
        assertNotAudited(QuotaAdminController.class, "getUser", String.class);
    }

    @Test
    void agentControlEndpointsDeclareAuditAndAspectRecordsEntry() throws Throwable {
        assertAudited(AgentController.class, "hitlConfirm", new Class<?>[]{Long.class, Map.class},
                "AGENT_STEP_HITL_CONFIRM", "AGENT_STEP", new Object[]{9L, Map.of()});
        assertAudited(AgentController.class, "cancel", new Class<?>[]{Long.class},
                "AGENT_PLAN_CANCEL", "AGENT_PLAN", new Object[]{1L});
        assertAudited(AgentController.class, "retryStep", new Class<?>[]{Long.class},
                "AGENT_STEP_RETRY", "AGENT_STEP", new Object[]{9L});
        assertAudited(AgentController.class, "skipStep", new Class<?>[]{Long.class},
                "AGENT_STEP_SKIP", "AGENT_STEP", new Object[]{9L});

        assertNotAudited(AgentController.class, "getPlanState", Long.class);
        assertNotAudited(AgentController.class, "getPlanBySession", Long.class);
    }

    @Test
    void multiAgentWriteEndpointsDeclareAuditAndAspectRecordsEntry() throws Throwable {
        assertAudited(MultiAgentController.class, "createRun", new Class<?>[]{Map.class},
                "MULTI_AGENT_RUN_CREATE", "MULTI_AGENT_RUN", new Object[]{Map.of("input", "hi")});
        assertAudited(MultiAgentController.class, "hitlConfirm", new Class<?>[]{String.class, Map.class},
                "MULTI_AGENT_RUN_HITL_CONFIRM", "MULTI_AGENT_RUN", new Object[]{"run-1", Map.of()});
        assertAudited(MultiAgentController.class, "cancelRun", new Class<?>[]{String.class},
                "MULTI_AGENT_RUN_CANCEL", "MULTI_AGENT_RUN", new Object[]{"run-1"});
        assertAudited(MultiAgentController.class, "createCrew", new Class<?>[]{MultiAgentCrew.class},
                "MULTI_AGENT_CREW_CREATE", "MULTI_AGENT_CREW", new Object[]{new MultiAgentCrew()});
        assertAudited(MultiAgentController.class, "updateCrew",
                new Class<?>[]{Long.class, MultiAgentCrew.class},
                "MULTI_AGENT_CREW_UPDATE", "MULTI_AGENT_CREW", new Object[]{7L, new MultiAgentCrew()});
        assertAudited(MultiAgentController.class, "deleteCrew", new Class<?>[]{Long.class},
                "MULTI_AGENT_CREW_DELETE", "MULTI_AGENT_CREW", new Object[]{7L});

        assertNotAudited(MultiAgentController.class, "streamRun", String.class);
        assertNotAudited(MultiAgentController.class, "getRunState", String.class);
        assertNotAudited(MultiAgentController.class, "listSteps", String.class);
        assertNotAudited(MultiAgentController.class, "listCrews");
        assertNotAudited(MultiAgentController.class, "getCrew", Long.class);
        assertNotAudited(MultiAgentController.class, "validateCrew", Long.class);
    }

    /**
     * AdminUserController 的账号写操作已由 AccountAuditService 落审计（显式脱敏），
     * 不叠加 @Audit：其入参 DTO 的 toString() 含明文口令，切面会原样写入 detail。
     */
    @Test
    void adminUserCreateIsAuditedWithoutPasswordLeak() throws Throwable {
        initLambdaCache(WghRole.class);
        assertThat(AnnotationUtils.findAnnotation(
                AdminUserController.class.getMethod("create", AdminUserRequest.class), Audit.class))
                .as("AdminUserController#create 不得使用 @Audit（会泄露明文口令）").isNull();
        assertThat(AnnotationUtils.findAnnotation(
                AdminUserController.class.getMethod("resetPassword", String.class, PasswordResetRequest.class),
                Audit.class))
                .as("AdminUserController#resetPassword 不得使用 @Audit（会泄露明文口令）").isNull();

        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        QuotaService quotaService = mock(QuotaService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        LocalPasswordService localPasswordService = mock(LocalPasswordService.class);
        AccountAuditService accountAuditService = new AccountAuditService(auditLogService);

        AdminUserController controller = new AdminUserController(userMapper, userRoleMapper, roleMapper,
                quotaService, new QuotaAdminGuard(), passwordEncoder, localPasswordService, accountAuditService);

        loginAsAdmin();
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$bcrypt-hash");
        when(roleMapper.selectCount(any())).thenReturn(1L);
        when(roleMapper.selectOne(any())).thenReturn(role(3L));
        when(userRoleMapper.selectRoleCodesByUserId(any())).thenReturn(List.of("viewer"));
        when(quotaService.findUserConfig(anyString())).thenReturn(null);

        AdminUserRequest request = new AdminUserRequest();
        request.setUsername("trial07");
        request.setPassword("secretPass123");
        request.setRole("viewer");

        controller.create(request);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).saveNow(captor.capture());
        AuditLog entry = captor.getValue();
        assertThat(entry.getAction()).isEqualTo(AccountAuditService.ACTION_CREATE);
        assertThat(entry.getResourceType()).isEqualTo(AuditConstants.RESOURCE_TYPE_ACCOUNT);
        assertThat(entry.getResourceId()).isEqualTo("trial07");
        assertThat(entry.getUsername()).isEqualTo("admin");
        assertThat(entry.getStatus()).isEqualTo(AuditConstants.STATUS_SUCCESS);
        assertThat(entry.getDetail()).contains("target=trial07").doesNotContain("secretPass123");
    }

    // ==================== 内部辅助 ====================

    private void assertAudited(Class<?> type, String methodName, Class<?>[] parameterTypes,
                               String expectedAction, String expectedResourceType,
                               Object[] args) throws Throwable {
        Method method = type.getMethod(methodName, parameterTypes);
        Audit annotation = AnnotationUtils.findAnnotation(method, Audit.class);
        assertThat(annotation).as("%s#%s 必须声明 @Audit", type.getSimpleName(), methodName).isNotNull();
        assertThat(annotation.action()).as("%s#%s action", type.getSimpleName(), methodName)
                .isEqualTo(expectedAction);
        assertThat(annotation.resourceType()).as("%s#%s resourceType", type.getSimpleName(), methodName)
                .isEqualTo(expectedResourceType);

        clearInvocations(auditLogService);
        auditAspect.around(joinPoint(method, args), annotation);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).saveAsync(captor.capture());
        AuditLog entry = captor.getValue();
        assertThat(entry.getAction()).isEqualTo(expectedAction);
        assertThat(entry.getResourceType()).isEqualTo(expectedResourceType);
        assertThat(entry.getStatus()).isEqualTo(AuditConstants.STATUS_SUCCESS);
        Object firstId = args.length > 0 ? args[0] : null;
        if (firstId instanceof Number number) {
            assertThat(entry.getResourceId()).isEqualTo(number.toString());
        }
    }

    private void assertNotAudited(Class<?> type, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getMethod(methodName, parameterTypes);
        assertThat(AnnotationUtils.findAnnotation(method, Audit.class))
                .as("%s#%s 为读操作，不应声明 @Audit", type.getSimpleName(), methodName).isNull();
    }

    private ProceedingJoinPoint joinPoint(Method method, Object[] args) throws Throwable {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringType()).thenReturn(method.getDeclaringClass());
        when(signature.getName()).thenReturn(method.getName());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(null);
        return pjp;
    }

    private void loginAsAdmin() {
        LoginUser user = new LoginUser();
        user.setUsername("admin");
        user.setRoles(Set.of(RoleCodes.ADMIN));
        user.setPermissions(Set.of());
        LoginUser.set(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private WghRole role(Long id) {
        WghRole role = new WghRole();
        role.setId(id);
        role.setRoleCode("viewer");
        return role;
    }

    /** 单元测试不启动 MyBatis，需手动初始化 Lambda 条件缓存。 */
    private void initLambdaCache(Class<?> entityType) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
    }
}
