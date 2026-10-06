package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.link.mapper.ClickLogMapper;
import com.tcrm.admin.link.model.ClickCountRow;
import com.tcrm.admin.link.model.LinkUnavailableReason;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LinkServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
  private final KolApi kols = mock(KolApi.class);
  private final KolItemApi items = mock(KolItemApi.class);
  private final ClickLogMapper clickLogs = mock(ClickLogMapper.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private final LinkService service =
      new LinkService(
          kols,
          items,
          clickLogs,
          new LinkUrls("https://t.example.com"),
          current,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private void actAs(long id, Role role, DataScope scope) {
    when(current.user())
        .thenReturn(new AccountDTO(id, "u" + id, null, "U", null, role, scope, 1, null, null, 0));
  }

  private static KolItemLinkDTO link(long id, String code, boolean onSale, boolean shopEnabled) {
    return new KolItemLinkDTO(
        id, 10L, 20L + id, code, "name " + code, 1L, "90" + id, onSale, shopEnabled);
  }

  private static ClickCountRow count(long productId, long total, long valid) {
    var row = new ClickCountRow();
    row.setProductId(productId);
    row.setTotal(total);
    row.setValid(valid);
    return row;
  }

  @Test
  void shouldReturnLinksWithStatusAndCounts() {
    actAs(1L, Role.FINANCE, DataScope.ALL);
    when(kols.getByCode("KOL0001")).thenReturn(new KolRefDTO(10L, "KOL0001", "A", true, 7L));
    when(items.pageCurrentLinksByKol(10L, null, 1, 20))
        .thenReturn(
            new PageResult<>(
                List.of(link(1L, "P1", true, true), link(2L, "P2", false, false)), 2, 1, 20));
    when(clickLogs.countSince(10L, List.of(21L, 22L), Instant.parse("2026-09-06T00:00:00Z")))
        .thenReturn(List.of(count(21L, 12, 7)));

    var page = service.page(new LinkFilterDTO("KOL0001", " ", 1, 20));

    assertThat(page.total()).isEqualTo(2);
    var first = page.items().get(0);
    assertThat(first.linkUrl()).isEqualTo("https://t.example.com/t/KOL0001/P1");
    assertThat(first.targetUrl()).isEqualTo("https://weidian.com/item.html?itemID=901");
    assertThat(first.available()).isTrue();
    assertThat(first.unavailableReasons()).isEmpty();
    assertThat(first.clicks30d()).isEqualTo(12);
    assertThat(first.validClicks30d()).isEqualTo(7);
    var second = page.items().get(1);
    assertThat(second.available()).isFalse();
    assertThat(second.unavailableReasons())
        .containsExactly(
            LinkUnavailableReason.PRODUCT_OFF_SHELF, LinkUnavailableReason.SHOP_DISABLED);
    assertThat(second.clicks30d()).isZero();
    assertThat(second.validClicks30d()).isZero();
  }

  @Test
  void shouldCountClicksPerKolAndProduct_notPerKolItem() {
    // The exclusive item of P1 was changed: the current mapping row (id 99) is new, but clicks
    // recorded under the old row still belong to the same link (KOL 10 + original product 21).
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(kols.getByCode("KOL0001")).thenReturn(new KolRefDTO(10L, "KOL0001", "A", true, null));
    var replaced = new KolItemLinkDTO(99L, 10L, 21L, "P1", "name P1", 1L, "8888888", true, true);
    when(items.pageCurrentLinksByKol(10L, null, 1, 20))
        .thenReturn(new PageResult<>(List.of(replaced, link(2L, "P2", true, true)), 2, 1, 20));
    when(clickLogs.countSince(10L, List.of(21L, 22L), Instant.parse("2026-09-06T00:00:00Z")))
        .thenReturn(List.of(count(22L, 1, 1), count(21L, 3, 1)));

    var page = service.page(new LinkFilterDTO("KOL0001", null, 1, 20));

    var first = page.items().get(0);
    assertThat(first.kolItemId()).isEqualTo(99L);
    assertThat(first.targetUrl()).isEqualTo("https://weidian.com/item.html?itemID=8888888");
    assertThat(first.clicks30d()).isEqualTo(3);
    assertThat(first.validClicks30d()).isEqualTo(1);
    assertThat(page.items().get(1).clicks30d()).isEqualTo(1);
  }

  @Test
  void shouldMarkKolDisabled_andPassTrimmedProductCode() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    when(kols.getByCode("KOL0001")).thenReturn(new KolRefDTO(10L, "KOL0001", "A", false, null));
    when(items.pageCurrentLinksByKol(10L, "P1", 2, 5))
        .thenReturn(new PageResult<>(List.of(link(1L, "P1", true, true)), 6, 2, 5));

    var page = service.page(new LinkFilterDTO("KOL0001", " P1 ", 2, 5));

    assertThat(page.items().get(0).unavailableReasons())
        .containsExactly(LinkUnavailableReason.KOL_DISABLED);
  }

  @Test
  void shouldNotCountClicks_whenPageIsEmpty() {
    actAs(1L, Role.OPERATOR, DataScope.ALL);
    when(kols.getByCode("KOL0001")).thenReturn(new KolRefDTO(10L, "KOL0001", "A", true, 9L));
    when(items.pageCurrentLinksByKol(eq(10L), isNull(), anyInt(), anyInt()))
        .thenReturn(new PageResult<>(List.of(), 0, 1, 20));

    assertThat(service.page(new LinkFilterDTO("KOL0001", null, 1, 20)).items()).isEmpty();
    verifyNoInteractions(clickLogs);
  }

  @Test
  void shouldReturn404_whenKolUnknown() {
    actAs(1L, Role.SUPER_ADMIN, DataScope.ALL);
    assertThatThrownBy(() -> service.page(new LinkFilterDTO("KOL9999", null, 1, 20)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND));
  }

  @ParameterizedTest
  @CsvSource({"7, true", "8, false"})
  void shouldApplyBdScope(long userId, boolean allowed) {
    actAs(userId, Role.OPERATOR, DataScope.OWN);
    when(kols.getByCode("KOL0001")).thenReturn(new KolRefDTO(10L, "KOL0001", "A", true, 7L));
    when(items.pageCurrentLinksByKol(anyLong(), any(), anyInt(), anyInt()))
        .thenReturn(new PageResult<>(List.of(), 0, 1, 20));

    if (allowed) {
      assertThat(service.page(new LinkFilterDTO("KOL0001", null, 1, 20)).items()).isEmpty();
    } else {
      assertThatThrownBy(() -> service.page(new LinkFilterDTO("KOL0001", null, 1, 20)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
      verify(items, never()).pageCurrentLinksByKol(anyLong(), any(), anyInt(), anyInt());
    }
  }

  @Test
  void shouldForbidBd_whenKolHasNoBd() {
    actAs(7L, Role.OPERATOR, DataScope.OWN);
    when(kols.getByCode("KOL0001")).thenReturn(new KolRefDTO(10L, "KOL0001", "A", true, null));
    assertThatThrownBy(() -> service.page(new LinkFilterDTO("KOL0001", null, 1, 20)))
        .isInstanceOf(BusinessException.class);
  }
}
