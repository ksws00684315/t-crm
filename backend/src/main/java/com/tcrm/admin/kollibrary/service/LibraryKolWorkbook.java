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
import com.tcrm.admin.kollibrary.model.LibrarySampleType;
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
import java.util.function.IntFunction;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
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
    AGE_SHARE("受众年龄占比（%）", "Audience age share (%)", "20"),
    GENDER(
        "受众性别",
        "Audience gender",
        "MOSTLY_FEMALE",
        LibraryAudienceGender.MOSTLY_FEMALE,
        LibraryAudienceGender.MOSTLY_MALE),
    GENDER_SHARE("受众性别占比（%）", "Audience gender share (%)", "80"),
    OUTREACH("建联状态", "Outreach status", "COOPERATING", LibraryOutreachStatus.values()),
    OWNER("跟进负责人", "Follow-up owner", "Demo Owner", true),
    LAST_CONTACT("最近沟通时间", "Last contact date", "2026-11-06"),
    SAMPLE("寄样状态", "Sample status", "RECEIVED", LibrarySampleStatus.values()),
    SAMPLE_TYPE("样品类型", "Sample type", "GIFT", LibrarySampleType.values()),
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
      writeFields(input, header, required, text, english, MAX_ROWS);
      writeInstruction(
          input,
          english
              ? "Highlighted fields are required. Starting in column B, fill one creator per column. Use the drop-downs. Separate multiple content categories with commas. Use YYYY-MM-DD for dates and a number from 0 to 100 for optional audience shares."
              : "高亮字段为必填项。从 B 列开始填写，每位达人占一列。下拉字段请选择对应选项，多个内容类目用顿号或逗号分隔。日期使用 YYYY-MM-DD，受众占比选填，填 0–100 的数字，例如 20。");

      XSSFSheet example = workbook.createSheet(english ? "Example" : "填写示例");
      writeFields(example, header, required, text, english, 1);
      writeInstruction(
          example,
          english
              ? "Example only. Fill in Creators. Dates use YYYY-MM-DD. Separate multiple content categories with commas."
              : "示例仅供参考，请在「达人导入」填写。日期格式为 YYYY-MM-DD，多个内容类目用顿号或逗号分隔。");
      for (int index = 0; index < TEMPLATE_COLUMNS.size(); index++) {
        example
            .getRow(HEADER_ROW + 1 + index)
            .createCell(1)
            .setCellValue(TEMPLATE_COLUMNS.get(index).example(english));
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
      int fieldRow = HEADER_ROW + 1 + index;
      var validation =
          helper.createValidation(
              helper.createFormulaListConstraint(name.getNameName()),
              new CellRangeAddressList(fieldRow, fieldRow, 1, MAX_ROWS));
      validation.setEmptyCellAllowed(true);
      // The form permits multiple categories. A comma-separated entry is validated on import.
      validation.setShowErrorBox(column != Column.CATEGORIES);
      input.addValidationData(validation);
    }
    workbook.setSheetHidden(workbook.getSheetIndex(options), true);
  }

  private static String optionLabel(LibraryOption option, boolean english) {
    if (option == LibraryAudienceGender.MOSTLY_FEMALE) {
      return english ? "Female" : "女性";
    }
    if (option == LibraryAudienceGender.MOSTLY_MALE) {
      return english ? "Male" : "男性";
    }
    return english ? option.nameEn() : option.nameZh();
  }

  private static CellStyle headerStyle(XSSFWorkbook workbook, IndexedColors color) {
    CellStyle style = workbook.createCellStyle();
    var font = workbook.createFont();
    font.setBold(true);
    style.setFont(font);
    style.setAlignment(HorizontalAlignment.LEFT);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    style.setFillForegroundColor(color.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    return style;
  }

  private static void writeInstruction(XSSFSheet sheet, String text) {
    var row = sheet.createRow(0);
    row.setHeightInPoints(45);
    var cell = row.createCell(0);
    var style = sheet.getWorkbook().createCellStyle();
    style.setWrapText(true);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    cell.setCellStyle(style);
    cell.setCellValue(text);
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
  }

  private static void writeFields(
      XSSFSheet sheet,
      CellStyle header,
      CellStyle required,
      CellStyle text,
      boolean english,
      int creatorCount) {
    sheet.createFreezePane(1, HEADER_ROW + 1);
    sheet.setDefaultColumnWidth(35);
    sheet.setColumnWidth(0, 34 * 256);
    XSSFRow headings = sheet.createRow(HEADER_ROW);
    headings.setHeightInPoints(22);
    var fieldHeading = headings.createCell(0);
    fieldHeading.setCellValue(english ? "Field" : "字段");
    fieldHeading.setCellStyle(header);
    for (int index = 1; index <= creatorCount; index++) {
      var heading = headings.createCell(index);
      heading.setCellValue((english ? "Creator " : "达人 ") + index);
      heading.setCellStyle(header);
      sheet.setDefaultColumnStyle(index, text);
    }
    for (int index = 0; index < TEMPLATE_COLUMNS.size(); index++) {
      Column col = TEMPLATE_COLUMNS.get(index);
      var row = sheet.createRow(HEADER_ROW + 1 + index);
      row.setHeightInPoints(22);
      var cell = row.createCell(0);
      cell.setCellValue(col.label(english));
      cell.setCellStyle(col.required ? required : header);
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
          errors.add(row.error(ErrorCode.KOL_LIBRARY_IMPORT_DUPLICATE_ROW, null));
        } else {
          kols.create(command);
          created++;
        }
      } catch (InvalidCell e) {
        errors.add(row.error(ErrorCode.COMMON_VALIDATION_FAILED, e.column.label(english)));
      } catch (BusinessException e) {
        errors.add(row.error(e.getErrorCode(), null));
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
      XSSFRow headings = sheet.getRow(HEADER_ROW);
      var first = headings == null ? null : headings.getCell(0);
      boolean vertical =
          first != null
              && first.getCellType() == CellType.STRING
              && Set.of("字段", "Field").contains(first.getStringCellValue().strip());
      List<InputRow> rows = vertical ? readVertical(sheet) : readHorizontal(sheet, headings);
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

  private static List<InputRow> readVertical(XSSFSheet sheet) {
    Map<Column, Integer> fields = new EnumMap<>(Column.class);
    int lastColumn = 0;
    for (var raw : sheet) {
      if (raw.getRowNum() > HEADER_ROW) {
        XSSFRow row = (XSSFRow) raw;
        if (readHeader(fields, row.getCell(0), row.getRowNum())) {
          lastColumn = Math.max(lastColumn, row.getLastCellNum() - 1);
        }
      }
    }
    requireHeaders(fields);
    List<InputRow> rows = new ArrayList<>();
    for (int index = 1; index <= lastColumn; index++) {
      int column = index;
      readRecord(
          rows,
          fields,
          row -> sheet.getRow(row).getCell(column),
          index,
          CellReference.convertNumToColString(index));
    }
    return rows;
  }

  private static List<InputRow> readHorizontal(XSSFSheet sheet, XSSFRow headings) {
    Map<Column, Integer> fields = new EnumMap<>(Column.class);
    if (headings != null) {
      for (var cell : headings) {
        readHeader(fields, (XSSFCell) cell, cell.getColumnIndex());
      }
    }
    requireHeaders(fields);
    List<InputRow> rows = new ArrayList<>();
    for (var raw : sheet) {
      if (raw.getRowNum() > HEADER_ROW) {
        XSSFRow row = (XSSFRow) raw;
        readRecord(rows, fields, row::getCell, row.getRowNum() + 1, null);
      }
    }
    return rows;
  }

  private static boolean readHeader(Map<Column, Integer> fields, XSSFCell cell, int position) {
    if (cell == null || cell.getCellType() == CellType.BLANK) {
      return false;
    }
    if (cell.getCellType() != CellType.STRING) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
    String label = cell.getStringCellValue().strip();
    if (label.isEmpty()) {
      return false;
    }
    Column field =
        Arrays.stream(Column.values())
            .filter(col -> col.zh.equals(label) || col.en.equalsIgnoreCase(label))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH));
    if (fields.putIfAbsent(field, position) != null) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
    return true;
  }

  private static void requireHeaders(Map<Column, Integer> fields) {
    if (TEMPLATE_COLUMNS.stream()
        .filter(
            col ->
                col != Column.AGE_SHARE && col != Column.GENDER_SHARE && col != Column.SAMPLE_TYPE)
        .anyMatch(col -> !fields.containsKey(col))) {
      throw new BusinessException(ErrorCode.TASK_FILE_HEADER_MISMATCH);
    }
  }

  private static void readRecord(
      List<InputRow> rows,
      Map<Column, Integer> fields,
      IntFunction<XSSFCell> cellAt,
      int position,
      String recordColumn) {
    Map<Column, String> cells = new EnumMap<>(Column.class);
    Column invalid = null;
    for (var entry : fields.entrySet()) {
      try {
        String value = readCell(cellAt.apply(entry.getValue()), entry.getKey());
        if (value != null) {
          cells.put(entry.getKey(), value);
        }
      } catch (InvalidCell e) {
        invalid = e.column;
      }
    }
    if (!cells.isEmpty() || invalid != null) {
      rows.add(new InputRow(position, recordColumn, cells, invalid));
    }
    if (rows.size() > MAX_ROWS) {
      throw new BusinessException(ErrorCode.TASK_FILE_TOO_MANY_ROWS);
    }
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
    } catch (NumberFormatException e) {
      throw new InvalidCell(column);
    }
    throw new InvalidCell(column);
  }

  private record InputRow(
      int number, String recordColumn, Map<Column, String> values, Column invalid) {
    private RowError error(ErrorCode code, String fieldLabel) {
      return new RowError(number, code.name(), fieldLabel, recordColumn);
    }

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
          rate(Column.RATE),
          values.get(Column.AUDIENCE_COUNTRY),
          choice(Column.AGE, LibraryAudienceAge.class),
          choice(Column.GENDER, LibraryAudienceGender.class),
          rate(Column.AGE_SHARE),
          rate(Column.GENDER_SHARE),
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
          choice(Column.SAMPLE_TYPE, LibrarySampleType.class),
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
                      || item.nameEn().equalsIgnoreCase(value)
                      || optionLabel(item, false).equalsIgnoreCase(value)
                      || optionLabel(item, true).equalsIgnoreCase(value))
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
      } catch (ArithmeticException e) {
        throw new InvalidCell(col);
      }
      throw new InvalidCell(col);
    }

    private BigDecimal rate(Column col) {
      String value = values.get(col);
      if (value == null) {
        return null;
      }
      BigDecimal rate = boundedDecimal(value.replaceFirst("[%％]$", "").strip(), col);
      if (rate.signum() >= 0
          && rate.compareTo(BigDecimal.valueOf(100)) <= 0
          && rate.stripTrailingZeros().scale() <= 2) {
        return rate;
      }
      throw new InvalidCell(col);
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

  public record RowError(int row, String errorCode, String column, String recordColumn) {}

  public record ImportResult(int created, List<RowError> errors) {}
}
