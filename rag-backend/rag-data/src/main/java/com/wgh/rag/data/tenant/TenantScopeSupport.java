package com.wgh.rag.data.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.TenantContext;

/**
 * 平台共享资源（scope='platform'）的显式查询条件。
 *
 * <p>仅用于 {@code wgh_prompt_template} / {@code wgh_tool}：这两张表不在
 * {@link TenantTableRegistry} 白名单内（插件不能生成 OR 条件），
 * 由服务层统一追加「本租户 OR 平台共享」。
 */
public final class TenantScopeSupport {

    public static final String SCOPE_PLATFORM = "platform";
    public static final String SCOPE_TENANT = "tenant";

    private TenantScopeSupport() {
    }

    /** 平台管理员保留跨租户视图，与 MyBatis 租户插件口径一致。 */
    public static boolean isPlatformAdmin() {
        LoginUser loginUser = LoginUser.get();
        return loginUser != null && loginUser.getRoles() != null
                && loginUser.getRoles().contains(RoleCodes.ADMIN);
    }

    /** 追加可见范围：租户内自建 + 平台内置共享；平台管理员不加条件。 */
    public static <T> void applyVisibleScope(LambdaQueryWrapper<T> wrapper,
                                             SFunction<T, ?> tenantColumn,
                                             SFunction<T, ?> scopeColumn) {
        if (isPlatformAdmin()) {
            return;
        }
        String tenantId = TenantContext.tenantIdOrDefault();
        wrapper.and(w -> w.eq(tenantColumn, tenantId).or().eq(scopeColumn, SCOPE_PLATFORM));
    }
}
