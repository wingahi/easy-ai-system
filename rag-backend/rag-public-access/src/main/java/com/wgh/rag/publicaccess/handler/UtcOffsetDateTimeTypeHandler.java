package com.wgh.rag.publicaccess.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * {@link OffsetDateTime} 与 {@code timestamp without time zone} 列之间的 UTC 映射。
 *
 * <p>{@code expires_at} 是 zone-less 的 {@code timestamp}，而 DB 会话时区为 UTC、
 * SQL 侧 {@code now()} 写出的也是 UTC 墙上时间。统一按 UTC 存取后，
 * Java 的 {@link java.time.Instant} 比较与 SQL 构造的数据口径一致，
 * 不再出现「now()+2h 被判为已过期」的 8 小时偏差。</p>
 */
public class UtcOffsetDateTimeTypeHandler extends BaseTypeHandler<OffsetDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, OffsetDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }

    @Override
    public OffsetDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toOffsetDateTime(rs.getObject(columnName, LocalDateTime.class));
    }

    @Override
    public OffsetDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toOffsetDateTime(rs.getObject(columnIndex, LocalDateTime.class));
    }

    @Override
    public OffsetDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toOffsetDateTime(cs.getObject(columnIndex, LocalDateTime.class));
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
