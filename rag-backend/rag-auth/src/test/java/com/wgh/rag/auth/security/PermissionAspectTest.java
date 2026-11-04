package com.wgh.rag.auth.security;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.RequiresPermission;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RBAC 切面语义测试：ANY / ALL、未登录、权限为空、方法级优先于类级注解。
 */
@DisplayName("权限切面")
class PermissionAspectTest {

    private final PermissionAspect aspect = new PermissionAspect();

    @AfterEach
    void tearDown() {
        LoginUser.clear();
    }

    @Test
    @DisplayName("ANY：命中任一权限即放行")
    void anyModePassesWithOnePermission() throws Throwable {
        loginAs("alice", "kb:read");

        Object result = aspect.around(joinPoint(SecuredService.class, "readSingle"));

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("ANY：一个都不命中 → 1004")
    void anyModeRejectsWithoutPermission() throws Throwable {
        loginAs("alice", "kb:other");

        assertThatThrownBy(() -> aspect.around(joinPoint(SecuredService.class, "readSingle")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.PERMISSION_DENIED.getCode()))
                .hasMessageContaining("kb:read");
    }

    @Test
    @DisplayName("ALL：权限不全 → 1004")
    void allModeRejectsPartialPermissions() throws Throwable {
        loginAs("alice", "kb:read");

        assertThatThrownBy(() -> aspect.around(joinPoint(SecuredService.class, "writeBoth")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.PERMISSION_DENIED.getCode()));
    }

    @Test
    @DisplayName("ALL：权限齐全 → 放行")
    void allModePassesWithAllPermissions() throws Throwable {
        loginAs("alice", "kb:read", "kb:write");

        assertThat(aspect.around(joinPoint(SecuredService.class, "writeBoth"))).isEqualTo("ok");
    }

    @Test
    @DisplayName("未登录上下文 → 1004")
    void noLoginContextRejected() throws Throwable {
        LoginUser.clear();

        assertThatThrownBy(() -> aspect.around(joinPoint(SecuredService.class, "readSingle")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未登录");
    }

    @Test
    @DisplayName("权限为 null 的登录用户 → 拒绝而不是 NPE")
    void nullPermissionsRejected() throws Throwable {
        LoginUser user = new LoginUser();
        user.setUsername("alice");
        user.setPermissions(null);
        LoginUser.set(user);

        assertThatThrownBy(() -> aspect.around(joinPoint(SecuredService.class, "readSingle")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.PERMISSION_DENIED.getCode()));
    }

    @Test
    @DisplayName("方法无注解时回退到类级注解")
    void classLevelAnnotationApplies() throws Throwable {
        loginAs("alice", "admin:view");

        assertThat(aspect.around(joinPoint(ClassLevelSecuredService.class, "unannotatedMethod")))
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("方法级注解优先于类级注解")
    void methodAnnotationOverridesClassAnnotation() throws Throwable {
        loginAs("alice", "admin:view");

        // 方法声明 kb:write，类声明 admin:view；方法级优先 → 当前权限不足
        assertThatThrownBy(() -> aspect.around(joinPoint(ClassLevelSecuredService.class, "methodAnnotated")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("kb:write");
    }

    // ===== 辅助 =====

    private void loginAs(String username, String... permissions) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        Set<String> perms = new LinkedHashSet<>(Set.of(permissions));
        user.setPermissions(perms);
        LoginUser.set(user);
    }

    private ProceedingJoinPoint joinPoint(Class<?> type, String methodName) throws Throwable {
        Method method = type.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.proceed()).thenReturn("ok");
        return pjp;
    }

    /** 方法级权限声明样例 */
    static class SecuredService {

        @RequiresPermission("kb:read")
        public String readSingle() {
            return "ok";
        }

        @RequiresPermission(value = {"kb:read", "kb:write"}, mode = RequiresPermission.Mode.ALL)
        public String writeBoth() {
            return "ok";
        }
    }

    /** 类级权限声明样例 */
    @RequiresPermission("admin:view")
    static class ClassLevelSecuredService {

        public String unannotatedMethod() {
            return "ok";
        }

        @RequiresPermission("kb:write")
        public String methodAnnotated() {
            return "ok";
        }
    }
}
