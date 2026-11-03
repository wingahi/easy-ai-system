package com.wgh.rag.log.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wgh.rag.log.entity.AuditLog;
import com.wgh.rag.log.mapper.AuditLogMapper;
import com.wgh.rag.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计日志写入与查询。写入异步执行，失败不阻断主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 异步写入审计日志（永不抛出）
     */
    @Async
    public void saveAsync(AuditLog auditLog) {
        try {
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败: action={}, reason={}", auditLog.getAction(), e.getMessage());
        }
    }

    /**
     * 同步写入审计日志（用于账号安全类事件，保证落库后再返回；永不抛出）
     */
    public void saveNow(AuditLog auditLog) {
        try {
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败（同步）: action={}, reason={}", auditLog.getAction(), e.getMessage());
        }
    }

    /**
     * 分页查询审计日志（按创建时间倒序）
     */
    public PageResult<AuditLog> page(long page, long size, String username, String action) {
        Page<AuditLog> p = new Page<>(page, size);
        auditLogMapper.selectPage(p, new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLog>()
                .like(username != null && !username.isBlank(), AuditLog::getUsername, username)
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .orderByDesc(AuditLog::getCreateTime));
        return new PageResult<>(p.getTotal(), p.getCurrent(), p.getSize(), p.getRecords());
    }
}
