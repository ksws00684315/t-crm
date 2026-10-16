package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.link.model.ClickCountRow;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Link list of one KOL (task 06 §5): current mappings ordered by original product code, with link
 * status and click counts of the last 30 days for the current page only. Counts belong to the link
 * (KOL + original product), so they survive a change of the exclusive item. A BD (OPERATOR + OWN)
 * sees only the KOLs assigned to them (403 otherwise).
 */
@Service
public class LinkService {
  static final Duration CLICK_PERIOD = Duration.ofDays(30);
  private final KolApi kols;
  private final KolItemApi kolItems;
  private final ClickLogMapper clickLogs;
  private final LinkUrls urls;
  private final CurrentUserContext current;
  private final Clock clock;

  @Autowired
  public LinkService(
      KolApi kols,
      KolItemApi kolItems,
      ClickLogMapper clickLogs,
      LinkUrls urls,
      CurrentUserContext current) {
    this(kols, kolItems, clickLogs, urls, current, Clock.systemUTC());
  }

  LinkService(
      KolApi kols,
      KolItemApi kolItems,
      ClickLogMapper clickLogs,
      LinkUrls urls,
      CurrentUserContext current,
      Clock clock) {
    this.kols = kols;
    this.kolItems = kolItems;
    this.clickLogs = clickLogs;
    this.urls = urls;
    this.current = current;
    this.clock = clock;
  }

  /** 404 when the KOL does not exist; 403 when a BD asks for a KOL assigned to someone else. */
  public PageResult<LinkDTO> page(LinkFilterDTO filter) {
    AccountDTO user = current.user();
    KolRefDTO kol = kols.getByCode(filter.kolCode());
    if (kol == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (own(user) && !user.id().equals(kol.bdUserId())) {
      throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
    }
    String productCode =
        filter.productCode() == null || filter.productCode().isBlank()
            ? null
            : filter.productCode().trim();
    PageResult<KolItemLinkDTO> links =
        kolItems.pageCurrentLinksByKol(kol.id(), productCode, filter.page(), filter.size());
    Map<Long, ClickCountRow> counts = counts(kol.id(), links.items());
    return links.map(link -> toDto(kol, link, counts.get(link.productId())));
  }

  private static boolean own(AccountDTO user) {
    return user.role() == Role.OPERATOR && user.dataScope() == DataScope.OWN;
  }

  /** Counts keyed by original product ID; a KOL has at most one current link per product. */
  private Map<Long, ClickCountRow> counts(long kolId, List<KolItemLinkDTO> links) {
    if (links.isEmpty()) {
      return Map.of();
    }
    var productIds = links.stream().map(KolItemLinkDTO::productId).toList();
    return clickLogs.countSince(kolId, productIds, clock.instant().minus(CLICK_PERIOD)).stream()
        .collect(Collectors.toMap(ClickCountRow::getProductId, Function.identity()));
  }

  private LinkDTO toDto(KolRefDTO kol, KolItemLinkDTO link, ClickCountRow count) {
    var reasons = LinkRules.reasons(kol.enabled(), link);
    return new LinkDTO(
        link.kolItemId(),
        link.productCode(),
        link.productName(),
        link.weidianItemId(),
        urls.linkUrl(kol.kolCode(), link.productCode()),
        LinkUrls.targetUrl(kol, link.weidianItemId()),
        reasons.isEmpty(),
        reasons,
        count == null ? 0 : count.getTotal(),
        count == null ? 0 : count.getValid());
  }
}
