package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.product.api.PromoSheetRowDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PromoSheetReader} against real workbooks: the three link shapes of a promo sheet, name
 * snapshots, in-sheet jumps, and the security rules (no DTD, entry size cap, no media reads).
 */
class PromoSheetReaderTest {
  private static final String KAKOBUY =
      "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop1856156775.v.weidian.com"
          + "%2Fitem.html%3FitemID%3D7849640325%26spider_token%3Dabc";
  private static final String WEIDIAN_TEXT =
      "https://shop1856156775.v.weidian.com/item.html?itemID=7849640326";

  private final PromoSheetReader reader = new PromoSheetReader();

  @TempDir Path temp;

  /** Header on row 2 like the customer sample: PHOTO / NAME / CW / PRICE (USD) / LINK. */
  private static void header(Sheet sheet) {
    Row head = sheet.createRow(1);
    head.createCell(0).setCellValue("PHOTO");
    head.createCell(1).setCellValue("NAME");
    head.createCell(2).setCellValue("CW");
    head.createCell(3).setCellValue("PRICE (USD)");
    head.createCell(4).setCellValue("LINK");
  }

  private static void urlCell(Sheet sheet, int rowNumber, int column, String url) {
    Row row = sheet.createRow(rowNumber);
    XSSFWorkbook book = (XSSFWorkbook) sheet.getWorkbook();
    CreationHelper helper = book.getCreationHelper();
    Hyperlink link = helper.createHyperlink(HyperlinkType.URL);
    link.setAddress(url);
    row.createCell(column).setHyperlink(link);
    row.createCell(column).setCellValue("LINK");
  }

  private List<PromoSheetRowDTO> read(XSSFWorkbook book) throws IOException {
    Path file = temp.resolve("sheet-" + System.nanoTime() + ".xlsx");
    try (OutputStream out = Files.newOutputStream(file)) {
      book.write(out);
    }
    return reader.read(file);
  }

