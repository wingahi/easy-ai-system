package com.wgh.rag.data.mybatis;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.wgh.rag.data.scope.DataScopeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Plus 配置
 */
@Configuration
@RequiredArgsConstructor
public class MybatisPlusConfig {

    private final DialectProperties dialectProperties;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 数据权限：必须在分页之前，保证 count SQL 同样带上可见性过滤
        interceptor.addInnerInterceptor(new DataScopeInterceptor());
        // 分页插件：方言由 wgh.db.dialect 决定，不再硬编码 PostgreSQL
        interceptor.addInnerInterceptor(
                new PaginationInnerInterceptor(dialectProperties.resolvedDialect().getDbType()));
        return interceptor;
    }
}
