package com.wgh.rag.data.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 用租户感知的事务管理器替换 Spring Boot 默认的 {@code DataSourceTransactionManager}。
 *
 * <p>顺序保证：用户配置先于 DataSourceTransactionManagerAutoConfiguration 处理，
 * 且自动配置带 {@code @ConditionalOnMissingBean(TransactionManager.class)}，
 * 因此这里定义的 {@code transactionManager} 会被采用。
 */
@Configuration
public class TenantTransactionConfig {

    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager transactionManager(DataSource dataSource,
                                                         TenantSqlProperties properties) {
        return new TenantAwareDataSourceTransactionManager(dataSource, properties.isGucEnabled());
    }
}