  @Test
  void shouldReadCellHyperlinksWithNameSnapshot() throws IOException {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("ARCHIVE style");
      header(sheet);
      Row row = sheet.createRow(2);
      row.createCell(1).setCellValue("Silver Ring");
      CreationHelper helper = book.getCreationHelper();
      Hyperlink link = helper.createHyperlink(HyperlinkType.URL);
      link.setAddress(KAKOBUY);
      row.createCell(4).setHyperlink(link);
      row.createCell(4).setCellValue("LINK");

      List<PromoSheetRowDTO> rows = read(book);
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().sheet()).isEqualTo("ARCHIVE style");
      assertThat(rows.getFirst().row()).isEqualTo(3);
      assertThat(rows.getFirst().link()).isEqualTo(KAKOBUY);
      assertThat(rows.getFirst().name()).isEqualTo("Silver Ring");
      assertThat(rows.getFirst().inSheetJump()).isFalse();
    }
  }

  @Test
  void shouldReadHyperlinkFormulas() throws IOException {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("Sheet1");
      header(sheet);
      Row row = sheet.createRow(2);
      row.createCell(1).setCellValue("Formula Item");
      row.createCell(4).setCellFormula("HYPERLINK(\"" + WEIDIAN_TEXT + "\",\"LINK\")");

      List<PromoSheetRowDTO> rows = read(book);
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().link()).isEqualTo(WEIDIAN_TEXT);
      assertThat(rows.getFirst().name()).isEqualTo("Formula Item");
    }
  }

  @Test
  void shouldReadTextLinksStartingWithHttp() throws IOException {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("Sheet1");
      header(sheet);
      Row row = sheet.createRow(2);
      row.createCell(1).setCellValue("Text Item");
      row.createCell(4).setCellValue(WEIDIAN_TEXT);

      List<PromoSheetRowDTO> rows = read(book);
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().link()).isEqualTo(WEIDIAN_TEXT);
    }
  }

  @Test
  void shouldSkipRowsWithoutLinks() throws IOException {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("Sheet1");
      header(sheet);
      sheet.createRow(2).createCell(1).setCellValue("CATEGORY: RINGS");
      Row linkRow = sheet.createRow(3);
      linkRow.createCell(1).setCellValue("Real Item");
      linkRow.createCell(4).setCellValue(WEIDIAN_TEXT);

      List<PromoSheetRowDTO> rows = read(book);
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().row()).isEqualTo(4);
    }
  }

  @Test
  void shouldMarkInSheetJumps() throws IOException {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("ARCHIVE style");
      header(sheet);
      Row row = sheet.createRow(2);
      row.createCell(1).setCellValue("RETURN");
      CreationHelper helper = book.getCreationHelper();
      Hyperlink jump = helper.createHyperlink(HyperlinkType.DOCUMENT);
      jump.setAddress("'ARCHIVE style'!A1");
      row.createCell(4).setHyperlink(jump);
      row.createCell(4).setCellValue("RETURN PAGE");

      List<PromoSheetRowDTO> rows = read(book);
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().link()).isNull();
      assertThat(rows.getFirst().inSheetJump()).isTrue();
    }
  }

  @Test
  void shouldReadEveryWorksheet() throws IOException {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      header(book.createSheet("First"));
      header(book.createSheet("Second"));
      urlCell(book.getSheet("First"), 2, 4, WEIDIAN_TEXT);
      urlCell(book.getSheet("Second"), 5, 0, KAKOBUY);

      List<PromoSheetRowDTO> rows = read(book);
      assertThat(rows).hasSize(2);
      assertThat(rows.get(0).sheet()).isEqualTo("First");
      assertThat(rows.get(1).sheet()).isEqualTo("Second");
      assertThat(rows.get(1).row()).isEqualTo(6);
    }
  }

  @Test
  void shouldTruncateNameSnapshotsTo255() throws IOException {
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("Sheet1");
      header(sheet);
      Row row = sheet.createRow(2);
      row.createCell(1).setCellValue("x".repeat(300));
      row.createCell(4).setCellValue(WEIDIAN_TEXT);

      assertThat(read(book).getFirst().name()).hasSize(255);
    }
  }

  @Test
  void shouldRejectFilesThatAreNotWorkbooks() throws IOException {
    Path plain = temp.resolve("plain.xlsx");
    Files.writeString(plain, "not a zip at all");
    assertThatThrownBy(() -> reader.read(plain))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.KOL_PROMO_SHEET_INVALID);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("readme.txt"));
      zip.write("no workbook here".getBytes(StandardCharsets.UTF_8));
    }
    Path withoutWorkbook = temp.resolve("no-workbook.xlsx");
    Files.write(withoutWorkbook, bytes.toByteArray());
    assertThatThrownBy(() -> reader.read(withoutWorkbook))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.KOL_PROMO_SHEET_INVALID);
  }

  @Test
  void shouldRejectWorksheetWithDtdOrEntity() throws IOException {
    Path file =
        handcraftedSheet(
            "<!DOCTYPE worksheet [<!ENTITY xxe SYSTEM"
                + " \"file:///etc/passwd\">]>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&xxe;</t></is></c></row>"
                + "</sheetData></worksheet>");
    assertThatThrownBy(() -> reader.read(file))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.KOL_PROMO_SHEET_INVALID);
  }

  @Test
  void shouldRejectEntriesBeyondTheSizeCap() throws IOException {
    StringBuilder huge = new StringBuilder();
    huge.append(
        "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>");
    long remaining = PromoSheetReader.MAX_ENTRY_BYTES + 1024;
    while (remaining > 0) {
      int chunk = (int) Math.min(remaining, 1 << 16);
      huge.append("a".repeat(chunk));
      remaining -= chunk;
    }
    huge.append("</t></is></c></row></sheetData></worksheet>");
    Path file = handcraftedSheet(huge.toString());
    assertThatThrownBy(() -> reader.read(file))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.KOL_PROMO_SHEET_INVALID);
  }

  @Test
  void shouldNotReadEmbeddedMedia() throws IOException {
    // A workbook with a large incompressible image must parse as fast as one without it: the
    // reader never opens xl/media, so neither time nor memory follows the image size.
    byte[] image = new byte[16 << 20];
    new java.util.Random(42).nextBytes(image);
    long withImage;
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("Sheet1");
      header(sheet);
      Row row = sheet.createRow(2);
      row.createCell(1).setCellValue("Media Item");
      row.createCell(4).setCellValue(WEIDIAN_TEXT);
      int picture = book.addPicture(image, XSSFWorkbook.PICTURE_TYPE_PNG);
      var drawing = sheet.createDrawingPatriarch();
      var anchor = drawing.createAnchor(0, 0, 0, 0, 0, 0, 1, 1);
      drawing.createPicture(anchor, picture);
      long started = System.nanoTime();
      List<PromoSheetRowDTO> rows = read(book);
      withImage = System.nanoTime() - started;
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().link()).isEqualTo(WEIDIAN_TEXT);
    }
    long withoutImage;
    try (XSSFWorkbook book = new XSSFWorkbook()) {
      Sheet sheet = book.createSheet("Sheet1");
      header(sheet);
      Row row = sheet.createRow(2);
      row.createCell(1).setCellValue("Media Item");
      row.createCell(4).setCellValue(WEIDIAN_TEXT);
      long started = System.nanoTime();
      read(book);
      withoutImage = System.nanoTime() - started;
    }
    assertThat(withImage).isLessThan(withoutImage + 3_000_000_000L);
  }

  /** A minimal but structurally valid single-sheet workbook with the given sheet1.xml. */
  private Path handcraftedSheet(String sheetXml) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(
          ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types"
                  + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
      zip.write(
          ("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                  + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                  + "<sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
      zip.write(
          ("<Relationships"
                  + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                  + "<Relationship Id=\"rId1\""
                  + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                  + "/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
      zip.write(sheetXml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    Path file = temp.resolve("handcrafted-" + System.nanoTime() + ".xlsx");
    Files.write(file, bytes.toByteArray());
    return file;
  }
}
