package com.wgh.rag.data.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;

import java.util.List;
import java.util.Set;

/**
 * 租户行级过滤处理器。
 *
 * <p>取值规则：
 * <ul>
 *   <li>租户只来自 {@link TenantContext}（由可信凭证建立），不读请求参数；</li>
 *   <li>无租户上下文（启动/维护/匿名公开链路）→ 不追加条件，由各入口显式校验；</li>
 *   <li>平台管理员（现有 {@code admin} 角色）→ 不追加条件，保留跨租户运维视图；</li>
 *   <li>仅 {@link TenantTableRegistry} 白名单表参与，平台共享表不受影响。</li>
 * </ul>
 *
 * <p><b>已知风险</b>：当前 admin 即平台管理员，因此所有 admin 均可跨租户读写；
 * 拆分平台管理员与业务管理员后，应把此处改为按角色权限判断。</p>
 */
public class TenantLineHandlerImpl implements TenantLineHandler {

    private static final String TENANT_COLUMN = "tenant_id";

    @Override
    public Expression getTenantId() {
        return new StringValue(TenantContext.tenantIdOrDefault());
    }

    @Override
    public String getTenantIdColumn() {
        return TENANT_COLUMN;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (!TenantTableRegistry.isTenantTable(tableName)) {
            return true;
        }
        if (TenantContext.get() == null) {
            return true;
        }
        LoginUser loginUser = LoginUser.get();
        return loginUser != null && loginUser.getRoles() != null
                && loginUser.getRoles().contains(RoleCodes.ADMIN);
    }

    @Override
    public boolean ignoreInsert(List<Column> columns, String tenantIdColumn) {
        // 显式写入 tenant_id 的语句（如 ApiKeyService）由业务保证归属，避免插件重复加列
        Set<String> names = columns == null ? Set.of()
                : columns.stream()
                        .map(Column::getColumnName)
                        .filter(java.util.Objects::nonNull)
                        .map(name -> name.replace("\"", "").toLowerCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
        return names.contains(tenantIdColumn == null
                ? TENANT_COLUMN : tenantIdColumn.toLowerCase(java.util.Locale.ROOT));
    }
}
