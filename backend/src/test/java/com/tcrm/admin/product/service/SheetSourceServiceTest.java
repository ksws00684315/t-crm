package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.product.api.SheetCellDTO;
import com.tcrm.admin.product.api.SourceSheetReader;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.SheetSourceItemMapper;
import com.tcrm.admin.product.mapper.SheetSourceMapper;
import com.tcrm.admin.product.model.SheetSourceDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.task.api.ImportTaskApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class SheetSourceServiceTest {
  private static final String SHEET_ID = "1utim6wvKOCcQf1Qgvzba2Y7bQ79F2T5lqguJz-ODf1E";
  private final SheetSourceMapper sources = mock(SheetSourceMapper.class);
  private final ShopService shops = mock(ShopService.class);
  private final SourceSheetReader sheets = mock(SourceSheetReader.class);
  private final DistributedLocks locks = mock(DistributedLocks.class);
  private final ImportTaskApi imports = mock(ImportTaskApi.class);
  private SheetSourceService service;

  @BeforeEach
  void setUp() {
    service =
        new SheetSourceService(
            sources,
            mock(SheetSourceItemMapper.class),
            shops,
            mock(ProductMapper.class),
            mock(KolItemMapper.class),
            mock(ProductCodeGenerator.class),
            sheets,
            imports,
            locks,
            mock(CurrentUserContext.class),
            mock(TransactionTemplate.class),
            new ObjectMapper());
    ShopDO shop = new ShopDO();
    shop.setId(31L);
    shop.setWeidianShopId("1856156775");
    shop.setShopName("Main");
    when(shops.getById(31L)).thenReturn(shop);
  }

  @Test
  void shouldTurnSheetRowsIntoProductImportRows_andCountSkips() {
    Map<Integer, SheetCellDTO> links = new HashMap<>();
    links.put(3, cell("LINK", "https://litbuy.com/product/weidian/7736025747?inviteCode=X"));
    links.put(
        4,
        cell(
            "LINK",
            "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop2001.v.weidian.com"
                + "%2Fitem.html%3FitemID%3D7739078620"));
    links.put(5, text("Tees/Vest"));
    links.put(6, cell("LINK", "https://item.taobao.com/item.htm?id=712345678901"));
    links.put(7, cell("LINK", "https://bit.ly/abc"));
    Map<Integer, SheetCellDTO> names = new HashMap<>();
    names.put(3, text(" Hoodie "));
    names.put(4, text("Zip Up"));
    names.put(5, text("Tees/Vest"));
    names.put(6, text("Taobao bag"));
    names.put(7, text("Short link"));
    Map<Integer, SheetCellDTO> prices = Map.of(3, text("$60"), 4, text("¥145 | $21.13"));
    Map<Integer, SheetCellDTO> images =
        Map.of(3, new SheetCellDTO("", null, "=IMAGE(\"https://img.test/h.jpg\")", List.of()));
    when(sheets.readColumns(eq(SHEET_ID), eq("ARCHIVE style"), eq(3), anyInt(), any()))
        .thenReturn(Map.of("E", links, "B", names, "D", prices, "A", images));

    SheetSourceService.Parsed parsed = service.fetchAndParse(source(31L, "A"));

    assertThat(parsed.rows())
        .containsExactly(
            List.of(
                "", "1856156775", "7736025747", "Hoodie", "", "https://img.test/h.jpg", "60", ""),
            List.of("", "2001", "7739078620", "Zip Up", "", "", "21.13", ""));
    assertThat(parsed.items())
        .containsExactly(
            new SheetSourceService.Parsed.Item("1856156775", "7736025747", 3),
            new SheetSourceService.Parsed.Item("2001", "7739078620", 4));
    assertThat(parsed.skipped())
        .isEqualTo(
            new SheetSourceService.Skipped(
                1,
                1,
                1,
                0,
                List.of(
                    new SheetSourceService.Sample(6, "NON_WEIDIAN", "Taobao bag"),
                    new SheetSourceService.Sample(7, "NO_ITEM_ID", "Short link"))));
  }

  @Test
  void shouldSkipRowsWithoutShop_whenNoDefaultShopIsConfigured() {
    when(sheets.readColumns(anyString(), anyString(), anyInt(), anyInt(), any()))
        .thenReturn(
            Map.of(
                "E",
                    Map.of(
                        3,
                        cell("LINK", "https://weidian.com/item.html?itemID=7736025747"),
                        4,
                        cell("LINK", "https://shop9.v.weidian.com/item.html?itemID=1234567890")),
                "B", Map.of(3, text("A"), 4, text("B")),
                "D", Map.of()));

    SheetSourceService.Parsed parsed = service.fetchAndParse(source(null, null));

    assertThat(parsed.rows()).hasSize(1);
    assertThat(parsed.skipped().noShop()).isEqualTo(1);
    assertThat(parsed.skipped().samples())
        .containsExactly(new SheetSourceService.Sample(3, "NO_SHOP", "A"));
  }

  @Test
  void shouldRequestEachColumnOnce_inLinkNamePriceOrder() {
    when(sheets.readColumns(anyString(), anyString(), anyInt(), anyInt(), any()))
        .thenReturn(
            Map.of(
                "E",
                Map.of(3, cell("LINK", "https://weidian.com/item.html?itemID=1")),
                "B",
                Map.of(),
                "D",
                Map.of(),
                "A",
                Map.of()));

    service.fetchAndParse(source(31L, "A"));

    verify(sheets)
        .readColumns(
            SHEET_ID, "ARCHIVE style", 3, SheetSourceService.MAX_ROWS, List.of("E", "B", "D", "A"));
  }

  @Test
  void shouldRejectAnEmptySheet_andAnOversizedSheet() {
    when(sheets.readColumns(anyString(), anyString(), anyInt(), anyInt(), any()))
        .thenReturn(Map.of("E", Map.of(5, text("header")), "B", Map.of(), "D", Map.of()));
    assertThatThrownBy(() -> service.fetchAndParse(source(31L, null)))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SHEET_SOURCE_EMPTY);

    when(sheets.readColumns(anyString(), anyString(), anyInt(), anyInt(), any()))
        .thenReturn(
            Map.of(
                "E",
                Map.of(
                    3 + SheetSourceService.MAX_ROWS,
                    cell("LINK", "https://weidian.com/item.html?itemID=1")),
                "B",
                Map.of(),
                "D",
                Map.of()));
    assertThatThrownBy(() -> service.fetchAndParse(source(31L, null)))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SHEET_SOURCE_TOO_LARGE);
  }

  @Test
  void shouldTakeTheSpreadsheetIdFromAnAddressOrABareId() {
    assertThat(
            SheetSourceService.spreadsheetId(
                "https://docs.google.com/spreadsheets/d/"
                    + SHEET_ID
                    + "/edit?gid=2037966457#gid=1"))
        .isEqualTo(SHEET_ID);
    assertThat(SheetSourceService.spreadsheetId(" " + SHEET_ID + " ")).isEqualTo(SHEET_ID);
    assertThat(SheetSourceService.spreadsheetId("https://example.com/x")).isNull();
    assertThat(SheetSourceService.spreadsheetId(null)).isNull();
  }

  @Test
  void shouldRejectDuplicateColumns_andAnUnknownDefaultShop() {
    assertThatThrownBy(() -> service.create(command("E", "E", "D", null)))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
    when(shops.require(99L)).thenThrow(new BusinessException(ErrorCode.SHOP_NOT_FOUND));
    assertThatThrownBy(() -> service.create(command("E", "B", "D", 99L)))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SHOP_NOT_FOUND);
    verify(sources, never()).insert(any(SheetSourceDO.class));
  }

  @Test
  void shouldRefuseASecondRead_whileTheLockIsHeld() throws Exception {
    when(locks.run(anyString(), anyLong(), anyLong(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.read(5L))
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SHEET_SOURCE_READING);
  }

  private static SheetSourceService.SaveCommand command(
      String link, String name, String price, Long shop) {
    return new SheetSourceService.SaveCommand(
        "Main", SHEET_ID, "ARCHIVE style", 2, link, name, price, null, null, shop, "USD");
  }

  private static SheetSourceDO source(Long defaultShop, String imageColumn) {
    SheetSourceDO s = new SheetSourceDO();
    s.setId(5L);
    s.setSourceName("Main");
    s.setSpreadsheetId(SHEET_ID);
    s.setSheetTitle("ARCHIVE style");
    s.setHeaderRow(2);
    s.setLinkColumn("E");
    s.setNameColumn("B");
    s.setPriceColumn("D");
    s.setImageColumn(imageColumn);
    s.setDefaultShopId(defaultShop);
    s.setPriceCurrency("USD");
    s.setStatus(1);
    s.setReadNo(0);
    return s;
  }

  private static SheetCellDTO cell(String text, String link) {
    return new SheetCellDTO(text, link, null, List.of());
  }

  private static SheetCellDTO text(String text) {
    return new SheetCellDTO(text, null, null, List.of());
  }
}
