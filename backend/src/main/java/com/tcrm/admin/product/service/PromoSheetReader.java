package com.tcrm.admin.product.service;

import com.tcrm.admin.product.api.PromoSheetRowDTO;
import com.tcrm.admin.product.api.SheetCellDTO;
import com.tcrm.admin.product.api.SheetLinkParser;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * Streaming reader of an uploaded promo sheet (task 26 §3.2, ADR-024). The workbook is opened as a
 * ZIP and only the workbook, its relationships, the shared strings, the worksheets and their
 * relationship files are read — never {@code xl/media}, so a 240 MB file of embedded images costs
 * neither time nor memory. Each entry is decompressed through a counting stream and rejected beyond
 * {@link #MAX_ENTRY_BYTES} (zip bombs); StAX runs with DTDs and external entities disabled (XXE).
 * Every structural problem fails as {@link ErrorCode#KOL_PROMO_SHEET_INVALID}.
 *
 * <p>Link rules follow {@link SheetLinkParser} (ADR-021 §5): per cell the embedded hyperlink wins
 * over a {@code HYPERLINK} formula over http text, and per row the leftmost cell that yields a link
 * is the row's link. A {@code location}-only hyperlink (a RETURN row jumping inside the workbook)
 * marks the row as an in-sheet jump.
 */
@Component
public class PromoSheetReader {
  /** Decompressed bytes allowed per read entry; a real worksheet stays far below this. */
  static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;

  /** Name snapshots are stored in a VARCHAR(255) column. */
  static final int MAX_NAME = 255;

  private static final String WORKBOOK = "xl/workbook.xml";
  private static final String WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
  private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
  private static final Pattern SHEET_ENTRY = Pattern.compile("xl/worksheets/sheet\\d+\\.xml");

  /** A cell reference like {@code E5}: leading letters are the column, digits the row. */
  private static final Pattern REFERENCE = Pattern.compile("^([A-Z]+)([0-9]+)$");

  /** Header text of the name column (task 26 §3.2): {@code NAME} or {@code 名称}. */
  private static final Pattern NAME_HEADER = Pattern.compile("(?i:name|名称)");

  /** Reads every worksheet of the workbook and returns its link-bearing rows in sheet order. */
  public List<PromoSheetRowDTO> read(Path file) {
    try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
      List<String> shared = sharedStrings(zip);
      List<SheetRef> sheets = workbookSheets(zip);
      if (sheets.isEmpty()) {
        throw invalid();
      }
      List<PromoSheetRowDTO> rows = new ArrayList<>();
      for (SheetRef sheet : sheets) {
        readSheet(zip, sheet, shared, rows);
      }
      return rows;
    } catch (IOException e) {
      throw invalid();
    }
  }

  /** The shared-string table, or an empty list when the workbook has none. */
  private static List<String> sharedStrings(ZipFile zip) {
    ZipEntry entry = zip.getEntry(SHARED_STRINGS);
    if (entry == null) {
      return List.of();
    }
    List<String> strings = new ArrayList<>();
    try (CountingInputStream stream = limited(zip, entry)) {
      XMLStreamReader reader = factory().createXMLStreamReader(stream, "UTF-8");
      StringBuilder item = new StringBuilder();
      boolean inText = false;
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          if ("si".equals(reader.getLocalName())) {
            item.setLength(0);
          } else if ("t".equals(reader.getLocalName())) {
            inText = true;
          }
        } else if (event == XMLStreamConstants.CHARACTERS && inText) {
          item.append(reader.getText());
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if ("t".equals(reader.getLocalName())) {
            inText = false;
          } else if ("si".equals(reader.getLocalName())) {
            strings.add(item.toString());
          }
        }
      }
      reader.close();
      return strings;
    } catch (XMLStreamException | IOException e) {
      throw invalid();
    }
  }

  /** Ordered worksheets with their entry names, from the workbook and its relationships. */
  private static List<SheetRef> workbookSheets(ZipFile zip) {
    Map<String, String> targets = relationships(zip, WORKBOOK_RELS, false);
    List<String[]> declared = new ArrayList<>();
    try (CountingInputStream stream = limited(zip, required(zip, WORKBOOK))) {
      XMLStreamReader reader = factory().createXMLStreamReader(stream, "UTF-8");
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT && "sheet".equals(reader.getLocalName())) {
          declared.add(new String[] {attribute(reader, "name"), attribute(reader, "r", "id")});
        }
      }
      reader.close();
    } catch (XMLStreamException | IOException e) {
      throw invalid();
    }
    List<SheetRef> sheets = new ArrayList<>();
    for (String[] sheet : declared) {
      String entry = sheetTarget(targets.get(sheet[1]));
      if (sheet[0] != null && entry != null && zip.getEntry(entry) != null) {
        sheets.add(new SheetRef(sheet[0], entry));
      }
    }
    return sheets;
  }

  /** A relationship target like {@code worksheets/sheet1.xml} to {@code xl/worksheets/…}. */
  private static String sheetTarget(String target) {
    if (target == null) {
      return null;
    }
    String entry = target.startsWith("/xl/") ? target.substring(1) : "xl/" + target;
    return SHEET_ENTRY.matcher(entry).matches() ? entry : null;
  }

  /**
   * Targets of one relationships part keyed by relationship id. Internal parts (the workbook's
   * worksheets) carry no {@code TargetMode}; hyperlink parts only need the external ones.
   */
  private static Map<String, String> relationships(
      ZipFile zip, String entryName, boolean externalOnly) {
    Map<String, String> external = new HashMap<>();
    ZipEntry entry = zip.getEntry(entryName);
    if (entry == null) {
      return external;
    }
    try (CountingInputStream stream = limited(zip, entry)) {
      XMLStreamReader reader = factory().createXMLStreamReader(stream, "UTF-8");
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT
            && "Relationship".equals(reader.getLocalName())) {
          String id = attribute(reader, null, "Id");
          String target = attribute(reader, null, "Target");
          boolean isExternal = "External".equals(attribute(reader, null, "TargetMode"));
          if (id != null && target != null && (!externalOnly || isExternal)) {
            external.put(id, target);
          }
        }
      }
      reader.close();
      return external;
    } catch (XMLStreamException | IOException e) {
      throw invalid();
    }
  }

  /** Streams one worksheet: rows with their cells, then the hyperlinks part. */
  private static void readSheet(
      ZipFile zip, SheetRef sheet, List<String> shared, List<PromoSheetRowDTO> out) {
    Map<String, String> linkTargets =
        relationships(
            zip, sheet.entry().replace("worksheets/", "worksheets/_rels/") + ".rels", true);
    SheetParser parser = new SheetParser(shared, linkTargets);
    try (CountingInputStream stream = limited(zip, required(zip, sheet.entry()))) {
      XMLStreamReader reader = factory().createXMLStreamReader(stream, "UTF-8");
      parser.parse(reader);
      reader.close();
    } catch (XMLStreamException | IOException e) {
      throw invalid();
    }
    for (RowAcc row : parser.rows.values()) {
      if (row.link != null) {
        out.add(
            new PromoSheetRowDTO(sheet.name(), row.number, row.link, false, truncate(row.name)));
      } else if (row.inSheetJump) {
        out.add(new PromoSheetRowDTO(sheet.name(), row.number, null, true, truncate(row.name)));
      }
    }
  }

  /** One worksheet's streaming state: rows, the name column, and the current cell. */
  private static final class SheetParser {
    final Map<Integer, RowAcc> rows = new LinkedHashMap<>();
    private final List<String> shared;
    private final Map<String, String> linkTargets;
    private RowAcc row;
    private int sequentialColumn;
    private int lastRow;
    private int nameColumn = -1;
    private String cellType;
    private int cellColumn = -1;
    private final StringBuilder characters = new StringBuilder();
    private String cellText;
    private String cellFormula;
    private boolean inValue;
    private boolean inFormula;
    private boolean inInlineText;

    SheetParser(List<String> shared, Map<String, String> linkTargets) {
      this.shared = shared;
      this.linkTargets = linkTargets;
    }

    void parse(XMLStreamReader reader) throws XMLStreamException {
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          start(reader);
        } else if (event == XMLStreamConstants.CHARACTERS) {
          if (inValue || inFormula || inInlineText) {
            characters.append(reader.getText());
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          end(reader);
        }
      }
    }

    private void start(XMLStreamReader reader) {
      switch (reader.getLocalName()) {
        case "row" -> {
          int number = rowNumber(attribute(reader, "r"));
          row = rows.computeIfAbsent(number, RowAcc::new);
          sequentialColumn = 0;
        }
        case "c" -> {
          String reference = attribute(reader, "r");
          int column = columnNumber(reference);
          cellColumn = column >= 0 ? column : sequentialColumn;
          cellType = attribute(reader, "t");
          cellText = null;
          cellFormula = null;
          inValue = false;
          inFormula = false;
          inInlineText = false;
          characters.setLength(0);
        }
        case "f" -> inFormula = true;
        case "v" -> inValue = true;
        case "t" -> inInlineText = "inlineStr".equals(cellType);
        case "hyperlink" -> hyperlink(reader);
        default -> {
          // other children (merges, drawings, …) are skipped together with their content
        }
      }
    }

    private void end(XMLStreamReader reader) {
      switch (reader.getLocalName()) {
        case "f" -> {
          cellFormula = characters.toString();
          characters.setLength(0);
          inFormula = false;
        }
        case "v", "t" -> {
          if (inValue || inInlineText) {
            cellText = characters.toString();
            characters.setLength(0);
          }
          inValue = false;
          inInlineText = false;
        }
        case "c" -> {
          finishCell();
          sequentialColumn = cellColumn + 1;
        }
        case "row" -> row = null;
        default -> {
          // skipped
        }
      }
    }

    private void finishCell() {
      String text = cellText;
      if ("s".equals(cellType) && text != null) {
        int index;
        try {
          index = Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
          index = -1;
        }
        text = index >= 0 && index < shared.size() ? shared.get(index) : null;
      }
      if (row == null) {
        return;
      }
      if (nameColumn < 0 && text != null && NAME_HEADER.matcher(text.strip()).matches()) {
        // The first row whose cell reads NAME / 名称 fixes the name column; rows above it are
        // title or category rows and carry no snapshot.
        nameColumn = cellColumn;
        return;
      }
      if (cellColumn == nameColumn && text != null && !text.isBlank()) {
        row.name = text.strip();
      }
      // xlsx stores formulas without the leading "=" that the Google-shaped parser expects.
      String formula = cellFormula == null ? null : "=" + cellFormula;
      String link =
          SheetLinkParser.link(
              new SheetCellDTO(text == null ? "" : text, null, formula, List.of()));
      if (link != null && cellColumn < row.linkColumn) {
        row.link = link;
        row.linkColumn = cellColumn;
      }
    }

    private void hyperlink(XMLStreamReader reader) {
      String reference = attribute(reader, "ref");
      int number = rowNumber(reference);
      RowAcc target = rows.get(number);
      if (target == null) {
        return;
      }
      String id = attribute(reader, "r", "id");
      String external = id == null ? null : linkTargets.get(id);
      if (external != null) {
        int column = columnNumber(reference);
        // Same priority as one cell's own link: an embedded hyperlink beats the formula or text
        // of its cell, and an earlier column beats a later one.
        if (column <= target.linkColumn) {
          target.link = external;
          target.linkColumn = column;
        }
      } else if (attribute(reader, null, "location") != null) {
        target.inSheetJump = true;
      }
    }

    /** 1-based row of a row number or cell reference, or one past the last row seen. */
    private int rowNumber(String reference) {
      Integer parsed = referenceRow(reference);
      if (parsed != null) {
        lastRow = parsed;
        return parsed;
      }
      return ++lastRow;
    }
  }

  /** Column letters of a reference, or -1 when the reference has none. */
  private static int columnNumber(String reference) {
    Integer[] parsed = parseReference(reference);
    return parsed == null ? -1 : parsed[0];
  }

  /**
   * Row digits of a {@code <row r>} value (plain digits) or of a cell reference like {@code E5};
   * null when the value has neither shape.
   */
  private static Integer referenceRow(String reference) {
    if (reference == null) {
      return null;
    }
    if (reference.matches("[0-9]{1,7}")) {
      try {
        return Integer.valueOf(reference);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    Integer[] parsed = parseReference(reference);
    return parsed == null ? null : parsed[1];
  }

  /** {@code {column, row}} of a reference like {@code E5}, or null when unparsable. */
  private static Integer[] parseReference(String reference) {
    if (reference == null) {
      return null;
    }
    Matcher matcher = REFERENCE.matcher(reference);
    if (!matcher.matches()) {
      return null;
    }
    int column = 0;
    for (char letter : matcher.group(1).toCharArray()) {
      column = column * 26 + (letter - 'A' + 1);
    }
    try {
      return new Integer[] {column - 1, Integer.valueOf(matcher.group(2))};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The attribute's value by local name, ignoring any prefix. */
  private static String attribute(XMLStreamReader reader, String name) {
    return attribute(reader, null, name);
  }

  /** The attribute's value by prefix and local name; a null prefix matches any namespace. */
  private static String attribute(XMLStreamReader reader, String prefix, String local) {
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      if (!local.equals(reader.getAttributeLocalName(i))) {
        continue;
      }
      String found = reader.getAttributePrefix(i);
      boolean prefixMatches =
          prefix == null || prefix.equals(found) || (prefix.isEmpty() && found == null);
      if (prefixMatches) {
        return reader.getAttributeValue(i);
      }
    }
    return null;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= MAX_NAME ? value : value.substring(0, MAX_NAME);
  }

  /** StAX with DTDs and external entities off; a sheet containing either fails as invalid. */
  private static XMLInputFactory factory() {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    return factory;
  }

  private static CountingInputStream limited(ZipFile zip, ZipEntry entry) throws IOException {
    return new CountingInputStream(zip.getInputStream(entry));
  }

  private static ZipEntry required(ZipFile zip, String name) {
    ZipEntry entry = zip.getEntry(name);
    if (entry == null) {
      throw invalid();
    }
    return entry;
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.KOL_PROMO_SHEET_INVALID);
  }

  /** Fails the read once the entry's decompressed size exceeds {@link #MAX_ENTRY_BYTES}. */
  private static final class CountingInputStream extends InputStream {
    private final InputStream delegate;
    private long read;

    CountingInputStream(InputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value >= 0) {
        count(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int value = delegate.read(buffer, offset, length);
      if (value > 0) {
        count(value);
      }
      return value;
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    private void count(long bytes) throws IOException {
      read += bytes;
      if (read > MAX_ENTRY_BYTES) {
        throw new IOException("entry exceeds " + MAX_ENTRY_BYTES + " decompressed bytes");
      }
    }
  }

  /** The workbook's worksheets in order: display name plus ZIP entry name. */
  private record SheetRef(String name, String entry) {}

  /** One worksheet row while streaming: its best link and the NAME column's text. */
  private static final class RowAcc {
    final int number;
    String link;
    int linkColumn = Integer.MAX_VALUE;
    boolean inSheetJump;
    String name;

    RowAcc(int number) {
      this.number = number;
    }
  }
}
