package com.wgh.rag.data.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 租户 SQL 隔离开关。
 * <p>默认开启；仅在租户列尚未完成迁移、需要紧急回滚时置为 {@code false}。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.tenant")
public class TenantSqlProperties {

    /** 是否由 MyBatis 插件自动为租户表追加 tenant_id 条件 */
    private boolean sqlIsolationEnabled = true;

    /** 是否在事务开始时下发 {@code app.tenant_id}（RLS 前置）；关闭仅用于故障回滚 */
    private boolean gucEnabled = true;

    /** 是否允许在独立事务内切换专用数据库角色（wgh_preauth / wgh_scheduler / wgh_platform） */
    private boolean roleSwitchEnabled = true;

    /** 启动自检发现高权限连接用户时是否直接失败（切到 wgh_app 后应置为 true） */
    private boolean databaseRoleCheckEnforced = false;
}
