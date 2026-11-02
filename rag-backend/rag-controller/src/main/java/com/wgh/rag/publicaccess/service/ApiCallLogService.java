package com.wgh.rag.publicaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.publicaccess.entity.ApiCallLog;
import com.wgh.rag.publicaccess.entity.ApiKey;
import com.wgh.rag.publicaccess.mapper.ApiCallLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 开放 API 调用日志（写入失败不影响主链路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiCallLogService {

    private final ApiCallLogMapper apiCallLogMapper;

    public void record(ApiKey key, HttpServletRequest request, int statusCode, long durationMs) {
        try {
            ApiCallLog logRow = new ApiCallLog();
            logRow.setApiKeyId(key.getId());
            logRow.setApiKeyName(key.getName());
            logRow.setUsername("api_" + key.getId());
            logRow.setMethod(request.getMethod());
            logRow.setPath(request.getRequestURI());
            logRow.setStatusCode(statusCode);
            logRow.setDurationMs(durationMs);
            logRow.setIp(clientIp(request));
            apiCallLogMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("API 调用日志写入失败: keyId={}, path={}, reason={}",
                    key.getId(), request.getRequestURI(), e.getMessage());
        }
    }

    public List<ApiCallLog> recent(Long keyId, int limit) {
        return apiCallLogMapper.selectList(new LambdaQueryWrapper<ApiCallLog>()
                .eq(keyId != null, ApiCallLog::getApiKeyId, keyId)
                .orderByDesc(ApiCallLog::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    public Long countByKey(Long keyId) {
        return apiCallLogMapper.selectCount(new LambdaQueryWrapper<ApiCallLog>()
                .eq(ApiCallLog::getApiKeyId, keyId));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
