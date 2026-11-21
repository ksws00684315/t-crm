package com.tcrm.admin.kol.api;

import com.tcrm.admin.kol.service.KolFilterDTO;
import com.tcrm.admin.kol.service.KolService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KolWorkbenchApi {
  private final KolService kols;

  public KolWorkbenchApi(KolService kols) {
    this.kols = kols;
  }

  public Counts counts(Instant dayStart, Instant asOf) {
    return new Counts(
        kols.workbenchCreatorCount(null, asOf), kols.workbenchCreatorCount(dayStart, asOf));
  }

  public long promoting() {
    return kols.page(
            new KolFilterDTO(
                null, null, null, null, null, null, null, null, "PROMOTING", null, 1, 1))
        .total();
  }

  public Pending pending() {
    var page = kols.page(filter("PENDING", 5));
    var inviters = kols.registrationInviters(page.items());
    return new Pending(
        page.total(),
        page.items().stream()
            .map(
                row ->
                    new Review(row.kolCode(), row.name(), inviters.get(row.id()), row.createTime()))
            .toList());
  }

  private static KolFilterDTO filter(String reviewStatus, int size) {
    return new KolFilterDTO(
        null, null, null, null, null, null, null, reviewStatus, null, null, 1, size);
  }

  public record Counts(long visible, long today) {}

  public record Pending(long total, List<Review> rows) {}

  public record Review(String code, String name, String inviter, Instant createdAt) {}
}
