package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link LenientEnumTypeHandler} keeps queries alive when the database stores a value the build
 * does not know (#106): unknown reads become null, writes stay the exact enum name.
 */
@ExtendWith(MockitoExtension.class)
class LenientEnumTypeHandlerTest {
  enum Subject {
    ALPHA,
    BETA
  }

  @Mock ResultSet resultSet;
  @Mock PreparedStatement statement;
  @Mock CallableStatement callableStatement;
  private final JdbcType jdbcType = JdbcType.VARCHAR;

  private final LenientEnumTypeHandler<Subject> handler =
      new LenientEnumTypeHandler<>(Subject.class);

  @Test
  void shouldReturnTheEnum_forAKnownValue() throws SQLException {
    when(resultSet.getString("role")).thenReturn("BETA");
    assertThat(handler.getNullableResult(resultSet, "role")).isEqualTo(Subject.BETA);
  }

  @Test
  void shouldReturnNull_forAnUnknownValue() throws SQLException {
    when(resultSet.getString("role")).thenReturn("FINANCE");
    assertThat(handler.getNullableResult(resultSet, "role")).isNull();
  }

  @Test
  void shouldReturnNull_forNullAndEmptyReads() throws SQLException {
    when(resultSet.getString("role")).thenReturn(null);
    assertThat(handler.getNullableResult(resultSet, "role")).isNull();
    when(resultSet.getString("role")).thenReturn("");
    assertThat(handler.getNullableResult(resultSet, "role")).isNull();
  }

  @Test
  void shouldReturnNull_forAnUnknownValue_byColumnIndexAndCallable() throws SQLException {
    when(resultSet.getString(2)).thenReturn("GAMMA");
    assertThat(handler.getNullableResult(resultSet, 2)).isNull();
    when(callableStatement.getString(3)).thenReturn("GAMMA");
    assertThat(handler.getNullableResult(callableStatement, 3)).isNull();
  }

  @Test
  void shouldWriteTheEnumName_onTheStrictWritePath() throws SQLException {
    handler.setNonNullParameter(statement, 1, Subject.ALPHA, jdbcType);
    verify(statement).setString(1, "ALPHA");
    handler.setParameter(statement, 2, Subject.BETA, jdbcType);
    verify(statement).setString(2, "BETA");
    handler.setParameter(statement, 3, null, jdbcType);
    verify(statement).setNull(3, java.sql.Types.VARCHAR);
  }
}
