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

  public byte[] currentPage(
      AnalyticsRankingService.Board board, AnalyticsRankingService.Result result) {
    var meta = result.meta();
    var filters = meta.filters();
    var metadata =
        List.of(
            List.of(
                label("start"),
                meta.period().startDate().toString(),
                label("end"),
                meta.period().endDate().toString(),
                label("zone"),
                meta.timezone()),
            List.of(
                label("time"),
                ZonedDateTime.now(StatsPeriod.ZONE).toOffsetDateTime().toString(),
                label("scope"),
                meta.dataScope().name()),
            List.of(
                label("shop"),
                text(filters.shopId()),
                label("creator"),
                text(filters.kolId()),
                label("inviter"),
                text(filters.inviterId()),
                label("channel"),
                filters.channel(),
                label("keyword"),
                text(result.keyword())),
            List.of(
                label("page"),
                text(result.ranking().page()),
                label("size"),
                text(result.ranking().size()),
                label("sort"),
                result.sortBy(),
                label("direction"),
                result.sortDirection()));
    if (board == AnalyticsRankingService.Board.PRODUCT) {
      return exportProducts(result, metadata);
    }
    if (board == AnalyticsRankingService.Board.CREATOR) {
      return exportCreators(result, metadata);
    }
    var headers =
        List.of(
            label("inviter"),
            label("status"),
            label("creatorCounts"),
            label("units"),
            label("sales"),
            label("refund"),
            label("net"),
            label("amountRefundRate"),
            label("average"),
            label("prepayExpense"),
            label("totalEstimatedCommission"),
            label("totalExpense"));
    var rows =
        result.ranking().items().stream()
            .map(
                row -> {
                  var m = row.metrics();
                  return List.of(
                      join(row.name(), row.code()),
                      status(row.enabled()),
                      text(row.effectiveCount()) + " / " + text(row.invitedCount()),
                      text(m.units()),
                      money(m.salesAmount()),
                      money(m.completedRefundAmount()),
                      money(m.netSalesAmount()),
                      percent(m.amountRefundRate()),
                      money(m.averageOrderAmount()),
                      "",
                      "",
                      "");
                })
            .toList();
    return excel.export(metadata, headers, rows);
  }

  private byte[] exportCreators(
      AnalyticsRankingService.Result result, List<List<String>> metadata) {
    var headers =
        List.of(
            label("creatorIdentity"),
            label("creatorRelationship"),
            label("orderCount"),
            label("units"),
            label("linkCount"),
            label("sales"),
            label("refund"),
            label("net"),
            label("average"),
            label("amountRefundRate"),
            label("commissionRate"),
            label("estimatedCommission"),
            label("commissionExpense"),
            label("cumulativeCommission"));
    var rows =
        result.ranking().items().stream()
            .map(
                row -> {
                  var m = row.metrics();
                  String name = text(row.name());
                  if (!name.isBlank() && row.primaryPlatform() != null) {
                    name += " (" + row.primaryPlatform() + ")";
                  }
                  String identity =
                      join(
                          name,
                          row.social() == null ? null : String.join(" / ", row.social()),
                          row.code());
                  String relation =
                      row.relationStatus() == null
                          ? null
                          : label("relation." + row.relationStatus());
                  return List.of(
                      identity,
                      join(row.related(), relation),
                      text(m.paidOrderCount()),
                      text(m.units()),
                      text(row.linkCount()),
                      money(m.salesAmount()),
                      money(m.completedRefundAmount()),
                      money(m.netSalesAmount()),
                      money(m.averageOrderAmount()),
                      percent(m.amountRefundRate()),
                      percent(row.commissionRate()),
                      "",
                      "",
                      "");
                })
            .toList();
    return excel.export(metadata, headers, rows);
  }

  private static String join(String... parts) {
    return String.join(
        " / ",
        java.util.Arrays.stream(parts).filter(part -> part != null && !part.isBlank()).toList());
  }

  private static String percent(BigDecimal rate) {
    return rate == null ? "" : rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
  }

  private byte[] exportProducts(
      AnalyticsRankingService.Result result, List<List<String>> metadata) {
    var headers =
        List.of(
            label("rank"),
            label("productName"),
            label("channelItemId"),
            label("shop"),
            label("sellingCreators"),
            label("units"),
            label("orderCount"),
            label("refundUnits"),
            label("sales"),
            label("refund"),
            label("net"),
            label("settlementAmount"));
    var rows =
        result.ranking().items().stream()
            .map(
                row -> {
                  var m = row.metrics();
                  return List.of(
                      text(row.rank()),
                      text(row.name()),
                      row.sourceItemId() == null
                          ? label("weidian")
                          : label("weidian") + " / " + row.sourceItemId(),
                      text(row.related()),
                      text(row.kolCount()),
                      text(m.units()),
                      text(m.paidOrderCount()),
                      "",
                      money(m.salesAmount()),
                      money(m.completedRefundAmount()),
                      money(m.netSalesAmount()),
                      "");
                })
            .toList();
    return excel.export(metadata, headers, rows);
  }

  private String status(Boolean enabled) {
    return enabled == null ? "" : label(enabled ? "active" : "inactive");
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
