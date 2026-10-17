package com.tcrm.admin.integration.service;

import com.tcrm.admin.product.api.SheetCellDTO;
import com.tcrm.admin.product.api.SourceSheetReader;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code spreadsheets.get} with grid data for a few columns of one tab (ADR-021 §3). The {@code
 * fields} mask keeps the response to what link parsing needs: displayed text, the cell link, the
 * formula and rich-text run links.
 */
@Service
public class GoogleSheetsClient implements SourceSheetReader {
  static final String FIELDS =
      "sheets(data(startRow,rowData(values(formattedValue,hyperlink,"
          + "userEnteredValue/formulaValue,textFormatRuns/format/link/uri))))";
  private static final Pattern COLUMN = Pattern.compile("[A-Z]{1,3}");
  private static final Pattern SPREADSHEET_ID = Pattern.compile("[A-Za-z0-9_-]{20,128}");
  private static final Logger LOG = LoggerFactory.getLogger(GoogleSheetsClient.class);

  private final RestTemplate http;
  private final ObjectMapper json;
  private final GoogleCredentialService credentials;
  private final GoogleAccessTokens tokens;
  private final CurrentUserContext current;
  private final String baseUrl;

  public GoogleSheetsClient(
      RestTemplate googleRestTemplate,
      ObjectMapper json,
      GoogleCredentialService credentials,
      GoogleAccessTokens tokens,
      CurrentUserContext current,
      @Value("${crm.google.sheets-base-url}") String baseUrl) {
    this.http = googleRestTemplate;
    this.json = json;
    this.credentials = credentials;
    this.tokens = tokens;
    this.current = current;
    this.baseUrl = baseUrl;
  }

  @Override
  public Map<String, Map<Integer, SheetCellDTO>> readColumns(
      String spreadsheetId, String sheetTitle, int fromRow, int maxRows, List<String> columns) {
    if (spreadsheetId == null
        || !SPREADSHEET_ID.matcher(spreadsheetId).matches()
        || sheetTitle == null
        || sheetTitle.isBlank()
        || fromRow < 1
        || maxRows < 1
        || columns.isEmpty()
        || columns.stream().anyMatch(c -> c == null || !COLUMN.matcher(c).matches())) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    long tenantId = current.user().tenantId();
    GoogleServiceAccount account = credentials.account();
    URI uri = uri(spreadsheetId, sheetTitle, fromRow, fromRow + maxRows, columns);
    String body;
    try {
      body = get(uri, tokens.token(tenantId, account));
    } catch (HttpClientErrorException e) {
      throw translate(e, tenantId);
    } catch (RestClientException e) {
      LOG.warn("Google Sheets request failed: {}", e.getClass().getSimpleName());
      throw new BusinessException(ErrorCode.GOOGLE_API_FAILED);
    }
    return parse(body, columns);
  }

  private String get(URI uri, String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
  }

  /** One A1 range per column, quoted tab name with single quotes doubled (A1 notation). */
  URI uri(String spreadsheetId, String sheetTitle, int fromRow, int toRow, List<String> columns) {
    String tab = "'" + sheetTitle.replace("'", "''") + "'";
    var builder =
        UriComponentsBuilder.fromUriString(baseUrl)
            .path("/v4/spreadsheets/{id}")
            .queryParam("includeGridData", "true")
            .queryParam("fields", "{fields}");
    Map<String, Object> vars = new HashMap<>();
    vars.put("id", spreadsheetId);
    vars.put("fields", FIELDS);
    for (int i = 0; i < columns.size(); i++) {
      String column = columns.get(i);
      builder.queryParam("ranges", "{r" + i + "}");
      vars.put("r" + i, tab + "!" + column + fromRow + ":" + column + toRow);
    }
    return builder.encode().buildAndExpand(vars).toUri();
  }

  private BusinessException translate(HttpClientErrorException e, long tenantId) {
    int status = e.getStatusCode().value();
    LOG.warn("Google Sheets request rejected with HTTP {}", status);
    return switch (status) {
      case 401 -> {
        tokens.evict(tenantId);
        yield new BusinessException(ErrorCode.GOOGLE_CREDENTIAL_INVALID);
      }
      case 403 -> new BusinessException(ErrorCode.GOOGLE_SHEET_FORBIDDEN);
      // 400 "Unable to parse range" is a missing tab; 404 a missing spreadsheet.
      case 400, 404 -> new BusinessException(ErrorCode.GOOGLE_SHEET_NOT_FOUND);
      default -> new BusinessException(ErrorCode.GOOGLE_API_FAILED);
    };
  }

  /** Grid data come back in request order, one entry per range; absent startRow means row 0. */
  Map<String, Map<Integer, SheetCellDTO>> parse(String body, List<String> columns) {
    JsonNode root = body == null ? null : json.readTree(body);
    JsonNode sheets = root == null ? null : root.get("sheets");
    JsonNode data =
        sheets == null || !sheets.isArray() || sheets.isEmpty() ? null : sheets.get(0).get("data");
    if (data == null || !data.isArray() || data.size() != columns.size()) {
      throw new BusinessException(ErrorCode.GOOGLE_API_FAILED);
    }
    Map<String, Map<Integer, SheetCellDTO>> result = new LinkedHashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      JsonNode grid = data.get(i);
      int startRow = grid.has("startRow") ? grid.get("startRow").asInt() : 0;
      Map<Integer, SheetCellDTO> cells = new HashMap<>();
      JsonNode rows = grid.get("rowData");
      if (rows != null && rows.isArray()) {
        for (int r = 0; r < rows.size(); r++) {
          JsonNode values = rows.get(r).get("values");
          if (values == null || !values.isArray() || values.isEmpty()) {
            continue;
          }
          SheetCellDTO cell = cell(values.get(0));
          if (cell != null) {
            cells.put(startRow + r + 1, cell);
          }
        }
      }
      result.put(columns.get(i), cells);
    }
    return result;
  }

  private static SheetCellDTO cell(JsonNode value) {
    String text = string(value.get("formattedValue"));
    String link = string(value.get("hyperlink"));
    JsonNode entered = value.get("userEnteredValue");
    String formula = entered == null ? null : string(entered.get("formulaValue"));
    List<String> runLinks = new ArrayList<>();
    JsonNode runs = value.get("textFormatRuns");
    if (runs != null && runs.isArray()) {
      for (JsonNode run : runs) {
        JsonNode format = run.get("format");
        JsonNode linkNode = format == null ? null : format.get("link");
        String uri = linkNode == null ? null : string(linkNode.get("uri"));
        if (uri != null) {
          runLinks.add(uri);
        }
      }
    }
    if (text == null && link == null && formula == null && runLinks.isEmpty()) {
      return null;
    }
    return new SheetCellDTO(text == null ? "" : text, link, formula, List.copyOf(runLinks));
  }

  private static String string(JsonNode node) {
    return node == null || !node.isString() || node.asString().isEmpty() ? null : node.asString();
  }
}
