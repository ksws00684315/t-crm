package com.tcrm.admin.stats.service;

import com.tcrm.admin.stats.service.ShopPerformanceService.ShopPerformanceDTO;
import com.tcrm.admin.task.api.ExcelExportApi;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

/** Formats the authorized ranking page and the applied filter metadata for export. */
@Service
public class ShopPerformanceExport {
  private final ExcelExportApi excel;
  private final MessageSource messages;

  public ShopPerformanceExport(ExcelExportApi excel, MessageSource messages) {
    this.excel = excel;
    this.messages = messages;
  }

  public byte[] currentPage(ShopPerformanceDTO result) {
    var meta = result.meta();
    var filters = meta.filters();
    var metadata =
        List.of(
            List.of(label("start"), meta.period().startDate().toString()),
            List.of(label("end"), meta.period().endDate().toString()),
            List.of(label("zone"), meta.timezone()),
            List.of(
                label("time"), ZonedDateTime.now(StatsPeriod.ZONE).toOffsetDateTime().toString()),
            List.of(label("scope"), meta.dataScope().name()),
            List.of(
                label("shop"),
                text(filters.shopId()),
                label("creator"),
                text(filters.kolId()),
                label("inviter"),
                text(filters.inviterId()),
                label("channel"),
                filters.channel()),
            List.of(
                label("page"),
                text(result.ranking().page()),
                label("size"),
                text(result.ranking().size())),
            List.of(label("sort"), result.sortBy(), label("direction"), result.sortDirection()));
    var headers =
        List.of(
            label("code"),
            label("shop"),
            label("status"),
            label("orders"),
            label("units"),
            "SPU",
            "SKU",
            label("creators"),
            label("sales"),
            label("refund"),
            label("net"),
            label("average"));
    var rows =
        result.ranking().items().stream()
            .map(
                row ->
                    List.of(
                        text(row.shopCode()),
                        row.shopName(),
                        text(row.status()),
                        text(row.paidOrderCount()),
                        text(row.units()),
                        text(row.spuCount()),
                        text(row.skuCount()),
                        text(row.kolCount()),
                        money(row.salesAmount()),
                        money(row.completedRefundAmount()),
                        money(row.netSalesAmount()),
                        money(row.averageOrderAmount())))
            .toList();
    return excel.export(metadata, headers, rows);
  }

  private static String text(Object value) {
    return Objects.toString(value, "");
  }

  private String label(String key) {
    return messages.getMessage("stats.export." + key, null, LocaleContextHolder.getLocale());
  }

  private static String money(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }
}
