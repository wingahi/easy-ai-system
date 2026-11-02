package com.wgh.rag.common.aspect;

import com.wgh.rag.common.annotation.Audit;
import com.wgh.rag.common.entity.AuditLog;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.common.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 审计切面：拦截 @Audit 标注的方法，异步写审计日志，失败不阻断主流程。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogService auditLogService;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint pjp, Audit audit) throws Throwable {
        String status = "SUCCESS";
        String error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            status = "FAIL";
            error = t.getMessage();
            throw t;
        } finally {
            try {
                AuditLog entry = new AuditLog();
                entry.setUsername(SecurityUtils.currentUsername());
                entry.setAction(audit.action());
                entry.setResourceType(audit.resourceType());
                entry.setResourceId(extractResourceId(pjp.getArgs()));
                entry.setDetail(buildDetail(pjp, error));
                entry.setIp(currentIp());
                entry.setStatus(status);
                auditLogService.saveAsync(entry);
            } catch (Exception e) {
                log.warn("审计切面构造日志失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 尽力提取资源 ID：第一个 Long / String 类型参数（通常是 @PathVariable id）
     */
    private String extractResourceId(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Long || arg instanceof Integer) {
                return arg.toString();
            }
        }
        return null;
    }

    private String buildDetail(ProceedingJoinPoint pjp, String error) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String argsSummary = Arrays.stream(pjp.getArgs())
                .map(a -> a == null ? "null" : abbreviate(a.toString(), 100))
                .collect(Collectors.joining(", "));
        String detail = signature.getDeclaringType().getSimpleName() + "#" + signature.getName()
                + "(" + abbreviate(argsSummary, 300) + ")";
        if (error != null) {
            detail += " ERROR=" + abbreviate(error, 150);
        }
        return abbreviate(detail, 500);
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
