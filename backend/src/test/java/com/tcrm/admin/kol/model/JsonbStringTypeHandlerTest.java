package com.tcrm.admin.kol.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import org.junit.jupiter.api.Test;

class JsonbStringTypeHandlerTest {
  private final JsonbStringTypeHandler handler = new JsonbStringTypeHandler();

  @Test
  void shouldBindUntypedSoPostgresCastsToJsonb() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    handler.setNonNullParameter(ps, 2, "[]", null);
    verify(ps).setObject(2, "[]", Types.OTHER);
  }

  @Test
  void shouldReadJsonAsText() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("social_accounts")).thenReturn("[]");
    when(rs.getString(3)).thenReturn("[{}]");
    CallableStatement cs = mock(CallableStatement.class);
    when(cs.getString(1)).thenReturn("null");
    assertThat(handler.getNullableResult(rs, "social_accounts")).isEqualTo("[]");
    assertThat(handler.getNullableResult(rs, 3)).isEqualTo("[{}]");
    assertThat(handler.getNullableResult(cs, 1)).isEqualTo("null");
  }
}
