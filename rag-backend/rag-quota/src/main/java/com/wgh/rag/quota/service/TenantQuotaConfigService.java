package com.wgh.rag.quota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.quota.entity.TenantQuotaConfig;
import com.wgh.rag.quota.mapper.TenantQuotaConfigMapper;
import com.wgh.rag.quota.properties.QuotaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户级配额配置读写。
 *
 * <p>只负责配置事实：无记录时返回全局默认值（{@code wgh.rag.quota.*}），
 * 不改变现有用户维度配额的计算与扣费行为。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQuotaConfigService {

    private final TenantQuotaConfigMapper mapper;

    private final QuotaProperties properties;

    @Transactional(readOnly = true)
    public TenantQuotaView get(String tenantId) {
        TenantQuotaConfig config = find(tenantId);
        if (config == null) {
            return new TenantQuotaView(tenantId, false, TenantQuotaConfig.PERIOD_DAY,
                    properties.getChatDailyLimit(), properties.getTokenDailyLimit(),
                    true, null, null);
        }
        return new TenantQuotaView(tenantId, true,
                config.getPeriod() == null ? TenantQuotaConfig.PERIOD_DAY : config.getPeriod(),
                config.getChatLimit() == null ? 0 : config.getChatLimit(),
                config.getTokenLimit() == null ? 0L : config.getTokenLimit(),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getRemark(), config.getUpdateTime());
    }

    @Transactional
    public TenantQuotaView save(String tenantId, String period, Integer chatLimit, Long tokenLimit,
                                Boolean enabled, String remark) {
        String normalizedPeriod = period == null ? TenantQuotaConfig.PERIOD_DAY
                : period.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TenantQuotaConfig.PERIOD_DAY.equals(normalizedPeriod)
                && !TenantQuotaConfig.PERIOD_MONTH.equals(normalizedPeriod)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "配额周期仅支持 DAY / MONTH");
        }
        if (chatLimit != null && chatLimit < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求数上限不能为负数");
        }
        if (tokenLimit != null && tokenLimit < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "token 上限不能为负数");
        }
        if (remark != null && remark.length() > 255) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "备注长度不能超过 255");
        }
        TenantQuotaConfig config = find(tenantId);
        if (config == null) {
            config = new TenantQuotaConfig();
            config.setTenantId(tenantId);
            config.setPeriod(normalizedPeriod);
            config.setChatLimit(chatLimit == null ? properties.getChatDailyLimit() : chatLimit);
            config.setTokenLimit(tokenLimit == null ? properties.getTokenDailyLimit() : tokenLimit);
            config.setEnabled(enabled == null || enabled);
            config.setRemark(remark);
            mapper.insert(config);
        } else {
            config.setPeriod(normalizedPeriod);
            if (chatLimit != null) {
                config.setChatLimit(chatLimit);
            }
            if (tokenLimit != null) {
                config.setTokenLimit(tokenLimit);
            }
            if (enabled != null) {
                config.setEnabled(enabled);
            }
            if (remark != null) {
                config.setRemark(remark);
            }
            mapper.updateById(config);
        }
        log.info("保存租户配额: tenant={}, period={}, chatLimit={}, tokenLimit={}, enabled={}",
                tenantId, normalizedPeriod, config.getChatLimit(), config.getTokenLimit(), config.getEnabled());
        return get(tenantId);
    }

    private TenantQuotaConfig find(String tenantId) {
        return mapper.selectOne(new LambdaQueryWrapper<TenantQuotaConfig>()
                .eq(TenantQuotaConfig::getTenantId, tenantId)
                .last("LIMIT 1"));
    }
}
