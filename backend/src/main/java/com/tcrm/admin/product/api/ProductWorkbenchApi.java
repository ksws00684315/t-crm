package com.tcrm.admin.product.api;

import com.tcrm.admin.product.service.CopyJobQueryDTO;
import com.tcrm.admin.product.service.CopyJobQueryService;
import com.tcrm.admin.product.service.KolItemFilterDTO;
import com.tcrm.admin.product.service.KolItemService;
import com.tcrm.admin.product.service.ShopService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductWorkbenchApi {
  private final ShopService shops;
  private final KolItemService links;
  private final CopyJobQueryService jobs;

  public ProductWorkbenchApi(ShopService shops, KolItemService links, CopyJobQueryService jobs) {
    this.shops = shops;
    this.links = links;
    this.jobs = jobs;
  }

  public ShopSummary shops() {
    var rows = shops.workbenchShops();
    var enabled = rows.stream().filter(row -> Integer.valueOf(1).equals(row.status())).toList();
    boolean capacityKnown =
        !enabled.isEmpty()
            && enabled.stream()
                .allMatch(
                    row ->
                        row.itemsRefreshedAt() != null
                            && row.currentItems() != null
                            && row.maxItems() != null
                            && row.maxItems() > 0);
    return new ShopSummary(
        enabled.size(),
        rows.stream().filter(row -> "AUTHORIZED".equals(row.authStatus())).count(),
        enabled.stream().filter(row -> !"AUTHORIZED".equals(row.authStatus())).count(),
        capacityKnown ? enabled.stream().mapToLong(row -> row.currentItems()).sum() : null,
        capacityKnown ? enabled.stream().mapToLong(row -> row.maxItems()).sum() : null,
        capacityKnown
            ? enabled.stream()
                .mapToLong(row -> Math.max(0, row.maxItems() - row.currentItems()))
                .sum()
            : null);
  }

  public long activeLinks() {
    return links.page(new KolItemFilterDTO(null, null, null, "ENABLED", 1, 1)).total();
  }

  public FailedJobs failedJobs() {
    var page = jobs.page(new CopyJobQueryDTO(null, null, "HAS_FAILURE", 1, 5));
    return new FailedJobs(
        page.total(),
        page.items().stream()
            .map(
                row ->
                    new Job(
                        row.jobCode(),
                        row.kolName(),
                        row.successCount(),
                        row.totalCount(),
                        row.status()))
            .toList());
  }

  public record ShopSummary(
      long enabled, long authorized, long authExceptions, Long used, Long limit, Long remaining) {}

  public record FailedJobs(long total, List<Job> rows) {}

  public record Job(
      String jobCode, String kolName, int successCount, int totalCount, String status) {}
}
