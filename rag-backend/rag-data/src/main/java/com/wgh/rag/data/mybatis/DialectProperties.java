package com.wgh.rag.data.mybatis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据库方言配置。
 * <p>
 * 默认 postgresql，与当前部署一致；切换测试库或未来换库时只改配置，不动分页插件代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.db")
public class DialectProperties {

    /**
     * 数据库方言：postgresql（默认）/ h2 / mysql / sqlite / oracle
     */
    private String dialect = "postgresql";

    public SqlDialect resolvedDialect() {
        return SqlDialect.from(dialect);
    }
}
