package com.wgh.rag.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL SMALLINT(0/1) ↔ Java Boolean
 * MySQL TINYINT(1) JDBC 自动转 boolean；PG SMALLINT 不行，需要显式转换。
 */
@MappedTypes(Boolean.class)
@MappedJdbcTypes(JdbcType.SMALLINT)
public class BooleanToSmallintTypeHandler extends BaseTypeHandler<Boolean> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Boolean parameter, JdbcType jdbcType) throws SQLException {
        ps.setShort(i, parameter ? (short) 1 : (short) 0);
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Short v = rs.getShort(columnName);
        return v == 0 ? false : (v == 1 ? true : null);
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Short v = rs.getShort(columnIndex);
        return v == 0 ? false : (v == 1 ? true : null);
    }

    @Override
    public Boolean getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Short v = cs.getShort(columnIndex);
        return v == 0 ? false : (v == 1 ? true : null);
    }
}
