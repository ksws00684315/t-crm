package com.tcrm.admin.shared;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enum handler that keeps list queries alive when the database holds a value the running build does
 * not know yet (an older image after a rollback, #106). Reads of an unknown value return {@code
 * null} plus one WARN naming the column, the raw value and the target enum; reads of {@code null}
 * or empty strings stay {@code null} silently. Writes are strict: the Java enum itself already
 * restricts the stored values, so nothing is weakened on the write path.
 *
 * <p>Registered as {@code mybatis-plus.configuration.default-enum-type-handler}; MyBatis-Plus
 * delegates plain enums (no {@code @EnumValue}, no {@code IEnum}) to this handler.
 */
public class LenientEnumTypeHandler<E extends Enum<E>> extends BaseTypeHandler<E> {
  private static final Logger LOG = LoggerFactory.getLogger(LenientEnumTypeHandler.class);

  private final Class<E> type;

  public LenientEnumTypeHandler(Class<E> type) {
    if (type == null) {
      throw new IllegalArgumentException("Enum type is required");
    }
    this.type = type;
  }

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, E parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, parameter.name());
  }

  @Override
  public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return valueOfLenient(rs.getString(columnName), columnName);
  }

  @Override
  public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return valueOfLenient(rs.getString(columnIndex), "#" + columnIndex);
  }

  @Override
  public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return valueOfLenient(cs.getString(columnIndex), "#" + columnIndex);
  }

  private E valueOfLenient(String raw, String column) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(raw)) {
        return constant;
      }
    }
    LOG.warn(
        "Unknown enum value \"{}\" in column {} for target type {}; returning null"
            + " (the database likely holds a value this build does not know yet)",
        raw,
        column,
        type.getName());
    return null;
  }
}
