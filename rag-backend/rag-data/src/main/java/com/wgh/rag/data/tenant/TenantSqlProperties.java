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
}
