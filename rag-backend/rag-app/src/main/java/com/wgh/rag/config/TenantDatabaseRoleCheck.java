package com.wgh.rag.config;

import com.wgh.rag.data.tenant.TenantSqlProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动自检：确认应用连接用户满足 RLS 前提。
 *
 * <p>RLS 仅对「非 superuser、无 BYPASSRLS、NOINHERIT」的登录角色生效；
 * 以 postgres 超级用户连接时策略看起来「都建好了」，但对应用完全不可见。
 * 这里在启动时把事实打进日志，避免误判。
 *
 * <p>默认只告警不阻断（dev 仍在用超级用户）；切到 {@code wgh_app} 后把
 * {@code wgh.tenant.database-role-check-enforced} 置为 {@code true}，不满足即启动失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantDatabaseRoleCheck implements ApplicationRunner {

    private static final String ROLE_SQL = """
            SELECT current_user AS username,
                   r.rolsuper AS superuser,
                   r.rolbypassrls AS bypass_rls,
                   r.rolinherit AS inherit
              FROM pg_roles r
             WHERE r.rolname = current_user
            """;

    private final JdbcTemplate jdbcTemplate;

    private final TenantSqlProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(ROLE_SQL);
            String username = String.valueOf(row.get("username"));
            boolean superuser = Boolean.TRUE.equals(row.get("superuser"));
            boolean bypassRls = Boolean.TRUE.equals(row.get("bypass_rls"));
            boolean inherit = Boolean.TRUE.equals(row.get("inherit"));
            if (!superuser && !bypassRls && !inherit) {
                log.info("数据库角色自检通过: current_user={} (非 superuser / 无 bypassrls / NOINHERIT)", username);
                return;
            }
            String message = "数据库角色自检未通过: current_user=" + username
                    + ", rolsuper=" + superuser + ", rolbypassrls=" + bypassRls + ", rolinherit=" + inherit
                    + "；该连接下 PostgreSQL RLS 对应用不可见，租户隔离只剩应用层过滤。";
            if (properties.isDatabaseRoleCheckEnforced()) {
                throw new IllegalStateException(message);
            }
            log.warn("{} 切到 wgh_app 后可把 wgh.tenant.database-role-check-enforced 置为 true 使其阻断启动。", message);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 数据库暂不可用或非 PostgreSQL（单测 H2）时不阻断启动
            log.warn("数据库角色自检跳过（数据库不可用或非 PostgreSQL）: {}", e.getMessage());
        }
    }
}
