package com.wgh.rag.data.mybatis;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
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
        // 分页插件：方言由 wgh.db.dialect 决定，不再硬编码 PostgreSQL
        interceptor.addInnerInterceptor(
                new PaginationInnerInterceptor(dialectProperties.resolvedDialect().getDbType()));
        return interceptor;
    }
}
