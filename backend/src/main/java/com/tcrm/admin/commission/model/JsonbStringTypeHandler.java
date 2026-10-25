package com.tcrm.admin.commission.model;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * Binds JSON text to a PostgreSQL JSONB column: sent as an untyped parameter so the server casts
 * it, read back as text. The application validates the JSON before binding. Same binding as the
 * kol/notification/system copies; modules stay independent (spec 01 §3).
 */
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {
  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, String value, JdbcType type)
      throws SQLException {
    ps.setObject(i, value, Types.OTHER);
  }

  @Override
  public String getNullableResult(ResultSet rs, String column) throws SQLException {
    return rs.getString(column);
  }

  @Override
  public String getNullableResult(ResultSet rs, int index) throws SQLException {
    return rs.getString(index);
  }

  @Override
  public String getNullableResult(CallableStatement cs, int index) throws SQLException {
    return cs.getString(index);
  }
}
