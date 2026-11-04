package com.wgh.rag.data.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 数据访问方言。
 * <p>
 * 目前业务库按 PostgreSQL 部署，本枚举只负责把配置值映射为 MyBatis-Plus 分页方言，
 * 避免在代码里硬编码 {@link DbType#POSTGRE_SQL}。新增方言时在此补充映射即可。
 */
public enum SqlDialect {

    POSTGRESQL(DbType.POSTGRE_SQL),
    H2(DbType.H2),
    MYSQL(DbType.MYSQL),
    SQLITE(DbType.SQLITE),
    ORACLE(DbType.ORACLE);

    private final DbType dbType;

    SqlDialect(DbType dbType) {
        this.dbType = dbType;
    }

    public DbType getDbType() {
        return dbType;
    }

    /**
     * 解析配置值（大小写不敏感）；空值默认 PostgreSQL。
     */
    public static SqlDialect from(String value) {
        if (value == null || value.isBlank()) {
            return POSTGRESQL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的数据库方言: " + value + "，可选值: "
                    + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")), e);
        }
    }
}
