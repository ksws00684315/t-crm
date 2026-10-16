package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolOwnershipApi;
import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.product.api.KolItemRefDTO;
import com.tcrm.admin.product.api.OrderMappingApi;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderAttributionTest {
  private final OrderMappingApi mappings = mock(OrderMappingApi.class);
  private final KolOwnershipApi ownership = mock(KolOwnershipApi.class);
  private final OrderAttribution service = new OrderAttribution(mappings, ownership);

  static OrderDO order() {
    var o = new OrderDO();
    o.setShopId(1L);
    o.setPayTime(Instant.parse("2026-10-16T00:00:00Z"));
    return o;
  }

  static OrderItemDO line(String id) {
    var i = new OrderItemDO();
    i.setWeidianItemId(id);
    i.setAttributionType(0);
    return i;
  }

  private void match(String id, long kol) {
    when(mappings.findAt(eq(1L), eq(id), any()))
        .thenReturn(List.of(new KolItemRefDTO(kol + 100, kol, 10L, 1L)));
  }

  @Test
  void shouldLeaveUnpaidOrdersPending_whenPayTimeIsMissing() {
    var o = order();
    o.setPayTime(null);
    service.apply(o, List.of(line("90")), false);
    assertThat(o.getAttributionStatus()).isEqualTo("PENDING");
    verifyNoInteractions(mappings, ownership);
  }

  @Test
  void shouldAttributeOneKolAndSnapshotBd_whenEveryLineMatches() {
    match("90", 1);
    match("91", 1);
    when(ownership.bdAt(1L, order().getPayTime())).thenReturn(8L);
    var o = order();
    service.apply(o, List.of(line("90"), line("91")), false);
    assertThat(o.getKolId()).isEqualTo(1);
    assertThat(o.getBdUserId()).isEqualTo(8);
    assertThat(o.getAttributionStatus()).isEqualTo("ATTRIBUTED");
  }

  @Test
  void shouldKeepPartialKol_whenOneLineIsUnmatched() {
    match("90", 1);
    when(ownership.bdAt(1L, order().getPayTime())).thenReturn(null);
    var o = order();
    service.apply(o, List.of(line("90"), line("91")), false);
    assertThat(o.getAttributionStatus()).isEqualTo("UNMATCHED");
    assertThat(o.getKolId()).isEqualTo(1);
    assertThat(o.getBdUserId()).isNull();
  }

  @Test
  void shouldFlagAllMatchedLines_whenMoreThanOneKolMatches() {
    match("90", 1);
    match("91", 2);
    var o = order();
    var lines = List.of(line("90"), line("91"), line("92"));
    service.apply(o, lines, false);
    assertThat(o.getAttributionStatus()).isEqualTo("CONFLICT");
    assertThat(o.getKolId()).isNull();
    assertThat(lines.stream().map(i -> i.getAttributionType()).toList()).containsExactly(4, 4, 0);
  }

  @Test
  void shouldPreserveAssignedLines_whenSyncingAgain() {
    var o = order();
    var i = line("90");
    i.setKolId(1L);
    i.setAttributionType(2);
    i.setManualReason("test reason");
    service.apply(o, List.of(i), false);
    assertThat(i.getAttributionType()).isEqualTo(2);
    assertThat(i.getManualReason()).isEqualTo("test reason");
    verifyNoInteractions(mappings);
  }

  @Test
  void shouldResetAssignments_whenExplicitlyReattributing() {
    match("90", 2);
    var o = order();
    var i = line("90");
    i.setKolId(1L);
    i.setAttributionType(2);
    i.setManualReason("old");
    service.apply(o, List.of(i), true);
    assertThat(i.getKolId()).isEqualTo(2);
    assertThat(i.getManualReason()).isNull();
    assertThat(i.getAttributionType()).isEqualTo(1);
  }

  @Test
  void shouldMarkNonKolAndPreserveDecision_whenMarkedManually() {
    var o = order();
    var lines = List.of(line("90"));
    service.manual(o, lines, null, "Non creator");
    service.apply(o, lines, false);
    assertThat(o.getAttributionStatus()).isEqualTo("NON_KOL");
    assertThat(o.getKolId()).isNull();
    assertThat(lines.getFirst().getAttributionType()).isEqualTo(3);
    verifyNoInteractions(mappings, ownership);
  }

  @Test
  void shouldAssignEntireOrder_whenChosenManually() {
    var o = order();
    var lines = List.of(line("90"), line("91"));
    service.manual(o, lines, 3L, "Confirmed");
    assertThat(o.getAttributionStatus()).isEqualTo("ATTRIBUTED");
    assertThat(lines.stream().map(i -> i.getKolId()).toList()).containsExactly(3L, 3L);
    assertThat(lines.getFirst().getAttributionType()).isEqualTo(2);
  }

  @Test
  void shouldLeaveNoKol_whenNothingMatches() {
    var o = order();
    service.apply(o, List.of(line("90")), false);
    assertThat(o.getAttributionStatus()).isEqualTo("UNMATCHED");
    assertThat(o.getKolId()).isNull();
  }

  @Test
  void shouldLeaveEmptyOrMixedNonKolOrdersUnmatched_whenNotCompletelyAssigned() {
    var o = order();
    service.apply(o, List.of(), false);
    assertThat(o.getAttributionStatus()).isEqualTo("UNMATCHED");
    var i = line("90");
    i.setAttributionType(3);
    service.apply(o, List.of(i, line("91")), false);
    assertThat(o.getAttributionStatus()).isEqualTo("UNMATCHED");
    o.setPayTime(null);
    service.manual(o, List.of(i), 3L, "test");
    assertThat(o.getBdUserId()).isNull();
  }

  @Test
  void shouldClearAnotherKolsMappingReference_whenAssignedManually() {
    var o = order();
    var i = line("90");
    i.setKolId(1L);
    i.setKolItemId(10L);
    service.manual(o, List.of(i), 2L, "Confirmed different creator");
    assertThat(i.getKolItemId()).isNull();
  }
}
