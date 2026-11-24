package com.wgh.rag.publicaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.publicaccess.entity.ApiCallLog;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.data.tenant.TenantTransactionTemplate;
import com.wgh.rag.publicaccess.mapper.ApiCallLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 开放 API 调用日志（写入失败不影响主链路）
 *
 * <p>RLS 前提：{@code wgh_api_call_log} 的写入必须在事务内且带 {@code app.tenant_id}。
 * 调用点位于过滤器 finally，此时 {@code TenantContext} 可能已被清理，
 * 因此按 Key 归属租户显式开启独立事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiCallLogService {

    private final ApiCallLogMapper apiCallLogMapper;
    private final ClientIpResolver clientIpResolver;
    private final TenantTransactionTemplate transactions;

    public void record(ApiKey key, HttpServletRequest request, int statusCode, long durationMs) {
        try {
            ApiCallLog logRow = new ApiCallLog();
            logRow.setApiKeyId(key.getId());
            logRow.setApiKeyName(key.getName());
            logRow.setUsername("api_" + key.getId());
            String tenantId = key.getTenantId() == null || key.getTenantId().isBlank()
                    ? TenantContext.tenantIdOrDefault() : key.getTenantId();
            logRow.setTenantId(tenantId);
            logRow.setMethod(request.getMethod());
            logRow.setPath(request.getRequestURI());
            logRow.setStatusCode(statusCode);
            logRow.setDurationMs(durationMs);
            logRow.setIp(clientIpResolver.resolve(request));
            transactions.executeNew(tenantId, () -> apiCallLogMapper.insert(logRow));
        } catch (Exception e) {
            log.warn("API 调用日志写入失败: keyId={}, path={}, reason={}",
                    key.getId(), request.getRequestURI(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<ApiCallLog> recent(Long keyId, int limit) {
        return apiCallLogMapper.selectList(new LambdaQueryWrapper<ApiCallLog>()
                .eq(keyId != null, ApiCallLog::getApiKeyId, keyId)
                .orderByDesc(ApiCallLog::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    @Transactional(readOnly = true)
    public Long countByKey(Long keyId) {
        return apiCallLogMapper.selectCount(new LambdaQueryWrapper<ApiCallLog>()
                .eq(ApiCallLog::getApiKeyId, keyId));
    }
}
