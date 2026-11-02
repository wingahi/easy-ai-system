package com.wgh.rag.common.security;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * RBAC 权限校验切面
 * <p>
 * 拦截 {@link RequiresPermission} 注解，从 {@link LoginUser#get()} 取当前登录用户的 permissions，
 * 按注解声明的 value + mode（ANY / ALL）进行比对。无登录上下文或权限不足时抛 {@link BusinessException}。
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Around("@annotation(com.wgh.rag.common.security.RequiresPermission) || @within(com.wgh.rag.common.security.RequiresPermission)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();

        // 方法级注解优先，其次类级注解
        RequiresPermission anno = AnnotationUtils.findAnnotation(method, RequiresPermission.class);
        if (anno == null) {
            anno = AnnotationUtils.findAnnotation(method.getDeclaringClass(), RequiresPermission.class);
        }
        if (anno == null) {
            // 理论上 around 的 pointcut 已经限定，这里兜底
            return pjp.proceed();
        }

        LoginUser loginUser = LoginUser.get();
        if (loginUser == null) {
            throw new BusinessException("无权限访问（未登录）");
        }

        Set<String> userPermissions = loginUser.getPermissions() == null
                ? new HashSet<>()
                : loginUser.getPermissions();

        String[] required = anno.value();
        Set<String> requiredSet = new HashSet<>(Arrays.asList(required));

        boolean pass;
        if (anno.mode() == RequiresPermission.Mode.ALL) {
            pass = userPermissions.containsAll(requiredSet);
        } else {
            // ANY
            pass = requiredSet.stream().anyMatch(userPermissions::contains);
        }

        if (!pass) {
            log.warn("权限校验失败：user={}, required={}, mode={}, has={}",
                    loginUser.getUsername(), required, anno.mode(), userPermissions);
            throw new BusinessException("无权限访问：" + Arrays.toString(required));
        }

        return pjp.proceed();
    }
}
