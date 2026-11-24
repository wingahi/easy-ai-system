package com.wgh.rag.log.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.data.tenant.TenantTransactionTemplate;
import com.wgh.rag.log.entity.AuditLog;
import com.wgh.rag.log.mapper.AuditLogMapper;
import com.wgh.rag.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志写入与查询。写入异步执行，失败不阻断主流程。
 *
 * <p>RLS 前提：{@code wgh_audit_log} 的写入必须在事务内且带 {@code app.tenant_id}。
 * 异步线程可能已丢失 {@code TenantContext}（或登录审计发生在认证上下文建立之前），
 * 因此这里按审计行自带的 {@code tenant_id} 显式开启独立事务，不依赖线程上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final TenantTransactionTemplate transactions;

    /**
     * 异步写入审计日志（永不抛出）
     */
    @Async
    public void saveAsync(AuditLog auditLog) {
        insertQuietly(auditLog, "审计日志写入失败");
    }

    /**
     * 同步写入审计日志（用于账号安全类事件，保证落库后再返回；永不抛出）
     */
    public void saveNow(AuditLog auditLog) {
        insertQuietly(auditLog, "审计日志写入失败（同步）");
    }

    private void insertQuietly(AuditLog auditLog, String scene) {
        try {
            String tenantId = auditLog.getTenantId() == null || auditLog.getTenantId().isBlank()
                    ? TenantContext.tenantIdOrDefault() : auditLog.getTenantId();
            auditLog.setTenantId(tenantId);
            transactions.executeNew(tenantId, () -> auditLogMapper.insert(auditLog));
        } catch (Exception e) {
            log.warn("{}: action={}, reason={}", scene, auditLog.getAction(), e.getMessage());
        }
    }

    /**
     * 分页查询审计日志（按创建时间倒序）
     */
    @Transactional(readOnly = true)
    public PageResult<AuditLog> page(long page, long size, String username, String action) {
        Page<AuditLog> p = new Page<>(page, size);
        auditLogMapper.selectPage(p, new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLog>()
                .like(username != null && !username.isBlank(), AuditLog::getUsername, username)
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .orderByDesc(AuditLog::getCreateTime));
        return new PageResult<>(p.getTotal(), p.getCurrent(), p.getSize(), p.getRecords());
    }
}
