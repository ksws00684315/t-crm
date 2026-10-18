package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.api.SheetCellDTO;
import com.tcrm.admin.product.api.SheetLinkParser;
import com.tcrm.admin.product.api.SourceSheetReader;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.SheetSourceItemMapper;
import com.tcrm.admin.product.mapper.SheetSourceMapper;
import com.tcrm.admin.product.model.SheetMissingRow;
import com.tcrm.admin.product.model.SheetSourceDO;
import com.tcrm.admin.product.model.SheetSourceItemRow;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.DistributedLocks;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.task.api.ImportTaskApi;
import com.tcrm.admin.task.api.TaskType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Product source sheets (ADR-021): configuration, manual reads into the original-product import,
 * and the products that disappeared from a sheet.
 */
@Service
public class SheetSourceService {
  static final int MAX_ROWS = 50_000;
  static final int SAMPLE_LIMIT = 100;
  private static final int UPSERT_BATCH = 1_000;
  private static final Pattern SHEET_URL =
      Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]{20,128})");
  private static final Pattern SHEET_ID = Pattern.compile("[A-Za-z0-9_-]{20,128}");
  private static final Pattern COLUMN = Pattern.compile("[A-Z]{1,3}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  private final SheetSourceMapper sources;
  private final SheetSourceItemMapper items;
  private final ShopService shops;
  private final ProductMapper products;
  private final KolItemMapper kolItems;
  private final ProductCodeGenerator codes;
  private final SourceSheetReader sheets;
  private final ImportTaskApi imports;
  private final DistributedLocks locks;
  private final CurrentUserContext current;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public SheetSourceService(
      SheetSourceMapper sources,
      SheetSourceItemMapper items,
      ShopService shops,
      ProductMapper products,
      KolItemMapper kolItems,
      ProductCodeGenerator codes,
      SourceSheetReader sheets,
      ImportTaskApi imports,
      DistributedLocks locks,
      CurrentUserContext current,
      TransactionTemplate tx,
      ObjectMapper json) {
    this.sources = sources;
    this.items = items;
    this.shops = shops;
    this.products = products;
    this.kolItems = kolItems;
    this.codes = codes;
    this.sheets = sheets;
    this.imports = imports;
    this.locks = locks;
    this.current = current;
    this.tx = tx;
    this.json = json;
  }

  /** Configuration as entered; {@code sheet} is the sheet address or the bare spreadsheet ID. */
  public record SaveCommand(
      String sourceName,
      String sheet,
      String sheetTitle,
      Integer headerRow,
      String linkColumn,
      String nameColumn,
      String priceColumn,
      String categoryColumn,
      String imageColumn,
      Long defaultShopId,
      String priceCurrency) {}

  /** Rows skipped by a read, counted by reason, with up to {@value #SAMPLE_LIMIT} examples. */
  public record Skipped(int empty, int nonWeidian, int noItemId, int noShop, List<Sample> samples) {
    static Skipped none() {
      return new Skipped(0, 0, 0, 0, List.of());
    }
  }

  /** One skipped row: 1-based sheet row, reason ({@code NON_WEIDIAN}, …) and the name cell. */
  public record Sample(int row, String reason, String name) {}

  public record ReadResult(Long taskId, int rowCount, Skipped skipped) {}

  public record SourceDTO(
      Long id,
      String sourceName,
      String spreadsheetId,
      String sheetTitle,
      int headerRow,
      String linkColumn,
      String nameColumn,
      String priceColumn,
      String categoryColumn,
      String imageColumn,
      Long defaultShopId,
      String defaultShopName,
      String priceCurrency,
      int status,
      Instant lastReadAt,
      Long lastTaskId,
      Integer lastRowCount,
      Skipped lastSkipped,
      String lastError) {}

  public record MissingDTO(
      Long productId,
      String productCode,
      String productName,
      Integer productStatus,
      Long shopId,
      String shopName,
      String weidianItemId,
      Integer sheetRow,
      Instant lastSeenTime) {}

  public List<SourceDTO> list() {
    return sources
        .selectList(new LambdaQueryWrapper<SheetSourceDO>().orderByAsc(SheetSourceDO::getId))
        .stream()
        .map(this::toDto)
        .toList();
  }

  public SourceDTO get(Long id) {
    return toDto(require(id));
  }

  public SourceDTO create(SaveCommand c) {
    SheetSourceDO row = new SheetSourceDO();
    apply(row, c);
    row.setStatus(1);
    row.setReadNo(0);
    row.setDeleted(false);
    sources.insert(row);
    return get(row.getId());
  }

  public SourceDTO update(Long id, SaveCommand c) {
    SheetSourceDO row = require(id);
    apply(row, c);
    sources.updateById(row);
    return get(id);
  }

  public void updateStatus(Long id, int status) {
    if (status != 0 && status != 1) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    require(id);
    sources.update(
        null,
        new LambdaUpdateWrapper<SheetSourceDO>()
            .eq(SheetSourceDO::getId, id)
            .set(SheetSourceDO::getStatus, status));
  }

  public void delete(Long id) {
    require(id);
    sources.deleteById(id);
  }

  /**
   * Reads the sheet now and submits the rows as an original-product import (ADR-021 §6). Holds the
   * per-source lock while reading and submitting; a concurrent read is SHEET_SOURCE_READING.
   */
  public ReadResult read(Long id) {
    Long actorId = current.id();
    ReadResult[] result = new ReadResult[1];
    boolean ran;
    try {
      ran = locks.run("product:sheet-read:" + id, 0, -1, () -> result[0] = readLocked(id, actorId));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.SHEET_SOURCE_READING);
    }
    if (!ran) {
      throw new BusinessException(ErrorCode.SHEET_SOURCE_READING);
    }
    return result[0];
  }

  public PageResult<MissingDTO> missing(Long id, int page, int size) {
    SheetSourceDO source = require(id);
    if (source.getReadNo() == null || source.getReadNo() == 0) {
      return new PageResult<>(List.of(), 0, page, size);
    }
    Page<SheetMissingRow> result =
        items.pageMissing(new Page<>(page, size), id, source.getReadNo());
    Map<Long, String> shopNames = new HashMap<>();
    List<MissingDTO> rows =
        result.getRecords().stream()
            .map(
                r ->
                    new MissingDTO(
                        r.getProductId(),
                        r.getProductCode(),
                        r.getProductName(),
                        r.getProductStatus(),
                        r.getShopId(),
                        shopNames.computeIfAbsent(r.getShopId(), this::shopName),
                        r.getWeidianItemId(),
                        r.getSheetRow(),
                        r.getLastSeenTime()))
            .toList();
    return new PageResult<>(rows, result.getTotal(), page, size);
  }

  private ReadResult readLocked(Long id, Long actorId) {
    SheetSourceDO source = require(id);
    if (!Integer.valueOf(1).equals(source.getStatus())) {
      throw new BusinessException(ErrorCode.SHEET_SOURCE_DISABLED);
    }
    Parsed parsed;
    try {
      parsed = fetchAndParse(source);
    } catch (BusinessException e) {
      recordError(id, e.getErrorCode());
      throw e;
    }
    Set<String> seen = new HashSet<>();
    List<SheetSourceItemRow> snapshot = new ArrayList<>();
    Map<String, ShopDO> shopsByWeidianId = new HashMap<>();
    Long tenantId = current.user().tenantId();
    for (Parsed.Item item : parsed.items()) {
      ShopDO shop =
          shopsByWeidianId.computeIfAbsent(item.weidianShopId(), shops::findEnabledByWeidianId);
      if (shop != null && seen.add(shop.getId() + ":" + item.itemId())) {
        snapshot.add(
            new SheetSourceItemRow(
                IdWorker.getId(), tenantId, id, shop.getId(), item.itemId(), item.row(), null));
      }
    }
    tx.executeWithoutResult(
        status -> {
          sources.lockById(id);
          int next = require(id).getReadNo() + 1;
          sources.update(
              null,
              new LambdaUpdateWrapper<SheetSourceDO>()
                  .eq(SheetSourceDO::getId, id)
                  .set(SheetSourceDO::getReadNo, next));
          List<SheetSourceItemRow> numbered =
              snapshot.stream()
                  .map(
                      r ->
                          new SheetSourceItemRow(
                              r.id(),
                              r.tenantId(),
                              r.sourceId(),
                              r.shopId(),
                              r.weidianItemId(),
                              r.sheetRow(),
                              next))
                  .toList();
          for (int from = 0; from < numbered.size(); from += UPSERT_BATCH) {
            items.upsert(numbered.subList(from, Math.min(from + UPSERT_BATCH, numbered.size())));
          }
        });
    String currency = source.getPriceCurrency();
    Long taskId;
    try {
      taskId =
          imports.submitRows(
              TaskType.PRODUCT_IMPORT,
              ProductImportColumn.headerKeys(),
              source.getSourceName() + ".xlsx",
              parsed.rows(),
              () ->
                  new ProductImportProcessor(
                      actorId, currency, shops, products, kolItems, codes, tx),
              MAX_ROWS);
    } catch (BusinessException e) {
      recordError(id, e.getErrorCode());
      throw e;
    }
    sources.update(
        null,
        new LambdaUpdateWrapper<SheetSourceDO>()
            .eq(SheetSourceDO::getId, id)
            .set(SheetSourceDO::getLastReadAt, Instant.now())
            .set(SheetSourceDO::getLastTaskId, taskId)
            .set(SheetSourceDO::getLastRowCount, parsed.rows().size())
            .set(SheetSourceDO::getLastSkipped, json.writeValueAsString(parsed.skipped()))
            .set(SheetSourceDO::getLastError, null));
    return new ReadResult(taskId, parsed.rows().size(), parsed.skipped());
  }

  /** Rows for the product import plus the (weidian shop, item, row) of each, and the skips. */
  record Parsed(List<List<String>> rows, List<Item> items, Skipped skipped) {
    record Item(String weidianShopId, String itemId, int row) {}
  }

  Parsed fetchAndParse(SheetSourceDO source) {
    List<String> columns = columns(source);
    int firstRow = source.getHeaderRow() + 1;
    Map<String, Map<Integer, SheetCellDTO>> cells =
        sheets.readColumns(
            source.getSpreadsheetId(), source.getSheetTitle(), firstRow, MAX_ROWS, columns);
    TreeSet<Integer> rowNumbers = new TreeSet<>();
    cells.values().forEach(column -> rowNumbers.addAll(column.keySet()));
    if (!rowNumbers.isEmpty() && rowNumbers.last() >= firstRow + MAX_ROWS) {
      throw new BusinessException(ErrorCode.SHEET_SOURCE_TOO_LARGE);
    }
    String defaultShop = defaultShopWeidianId(source);
    List<List<String>> rows = new ArrayList<>();
    List<Parsed.Item> parsedItems = new ArrayList<>();
    int empty = 0;
    int nonWeidian = 0;
    int noItemId = 0;
    int noShop = 0;
    List<Sample> samples = new ArrayList<>();
    for (int row : rowNumbers) {
      String name = text(cells, source.getNameColumn(), row);
      String link = SheetLinkParser.link(cells.get(source.getLinkColumn()).get(row));
      if (link == null) {
        empty++;
        continue;
      }
      String decoded = SheetLinkParser.decode(link);
      String reason = null;
      String itemId = null;
      String shopId = null;
      if (SheetLinkParser.isOtherPlatform(decoded)) {
        reason = "NON_WEIDIAN";
        nonWeidian++;
      } else if ((itemId = SheetLinkParser.itemId(decoded)) == null) {
        reason = "NO_ITEM_ID";
        noItemId++;
      } else if ((shopId = shopOf(decoded, defaultShop)) == null) {
        reason = "NO_SHOP";
        noShop++;
      }
      if (reason != null) {
        if (samples.size() < SAMPLE_LIMIT) {
          samples.add(new Sample(row, reason, name));
        }
        continue;
      }
      List<String> cellsOut = new ArrayList<>(List.of("", "", "", "", "", "", "", ""));
      cellsOut.set(ProductImportColumn.SHOP_ID.ordinal(), shopId);
      cellsOut.set(ProductImportColumn.WEIDIAN_ITEM_ID.ordinal(), itemId);
      cellsOut.set(ProductImportColumn.NAME.ordinal(), name);
      if (source.getCategoryColumn() != null) {
        cellsOut.set(
            ProductImportColumn.CATEGORY.ordinal(), text(cells, source.getCategoryColumn(), row));
      }
      if (source.getImageColumn() != null) {
        cellsOut.set(
            ProductImportColumn.IMAGE_URL.ordinal(),
            SheetLinkParser.image(cells.get(source.getImageColumn()).get(row)));
      }
      cellsOut.set(
          ProductImportColumn.PRICE.ordinal(),
          SheetLinkParser.price(
              text(cells, source.getPriceColumn(), row), source.getPriceCurrency()));
      rows.add(List.copyOf(cellsOut));
      parsedItems.add(new Parsed.Item(shopId, itemId, row));
    }
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.SHEET_SOURCE_EMPTY);
    }
    return new Parsed(
        rows, parsedItems, new Skipped(empty, nonWeidian, noItemId, noShop, List.copyOf(samples)));
  }

  /** Shop from the link's host, otherwise the source's default shop; null when neither. */
  private static String shopOf(String decoded, String defaultShop) {
    String fromLink = SheetLinkParser.shopId(decoded);
    return fromLink != null ? fromLink : defaultShop;
  }

  private static List<String> columns(SheetSourceDO s) {
    Set<String> columns = new LinkedHashSet<>();
    columns.add(s.getLinkColumn());
    columns.add(s.getNameColumn());
    columns.add(s.getPriceColumn());
    if (s.getCategoryColumn() != null) {
      columns.add(s.getCategoryColumn());
    }
    if (s.getImageColumn() != null) {
      columns.add(s.getImageColumn());
    }
    return List.copyOf(columns);
  }

  private static String text(
      Map<String, Map<Integer, SheetCellDTO>> cells, String column, int row) {
    SheetCellDTO cell = cells.get(column).get(row);
    return cell == null ? "" : cell.text().strip();
  }

  private String defaultShopWeidianId(SheetSourceDO source) {
    if (source.getDefaultShopId() == null) {
      return null;
    }
    ShopDO shop = shops.getById(source.getDefaultShopId());
    return shop == null ? null : shop.getWeidianShopId();
  }

  private void recordError(Long id, ErrorCode code) {
    sources.update(
        null,
        new LambdaUpdateWrapper<SheetSourceDO>()
            .eq(SheetSourceDO::getId, id)
            .set(SheetSourceDO::getLastError, code.name()));
  }

  private void apply(SheetSourceDO row, SaveCommand c) {
    String name = c.sourceName() == null ? "" : c.sourceName().strip();
    String title = c.sheetTitle() == null ? "" : c.sheetTitle().strip();
    String sheetId = spreadsheetId(c.sheet());
    String link = column(c.linkColumn(), true);
    String nameColumn = column(c.nameColumn(), true);
    String price = column(c.priceColumn(), true);
    String category = column(c.categoryColumn(), false);
    String image = column(c.imageColumn(), false);
    String currency = c.priceCurrency() == null ? "" : c.priceCurrency().strip();
    if (name.isEmpty()
        || name.length() > 64
        || title.isEmpty()
        || title.length() > 100
        || sheetId == null
        || c.headerRow() == null
        || c.headerRow() < 1
        || c.headerRow() > 100
        || !CURRENCY.matcher(currency).matches()
        || new HashSet<>(List.of(link, nameColumn, price)).size() < 3) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (c.defaultShopId() != null) {
      shops.require(c.defaultShopId());
    }
    row.setSourceName(name);
    row.setSpreadsheetId(sheetId);
    row.setSheetTitle(title);
    row.setHeaderRow(c.headerRow());
    row.setLinkColumn(link);
    row.setNameColumn(nameColumn);
    row.setPriceColumn(price);
    row.setCategoryColumn(category);
    row.setImageColumn(image);
    row.setDefaultShopId(c.defaultShopId());
    row.setPriceCurrency(currency);
  }

  /** Spreadsheet ID from a sheet address ({@code …/spreadsheets/d/{id}/…}) or a bare ID. */
  static String spreadsheetId(String sheet) {
    if (sheet == null) {
      return null;
    }
    String value = sheet.strip();
    Matcher m = SHEET_URL.matcher(value);
    if (m.find()) {
      return m.group(1);
    }
    return SHEET_ID.matcher(value).matches() ? value : null;
  }

  private static String column(String value, boolean required) {
    String v = value == null ? "" : value.strip();
    if (v.isEmpty()) {
      if (required) {
        throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
      }
      return null;
    }
    if (!COLUMN.matcher(v).matches()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return v;
  }

  private SheetSourceDO require(Long id) {
    SheetSourceDO row = sources.selectById(id);
    if (row == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    return row;
  }

  private String shopName(Long shopId) {
    ShopDO shop = shops.getById(shopId);
    return shop == null ? null : shop.getShopName();
  }

  private SourceDTO toDto(SheetSourceDO row) {
    Skipped skipped =
        row.getLastSkipped() == null ? null : json.readValue(row.getLastSkipped(), Skipped.class);
    return new SourceDTO(
        row.getId(),
        row.getSourceName(),
        row.getSpreadsheetId(),
        row.getSheetTitle(),
        row.getHeaderRow(),
        row.getLinkColumn(),
        row.getNameColumn(),
        row.getPriceColumn(),
        row.getCategoryColumn(),
        row.getImageColumn(),
        row.getDefaultShopId(),
        row.getDefaultShopId() == null ? null : shopName(row.getDefaultShopId()),
        row.getPriceCurrency(),
        row.getStatus(),
        row.getLastReadAt(),
        row.getLastTaskId(),
        row.getLastRowCount(),
        skipped,
        row.getLastError());
  }
}
