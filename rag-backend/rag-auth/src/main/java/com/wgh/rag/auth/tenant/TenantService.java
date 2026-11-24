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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** 套餐取值（新增值需同步管理端下拉与本文档） */
    private static final Set<String> PLANS = Set.of("standard", "professional", "enterprise");

    /** 隔离模式取值；schema/database 为阶段 4 预留，当前只做取值校验 */
    private static final Set<String> ISOLATION_MODES = Set.of("shared", "schema", "database");

    private final TenantMapper tenantMapper;
    private final TenantProperties properties;

    /** 按编码查询（含已下线）；不存在返回 {@code null}。 */
    public WghTenant findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        return tenantMapper.selectByCodeIncludeDeleted(code.trim());
    }

    /** 按主键查询；不存在抛 404。 */
    public WghTenant requireById(Long id) {
        WghTenant tenant = id == null ? null : tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        return tenant;
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

    /**
     * 校验租户可作为用户归属：必须存在，且未停用、未下线。
     *
     * @return 规范化后的租户编码
     */
    public String requireAssignable(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户编码不能为空");
        }
        WghTenant tenant = findByCode(code);
        if (tenant == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户不存在：" + code.trim());
        }
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户已停用或已下线：" + tenant.getCode());
        }
        return tenant.getCode();
    }

    /** 租户编码 → 名称（含已停用/已下线，用于展示历史归属）。 */
    public Map<String, String> codeNameMap() {
        Map<String, String> names = new LinkedHashMap<>();
        for (WghTenant tenant : tenantMapper.selectAllIncludingDeleted()) {
            names.put(tenant.getCode(), StringUtils.hasText(tenant.getName())
                    ? tenant.getName() : tenant.getCode());
        }
        return names;
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
        validatePlan(normalizedPlan);
        tenant.setPlan(normalizedPlan);
        tenant.setIsolationMode("shared");
        tenantMapper.insert(tenant);
        log.info("创建租户: code={}, name={}", tenant.getCode(), tenant.getName());
        return tenant;
    }

    /**
     * 更新租户基本信息（名称 / 套餐 / 隔离模式）。
     *
     * <p>字段为 {@code null} 表示不修改；空字符串视为非法值，避免把名称改空。
     */
    @Transactional
    public WghTenant update(Long id, String name, String plan, String isolationMode) {
        WghTenant tenant = requireById(id);
        if (name != null) {
            String displayName = name.trim();
            if (displayName.isEmpty() || displayName.length() > 128) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "租户名称不能为空且长度不能超过 128");
            }
            tenant.setName(displayName);
        }
        if (plan != null) {
            String normalizedPlan = plan.trim();
            validatePlan(normalizedPlan);
            tenant.setPlan(normalizedPlan);
        }
        if (isolationMode != null) {
            String normalizedMode = isolationMode.trim().toLowerCase(Locale.ROOT);
            if (!ISOLATION_MODES.contains(normalizedMode)) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "隔离模式仅支持 shared / schema / database");
            }
            tenant.setIsolationMode(normalizedMode);
        }
        tenantMapper.updateById(tenant);
        log.info("更新租户: code={}, plan={}, isolationMode={}",
                tenant.getCode(), tenant.getPlan(), tenant.getIsolationMode());
        return tenant;
    }

    /**
     * 租户下线：置停用并做逻辑删除，保留全部业务数据。
     *
     * <p>默认租户不允许下线；已下线的租户重复调用按幂等处理返回成功。
     */
    @Transactional
    public void offline(Long id) {
        WghTenant tenant = requireById(id);
        if (TenantContext.DEFAULT_TENANT.equals(tenant.getCode())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "默认租户不允许下线");
        }
        if (tenant.getStatus() != null && tenant.getStatus() == 0) {
            return;
        }
        tenant.setStatus(0);
        tenantMapper.updateById(tenant);
        // 逻辑删除：只置 deleted=1，不物理删除任何租户或业务数据
        tenantMapper.deleteById(id);
        log.info("租户下线（逻辑删除）: code={}", tenant.getCode());
    }

    private void validatePlan(String plan) {
        if (!PLANS.contains(plan)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "套餐仅支持 standard / professional / enterprise");
        }
    }

    @Transactional
    public WghTenant updateStatus(Long id, Integer status) {
        WghTenant tenant = requireById(id);
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
            throw new BusinessException(ResultCode.TOKEN_INVALID, "租户已停用或已下线：" + tenantId);
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
