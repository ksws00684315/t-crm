package com.tcrm.admin.kol.api;

import com.tcrm.admin.kol.model.CopyTokenDisplayStatus;
import com.tcrm.admin.kol.service.CopyTokenFilterDTO;
import com.tcrm.admin.kol.service.CopyTokenService;
import com.tcrm.admin.kol.service.KolFilterDTO;
import com.tcrm.admin.kol.service.KolService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KolWorkbenchApi {
  private final KolService kols;
  private final CopyTokenService tokens;

  public KolWorkbenchApi(KolService kols, CopyTokenService tokens) {
    this.kols = kols;
    this.tokens = tokens;
  }

  public Counts counts() {
    return new Counts(kols.page(filter(null, 1)).total(), kols.page(filter("APPROVED", 1)).total());
  }

  public Pending pending() {
    var page = kols.page(filter("PENDING", 5));
    var inviters = kols.registrationInviters(page.items());
    return new Pending(
        page.total(),
        page.items().stream()
            .map(
                row ->
                    new Review(
                        row.kolCode(), row.name(), inviters.get(row.id()), row.createTime()))
            .toList());
  }

  public long activeTokens() {
    return tokens
        .page(new CopyTokenFilterDTO(null, null, null, CopyTokenDisplayStatus.ACTIVE, 1, 1))
        .total();
  }

  private static KolFilterDTO filter(String reviewStatus, int size) {
    return new KolFilterDTO(
        null, null, null, null, null, null, null, reviewStatus, null, null, 1, size);
  }

  public record Counts(long visible, long approved) {}

  public record Pending(long total, List<Review> rows) {}

  public record Review(String code, String name, String inviter, Instant createdAt) {}
}
