package com.tcrm.admin.kollibrary.service;

import com.tcrm.admin.kollibrary.model.LibraryAudienceAge;
import com.tcrm.admin.kollibrary.model.LibraryAudienceGender;
import com.tcrm.admin.kollibrary.model.LibraryCategory;
import com.tcrm.admin.kollibrary.model.LibraryCountry;
import com.tcrm.admin.kollibrary.model.LibraryGrade;
import com.tcrm.admin.kollibrary.model.LibraryLevel;
import com.tcrm.admin.kollibrary.model.LibraryOption;
import com.tcrm.admin.kollibrary.model.LibraryOutreachStatus;
import com.tcrm.admin.kollibrary.model.LibraryPlatform;
import com.tcrm.admin.kollibrary.model.LibrarySampleStatus;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class LibraryKolWorkbook {
  public static final int MAX_ROWS = 200;
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final int HEADER_ROW = 1;
  private final LibraryKolService kols;

  public LibraryKolWorkbook(LibraryKolService kols) {
    this.kols = kols;
  }

  private enum Column {
    USERNAME("达人ID（用户名）", "Creator ID (username)", "demo.creator", true),
    NAME("达人名称（昵称）", "Creator name (nickname)", "Demo Creator", true),
    PLATFORM("所属平台", "Platform", "TIKTOK", true, LibraryPlatform.values()),
    FOLLOWERS("粉丝数", "Followers", "186200", true),
    COUNTRY("国家站点", "Country site", "US", true, LibraryCountry.values()),
    URL("主页链接", "Profile URL", "https://www.tiktok.com/@demo.creator", true),
    CONTACT("联系方式", "Contact", "+1 555 0100"),
    TAGS("自由标签", "Tags", ""),
    REMARK("备注", "Notes", "示例备注"),
    CATEGORIES("内容类目", "Content categories", "BEAUTY_PERSONAL_CARE", LibraryCategory.values()),
    VIEWS("近期平均播放量", "Recent average views", "18500"),
    RATE("互动率（%）", "Engagement rate (%)", "5.60"),
    AUDIENCE_COUNTRY("受众国家/地区", "Audience country or region", "US"),
    AGE("受众年龄段", "Audience age range", "AGE_25_34", LibraryAudienceAge.values()),
    GENDER("受众性别", "Audience gender", "MOSTLY_FEMALE", LibraryAudienceGender.values()),
    OUTREACH("建联状态", "Outreach status", "COOPERATING", LibraryOutreachStatus.values()),
    OWNER("跟进负责人", "Follow-up owner", "Demo Owner", true),
    LAST_CONTACT("最近沟通时间", "Last contact date", "2026-11-06"),
    SAMPLE("寄样状态", "Sample status", "RECEIVED", LibrarySampleStatus.values()),
    TRACKING("寄样单号", "Tracking number", "001234"),
    SAMPLE_DATE("寄样日期", "Sample date", "2026-11-05"),
    FOLLOW_UP("跟进备注", "Follow-up notes", "示例跟进备注"),
    QUALITY("内容质量", "Content quality", "HIGH", LibraryLevel.values()),
    POTENTIAL("带货潜力", "Sales potential", "MEDIUM", LibraryLevel.values()),
    ATTITUDE("配合度", "Cooperation attitude", "HIGH", LibraryLevel.values()),
    GRADE("达人分级", "Creator grade", "A", LibraryGrade.values());

    private final String zh;
    private final String en;
    private final String example;
    private final boolean required;
    private final List<LibraryOption> options;

    Column(String zh, String en, String example, LibraryOption... options) {
      this(zh, en, example, false, options);
    }

    Column(String zh, String en, String example, boolean required, LibraryOption... options) {
      this.zh = zh;
      this.en = en;
      this.example = example;
      this.required = required;
      this.options = List.of(options);
    }

    String label(boolean english) {
      return english ? en : zh;
    }

    String example(boolean english) {
      return options.stream()
          .filter(option -> ((Enum<?>) option).name().equals(example))
          .map(option -> optionLabel(option, english))
          .findFirst()
          .orElse(example);
    }
  }

  // Older downloaded workbooks may still include the optional tags column.
  private static final List<Column> TEMPLATE_COLUMNS =
      Arrays.stream(Column.values()).filter(column -> column != Column.TAGS).toList();

  public byte[] template(boolean english) {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      CellStyle header = headerStyle(workbook, IndexedColors.GREY_25_PERCENT);
      CellStyle required = headerStyle(workbook, IndexedColors.LIGHT_YELLOW);
      CellStyle text = workbook.createCellStyle();
      text.setDataFormat(workbook.createDataFormat().getFormat("@"));
      XSSFSheet input = workbook.createSheet(english ? "Creators" : "达人导入");
      writeHeaders(input, header, required, text, english);
      writeInstruction(
          input,
          english
              ? "Highlighted columns are required. Fill one creator per row starting on row 3. Use the drop-downs or enter multiple content categories separated by commas."
              : "高亮的表头为必填项。请从第 3 行开始填写，每行一位达人。下拉字段请使用下拉菜单，多个内容类目可手动用顿号或逗号分隔。");

      XSSFSheet example = workbook.createSheet(english ? "Example" : "填写示例");
      writeHeaders(example, header, required, text, english);
      writeInstruction(
          example,
          english
              ? "Example only. Fill in Creators. Dates use YYYY-MM-DD. Separate multiple content categories with commas."
              : "示例仅供参考，请在「达人导入」填写。日期格式为 YYYY-MM-DD，多个内容类目用顿号或逗号分隔。");
      XSSFRow exampleRow = example.createRow(HEADER_ROW + 1);
      for (int index = 0; index < TEMPLATE_COLUMNS.size(); index++) {
        exampleRow.createCell(index).setCellValue(TEMPLATE_COLUMNS.get(index).example(english));
      }
      addDropdowns(workbook, input, english);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void addDropdowns(XSSFWorkbook workbook, XSSFSheet input, boolean english) {
    XSSFSheet options = workbook.createSheet("_options");
    var helper = input.getDataValidationHelper();
    for (int index = 0; index < TEMPLATE_COLUMNS.size(); index++) {
      Column column = TEMPLATE_COLUMNS.get(index);
      if (column.options.isEmpty()) {
        continue;
      }
      for (int rowIndex = 0; rowIndex < column.options.size(); rowIndex++) {
        XSSFRow row = options.getRow(rowIndex);
        if (row == null) {
          row = options.createRow(rowIndex);
        }
        row.createCell(index).setCellValue(optionLabel(column.options.get(rowIndex), english));
      }
      var name = workbook.createName();
      name.setNameName("KOL_" + column.name());
      String address = CellReference.convertNumToColString(index);
      name.setRefersToFormula(
          "_options!$" + address + "$1:$" + address + "$" + column.options.size());
      var validation =
          helper.createValidation(
              helper.createFormulaListConstraint(name.getNameName()),
              new CellRangeAddressList(HEADER_ROW + 1, HEADER_ROW + MAX_ROWS, index, index));
      validation.setEmptyCellAllowed(true);
      // The form permits multiple categories. A comma-separated entry is validated on import.
      validation.setShowErrorBox(column != Column.CATEGORIES);
      input.addValidationData(validation);
    }
    workbook.setSheetHidden(workbook.getSheetIndex(options), true);
  }

  private static String optionLabel(LibraryOption option, boolean english) {
    return english ? option.nameEn() : option.nameZh();
  }

  private static CellStyle headerStyle(XSSFWorkbook workbook, IndexedColors color) {
    CellStyle style = workbook.createCellStyle();
    var font = workbook.createFont();
    font.setBold(true);
    style.setFont(font);
    style.setFillForegroundColor(color.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    return style;
  }

  private static void writeInstruction(XSSFSheet sheet, String text) {
    sheet.createRow(0).createCell(0).setCellValue(text);
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 10));
  }

  private static void writeHeaders(
      XSSFSheet sheet, CellStyle header, CellStyle required, CellStyle text, boolean english) {
    sheet.createFreezePane(3, HEADER_ROW + 1);
    XSSFRow row = sheet.createRow(HEADER_ROW);
    row.setHeightInPoints(23);
    for (int index = 0; index < TEMPLATE_COLUMNS.size(); index++) {
      Column col = TEMPLATE_COLUMNS.get(index);
      var cell = row.createCell(index);
      cell.setCellValue(col.label(english));
      cell.setCellStyle(col.required ? required : header);
      sheet.setDefaultColumnStyle(index, text);
      int width = col == Column.URL || col == Column.REMARK || col == Column.FOLLOW_UP ? 38 : 27;
      sheet.setColumnWidth(index, width * 256);
    }
  }

  public ImportResult importFile(String fileName, byte[] bytes, boolean english) {
    List<InputRow> rows = read(fileName, bytes);
    Set<String> keys = new HashSet<>();
    List<RowError> errors = new ArrayList<>();
    int created = 0;
    for (InputRow row : rows) {
      try {
        LibraryKolCommand command = row.command();
        if (Arrays.stream(Column.values())
            .anyMatch(column -> column.required && !row.values.containsKey(column))) {
          throw new BusinessException(ErrorCode.KOL_LIBRARY_IMPORT_REQUIRED);
        }
        String key = command.platform() + "/" + LibraryUsernames.normalize(command.kolUsername());
        if (!keys.add(key)) {
          errors.add(
              new RowError(row.number, ErrorCode.KOL_LIBRARY_IMPORT_DUPLICATE_ROW.name(), null));
        } else {
          kols.create(command);
          created++;
        }
      } catch (InvalidCell e) {
        errors.add(
            new RowError(
                row.number, ErrorCode.COMMON_VALIDATION_FAILED.name(), e.column.label(english)));
      } catch (BusinessException e) {
        errors.add(new RowError(row.number, e.getErrorCode().name(), null));
      }
    }
    return new ImportResult(created, List.copyOf(errors));
  }

  private static List<InputRow> read(String fileName, byte[] bytes) {
    if (bytes.length > MAX_BYTES) {
      throw new BusinessException(ErrorCode.KOL_LIBRARY_IMPORT_FILE_TOO_LARGE);
    }
    if (fileName == null
        || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")
        || bytes.length == 0) {
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      XSSFSheet sheet = workbook.getSheet("达人导入");
      if (sheet == null) {
        sheet = workbook.getSheet("Creators");
      }
      if (sheet == null) {
        throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
      }
      Map<Column, Integer> headers = headers(sheet.getRow(HEADER_ROW));
      List<InputRow> rows = new ArrayList<>();
      for (var raw : sheet) {
        if (raw.getRowNum() <= HEADER_ROW) {
          continue;
        }
        XSSFRow source = (XSSFRow) raw;
        Map<Column, String> cells = new EnumMap<>(Column.class);
        Column invalid = null;
        for (var entry : headers.entrySet()) {
          try {
            String value = readCell(source.getCell(entry.getValue()), entry.getKey());
            if (value != null) {
              cells.put(entry.getKey(), value);
            }
          } catch (InvalidCell e) {
            invalid = e.column;
          }
        }
        if (!cells.isEmpty() || invalid != null) {
          rows.add(new InputRow(raw.getRowNum() + 1, cells, invalid));
        }
        if (rows.size() > MAX_ROWS) {
          throw new BusinessException(ErrorCode.TASK_FILE_TOO_MANY_ROWS);
        }
      }
      if (rows.isEmpty()) {
        throw new BusinessException(ErrorCode.TASK_FILE_EMPTY);
      }
      return rows;
    } catch (BusinessException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new BusinessException(ErrorCode.TASK_FILE_INVALID);
    }
  }

  private static Map<Column, Integer> headers(XSSFRow row) {
    Map<Column, Integer> columns = new EnumMap<>(Column.class);
    if (row != null) {
      for (var cell : row) {
        if (cell.getCellType() == CellType.BLANK) {
          continue;
        }
        String label =
            cell.getCellType() == CellType.STRING ? cell.getStringCellValue().strip() : "";
        if (label.isEmpty() && cell.getCellType() == CellType.STRING) {
          continue;
        }
        Column col =
            Arrays.stream(Column.values())
                .filter(field -> field.zh.equals(label) || field.en.equalsIgnoreCase(label))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH));
        if (columns.putIfAbsent(col, cell.getColumnIndex()) != null) {
          throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
        }
      }
    }
    if (!columns.keySet().containsAll(TEMPLATE_COLUMNS)) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
    return columns;
  }

  private static String readCell(XSSFCell cell, Column col) {
    if (cell == null || cell.getCellType() == CellType.BLANK) {
      return null;
    }
    String value;
    if (cell.getCellType() == CellType.STRING) {
      value = cell.getStringCellValue().strip();
    } else if (cell.getCellType() == CellType.NUMERIC) {
      BigDecimal number = boundedDecimal(cell.getRawValue(), col);
      if ((col == Column.LAST_CONTACT || col == Column.SAMPLE_DATE)
          && DateUtil.isCellDateFormatted(cell)) {
        try {
          var date = cell.getLocalDateTimeCellValue();
          if (date == null) {
            throw new InvalidCell(col);
          }
          value = date.toLocalDate().toString();
        } catch (DateTimeException | ArithmeticException e) {
          throw new InvalidCell(col);
        }
      } else {
        if (col == Column.RATE && cell.getCellStyle().getDataFormatString().contains("%")) {
          number = number.movePointRight(2);
        }
        value = number.stripTrailingZeros().toPlainString();
      }
    } else {
      throw new InvalidCell(col);
    }
    return value.isEmpty() ? null : value;
  }

  private static BigDecimal boundedDecimal(String value, Column column) {
    // XLSX stores numbers as doubles. Bound text length and scale before expanding an exponent.
    if (value == null || value.length() > 64) {
      throw new InvalidCell(column);
    }
    try {
      BigDecimal number = new BigDecimal(value);
      if (number.scale() >= -308 && number.scale() <= 340) {
        return number;
      }
    } catch (NumberFormatException ignored) {
      // The import result identifies the cell rather than rejecting the whole file.
    }
    throw new InvalidCell(column);
  }

  private record InputRow(int number, Map<Column, String> values, Column invalid) {
    private LibraryKolCommand command() {
      if (invalid != null) {
        throw new InvalidCell(invalid);
      }
      String platform = choice(Column.PLATFORM, LibraryPlatform.class);
      if (platform == null) {
        throw new InvalidCell(Column.PLATFORM);
      }
      String username = values.get(Column.USERNAME);
      if (!LibraryUsernames.valid(LibraryUsernames.normalize(username))) {
        throw new InvalidCell(Column.USERNAME);
      }
      List<String> categories = split(Column.CATEGORIES);
      return new LibraryKolCommand(
          platform,
          username,
          values.get(Column.NAME),
          count(Column.FOLLOWERS),
          choice(Column.COUNTRY, LibraryCountry.class),
          values.get(Column.URL),
          categories == null
              ? null
              : categories.stream()
                  .map(value -> option(Column.CATEGORIES, value, LibraryCategory.class))
                  .toList(),
          count(Column.VIEWS),
          rate(),
          values.get(Column.AUDIENCE_COUNTRY),
          choice(Column.AGE, LibraryAudienceAge.class),
          choice(Column.GENDER, LibraryAudienceGender.class),
          values.get(Column.CONTACT),
          choice(Column.QUALITY, LibraryLevel.class),
          choice(Column.POTENTIAL, LibraryLevel.class),
          choice(Column.ATTITUDE, LibraryLevel.class),
          choice(Column.GRADE, LibraryGrade.class),
          split(Column.TAGS),
          values.get(Column.REMARK),
          choice(Column.OUTREACH, LibraryOutreachStatus.class),
          values.get(Column.OWNER),
          date(Column.LAST_CONTACT),
          choice(Column.SAMPLE, LibrarySampleStatus.class),
          values.get(Column.TRACKING),
          date(Column.SAMPLE_DATE),
          values.get(Column.FOLLOW_UP));
    }

    private List<String> split(Column col) {
      String text = values.get(col);
      if (text == null) {
        return null;
      }
      List<String> items =
          Arrays.stream(text.split("[、,，;；]"))
              .map(String::strip)
              .filter(item -> !item.isEmpty())
              .toList();
      if (items.isEmpty()) {
        throw new InvalidCell(col);
      }
      return items;
    }

    private <E extends Enum<E> & LibraryOption> String choice(Column col, Class<E> type) {
      String value = values.get(col);
      return value == null ? null : option(col, value, type);
    }

    private static <E extends Enum<E> & LibraryOption> String option(
        Column col, String value, Class<E> type) {
      return Arrays.stream(type.getEnumConstants())
          .filter(
              item ->
                  item.name().equalsIgnoreCase(value)
                      || item.nameZh().equalsIgnoreCase(value)
                      || item.nameEn().equalsIgnoreCase(value))
          .findFirst()
          .map(Enum::name)
          .orElseThrow(() -> new InvalidCell(col));
    }

    private Long count(Column col) {
      String value = values.get(col);
      if (value == null) {
        return null;
      }
      try {
        long result = boundedDecimal(value.replace(",", ""), col).longValueExact();
        if (result >= 0) {
          return result;
        }
      } catch (ArithmeticException ignored) {
        // Report the field using the same validation path as negative counts.
      }
      throw new InvalidCell(col);
    }

    private BigDecimal rate() {
      String value = values.get(Column.RATE);
      if (value == null) {
        return null;
      }
      BigDecimal rate = boundedDecimal(value.replaceFirst("[%％]$", "").strip(), Column.RATE);
      if (rate.signum() >= 0 && rate.compareTo(BigDecimal.valueOf(100)) <= 0) {
        return rate;
      }
      throw new InvalidCell(Column.RATE);
    }

    private LocalDate date(Column col) {
      String value = values.get(col);
      if (value == null) {
        return null;
      }
      try {
        return LocalDate.parse(value);
      } catch (DateTimeParseException e) {
        throw new InvalidCell(col);
      }
    }
  }

  private static final class InvalidCell extends RuntimeException {
    private final Column column;

    private InvalidCell(Column column) {
      this.column = column;
    }
  }

  public record RowError(int row, String errorCode, String column) {}

  public record ImportResult(int created, List<RowError> errors) {}
}
