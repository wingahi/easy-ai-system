package com.wgh.rag.auth.service;

import com.wgh.rag.common.entity.AuditLog;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.common.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 账号安全审计（显式构造审计内容，绝不把含密码的 DTO 交给 {@code AuditAspect} 的 toString）。
 * <p>
 * 详情只记录 目标账号 / 操作者 / 结果（可选原因），不含任何口令明文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAuditService {

    public static final String RESOURCE_TYPE = "ACCOUNT";

    public static final String ACTION_LOGIN_SUCCESS = "ACCOUNT_LOGIN_SUCCESS";
    public static final String ACTION_LOGIN_FAIL = "ACCOUNT_LOGIN_FAIL";
    public static final String ACTION_CREATE = "ACCOUNT_CREATE";
    public static final String ACTION_ROLE_CHANGE = "ACCOUNT_ROLE_CHANGE";
    public static final String ACTION_ENABLE = "ACCOUNT_ENABLE";
    public static final String ACTION_DISABLE = "ACCOUNT_DISABLE";
    public static final String ACTION_PASSWORD_CHANGE = "ACCOUNT_PASSWORD_CHANGE";
    public static final String ACTION_PASSWORD_RESET = "ACCOUNT_PASSWORD_RESET";

    private final AuditLogService auditLogService;

    /**
     * 记录一次账号事件
     *
     * @param action         事件类型
     * @param targetUsername 目标账号
     * @param operator       操作者（登录场景可为 anonymous）
     * @param status         SUCCESS / FAIL
     * @param reason         失败原因摘要（不得包含口令）
     */
    public void record(String action, String targetUsername, String operator, String status, String reason) {
        try {
            StringBuilder detail = new StringBuilder()
                    .append("target=").append(targetUsername)
                    .append(", operator=").append(operator == null ? SecurityUtils.ANONYMOUS : operator)
                    .append(", result=").append(status);
            if (reason != null && !reason.isBlank()) {
                detail.append(", reason=").append(reason.length() > 100 ? reason.substring(0, 100) : reason);
            }
            AuditLog entry = new AuditLog();
            entry.setUsername(operator == null ? SecurityUtils.ANONYMOUS : operator);
            entry.setAction(action);
            entry.setResourceType(RESOURCE_TYPE);
            entry.setResourceId(targetUsername);
            entry.setDetail(detail.toString());
            entry.setIp(currentIp());
            entry.setStatus(status);
            auditLogService.saveNow(entry);
        } catch (Exception e) {
            log.warn("账号审计记录构造失败: action={}, reason={}", action, e.getMessage());
        }
    }

    public void success(String action, String targetUsername) {
        record(action, targetUsername, SecurityUtils.currentUsername(), "SUCCESS", null);
    }

    public void failure(String action, String targetUsername, String reason) {
        record(action, targetUsername, SecurityUtils.currentUsername(), "FAIL", reason);
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
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
}
