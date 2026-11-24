package com.wgh.rag.auth.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.auth.entity.WghTenant;
import com.wgh.rag.auth.mapper.TenantMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 租户目录服务：租户查询、状态校验与平台侧管理。
 * <p>
 * 阶段 1 只提供最小管理能力（列表 / 创建 / 启停）；用户唯一约束仍保持全局唯一，
 * 是否改为 {@code (tenant_id, username)} 待产品拍板。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantProperties properties;

    /** 按编码查询；不存在返回 {@code null}。 */
    public WghTenant findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        return tenantMapper.selectOne(new LambdaQueryWrapper<WghTenant>()
                .eq(WghTenant::getCode, code.trim())
                .last("LIMIT 1"));
    }

    /** 租户是否存在且启用。 */
    public boolean isActive(String code) {
        WghTenant tenant = findByCode(code);
        return tenant != null && tenant.getStatus() != null && tenant.getStatus() == 1;
    }

    /** 列表（平台管理员使用）。 */
    public List<WghTenant> list() {
        return tenantMapper.selectList(new LambdaQueryWrapper<WghTenant>()
                .orderByAsc(WghTenant::getId));
    }

    @Transactional
    public WghTenant create(String code, String name, String plan) {
        String normalized = normalizeCode(code);
        if (findByCode(normalized) != null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户编码已存在：" + normalized);
        }
        WghTenant tenant = new WghTenant();
        tenant.setCode(normalized);
        String displayName = StringUtils.hasText(name) ? name.trim() : normalized;
        if (displayName.length() > 128) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户名称长度不能超过 128");
        }
        tenant.setName(displayName);
        tenant.setStatus(1);
        String normalizedPlan = StringUtils.hasText(plan) ? plan.trim() : "standard";
        if (normalizedPlan.length() > 32) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "套餐标识长度不能超过 32");
        }
        tenant.setPlan(normalizedPlan);
        tenant.setIsolationMode("shared");
        tenantMapper.insert(tenant);
        log.info("创建租户: code={}, name={}", tenant.getCode(), tenant.getName());
        return tenant;
    }

    @Transactional
    public WghTenant updateStatus(Long id, Integer status) {
        WghTenant tenant = id == null ? null : tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户状态仅支持 0/1");
        }
        if (status == 0 && TenantContext.DEFAULT_TENANT.equals(tenant.getCode())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "默认租户不允许停用");
        }
        tenant.setStatus(status);
        tenantMapper.updateById(tenant);
        log.info("更新租户状态: code={}, status={}", tenant.getCode(), status);
        return tenant;
    }

    /**
     * 解析并校验租户。
     * <ul>
     *   <li>租户存在且启用 → 直接使用；</li>
     *   <li>租户存在但停用 → 始终拒绝（与 enforcement 无关，避免停用租户回落到默认租户）；</li>
     *   <li>租户缺失/未知 → 严格模式拒绝，非严格模式回落默认租户。</li>
     * </ul>
     */
    public String resolveOrReject(String requestedTenant) {
        String tenantId = StringUtils.hasText(requestedTenant)
                ? requestedTenant.trim() : TenantContext.DEFAULT_TENANT;
        WghTenant tenant = findByCode(tenantId);
        if (tenant == null) {
            if (properties.isEnforcement()) {
                throw new BusinessException(ResultCode.TOKEN_INVALID, "租户不存在：" + tenantId);
            }
            return TenantContext.DEFAULT_TENANT;
        }
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new BusinessException(ResultCode.TOKEN_INVALID, "租户已停用：" + tenantId);
        }
        return tenantId;
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户编码不能为空");
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 64 || !normalized.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "租户编码仅支持小写字母、数字、下划线与中划线，长度不超过 64");
        }
        return normalized;
    }
}
