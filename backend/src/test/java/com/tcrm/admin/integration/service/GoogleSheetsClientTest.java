package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tcrm.admin.product.api.SheetCellDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class GoogleSheetsClientTest {
  private static final String SHEET = "1utim6wvKOCcQf1Qgvzba2Y7bQ79F2T5lqguJz-ODf1E";
  private final RestTemplate http = new RestTemplate();
  private final GoogleCredentialService credentials = mock(GoogleCredentialService.class);
  private final GoogleAccessTokens tokens = mock(GoogleAccessTokens.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final GoogleServiceAccount account =
      new GoogleServiceAccount(
          GoogleTestKeys.EMAIL, GoogleTestKeys.KEY_ID, GoogleTestKeys.pair().getPrivate());
  private MockRestServiceServer server;
  private GoogleSheetsClient client;

  @BeforeEach
  void setUp() {
    server = MockRestServiceServer.bindTo(http).build();
    client =
        new GoogleSheetsClient(
            http, new ObjectMapper(), credentials, tokens, current, "https://sheets.test");
    when(current.user())
        .thenReturn(
            new AccountDTO(
                9L,
                "admin",
                null,
                "Admin",
                null,
                Role.SUPER_ADMIN,
                DataScope.ALL,
                1,
                null,
                null,
                0,
                7L,
                "Co",
                true,
                Set.of(),
                null));
    when(credentials.account()).thenReturn(account);
    when(tokens.token(7L, account)).thenReturn("ya29.test");
  }

  @Test
  void shouldRequestOneQuotedRangePerColumn_withTheFieldMask() {
    URI uri = client.uri(SHEET, "Bob's style", 3, 50_003, List.of("E", "B"));

    assertThat(uri.getRawQuery())
        .contains("includeGridData=true")
        .contains("ranges=%27Bob%27%27s%20style%27%21E3%3AE50003")
        .contains("ranges=%27Bob%27%27s%20style%27%21B3%3AB50003")
        .contains("fields=sheets%28data%28startRow%2CrowData");
    assertThat(uri.getPath()).isEqualTo("/v4/spreadsheets/" + SHEET);
  }

  @Test
  void shouldMapGridDataToRowNumbers_perColumn() {
    String body =
        """
        {"sheets":[{"data":[
          {"startRow":2,"rowData":[
            {"values":[{"formattedValue":"LINK","hyperlink":"https://a.test/1"}]},
            {},
            {"values":[{"formattedValue":"LINK",
              "textFormatRuns":[{"startIndex":0},{"format":{"link":{"uri":"https://b.test/2"}}}]}]},
            {"values":[{"formattedValue":"LINK",
              "userEnteredValue":{"formulaValue":"=HYPERLINK(\\"https://c.test\\")"}}]}
          ]},
          {"rowData":[{"values":[{"formattedValue":"Hoodie"}]},{"values":[{}]}]}
        ]}]}
        """;
    server
        .expect(requestTo(org.hamcrest.Matchers.startsWith("https://sheets.test/v4/spreadsheets/")))
        .andExpect(header("Authorization", "Bearer ya29.test"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    Map<String, Map<Integer, SheetCellDTO>> cells =
        client.readColumns(SHEET, "main", 3, 100, List.of("E", "B"));

    assertThat(cells.get("E"))
        .containsOnlyKeys(3, 5, 6)
        .containsEntry(3, new SheetCellDTO("LINK", "https://a.test/1", null, List.of()))
        .containsEntry(5, new SheetCellDTO("LINK", null, null, List.of("https://b.test/2")))
        .containsEntry(
            6, new SheetCellDTO("LINK", null, "=HYPERLINK(\"https://c.test\")", List.of()));
    // No startRow means the grid starts at row index 0, that is sheet row 1.
    assertThat(cells.get("B")).containsOnlyKeys(1);
  }

  @ParameterizedTest
  @CsvSource({
    "FORBIDDEN, GOOGLE_SHEET_FORBIDDEN",
    "NOT_FOUND, GOOGLE_SHEET_NOT_FOUND",
    "BAD_REQUEST, GOOGLE_SHEET_NOT_FOUND",
    "TOO_MANY_REQUESTS, GOOGLE_API_FAILED",
    "INTERNAL_SERVER_ERROR, GOOGLE_API_FAILED"
  })
  void shouldTranslateGoogleErrors(HttpStatus status, ErrorCode expected) {
    server
        .expect(requestTo(org.hamcrest.Matchers.any(String.class)))
        .andRespond(withStatus(status));

    assertThatThrownBy(() -> client.readColumns(SHEET, "main", 3, 10, List.of("E")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(expected);
  }

  @Test
  void shouldDropTheCachedToken_whenGoogleRejectsIt() {
    server
        .expect(requestTo(org.hamcrest.Matchers.any(String.class)))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> client.readColumns(SHEET, "main", 3, 10, List.of("E")))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
    verify(tokens).evict(7L);
  }

  @Test
  void shouldRejectMalformedInput_beforeCallingGoogle() {
    assertThatThrownBy(() -> client.readColumns("short", "main", 3, 10, List.of("E")))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
    assertThatThrownBy(() -> client.readColumns(SHEET, "main", 3, 10, List.of("e")))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
    assertThatThrownBy(() -> client.readColumns(SHEET, " ", 3, 10, List.of("E")))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
    verify(tokens, org.mockito.Mockito.never()).token(eq(7L), any());
  }

  @Test
  void shouldFail_whenGoogleAnswersWithAnUnexpectedShape() {
    server
        .expect(requestTo(org.hamcrest.Matchers.any(String.class)))
        .andRespond(withSuccess("{\"sheets\":[{\"data\":[]}]}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.readColumns(SHEET, "main", 3, 10, List.of("E")))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.GOOGLE_API_FAILED);
  }
}
