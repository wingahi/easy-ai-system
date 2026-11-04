package com.wgh.rag.data.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * JSON 列（PostgreSQL JSONB / 未来文本列）↔ Java String。
 * <p>
 * 写入前用 Jackson 校验合法性，把"JSON 必须合法"的约束从数据库（JSONB 隐式校验）上移到应用层，
 * 避免未来换成 TEXT / MySQL JSON 列后写入脏数据。读取时原样返回，不做重排或压缩。
 * <p>
 * 不要全局注册为 String 的通用处理器：这里只通过注解 {@code @MappedJdbcTypes(JdbcType.OTHER)}
 * 覆盖 JSONB 这类 OTHER 类型，普通 VARCHAR 文本列不受影响。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonStringTypeHandler extends BaseTypeHandler<String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        validate(parameter);
        ps.setString(i, parameter);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }

    private static void validate(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON 列不允许空值或空串");
        }
        try {
            OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 列写入值不是合法 JSON: " + e.getOriginalMessage(), e);
        }
    }
}
