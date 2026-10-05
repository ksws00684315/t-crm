package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LinkIndexServiceTest {
  private final KolItemApi items = mock(KolItemApi.class);
  private final KolApi kols = mock(KolApi.class);
  private final LinkIndexStore store = mock(LinkIndexStore.class);
  private final LinkIndexService service = new LinkIndexService(items, kols, store);

  private static final KolRefDTO ALICE = new KolRefDTO(10L, "KOL0001", "Alice", true, null);
  private static final KolRefDTO BOB = new KolRefDTO(11L, "KOL0002", "Bob", false, null);

  private static KolItemLinkDTO link(long id, long kolId, String code, String item) {
    return new KolItemLinkDTO(id, kolId, 20L, code, "name", 1L, item, true, true);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> fieldsWritten(String kolCode) {
    ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
    verify(store).putAll(eq(kolCode), fields.capture());
    return fields.getValue();
  }

  @Test
  void shouldWriteField_whenMappingIsCurrent() {
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(ALICE));
    when(items.getCurrentLink(10L, 20L)).thenReturn(link(5L, 10L, "P000001", "9002"));

    assertThat(service.refreshMapping(10L, 20L)).isEqualTo(1);

    assertThat(fieldsWritten("KOL0001").get("P000001"))
        .contains("\"kolItemId\":\"5\"")
        .contains("itemID=9002")
        .contains("\"status\":1");
  }

  @Test
  void shouldDeleteField_whenMappingNoLongerCurrent() {
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(ALICE));
    when(items.getProductCode(20L)).thenReturn("P000001");

    assertThat(service.refreshMapping(10L, 20L)).isZero();

    verify(store).remove("KOL0001", "P000001");
    verify(store, never()).putAll(anyString(), anyMap());
  }

  @Test
  void shouldIgnoreMapping_whenKolOrProductUnknown() {
    when(kols.listByIds(List.of(10L))).thenReturn(List.of(ALICE));
    assertThat(service.refreshMapping(10L, 20L)).isZero();
    assertThat(service.refreshMapping(99L, 20L)).isZero();
    verify(store, never()).remove(anyString(), anyString());
  }

  @Test
  void shouldRewriteAllKolFieldsWithStatusZero_whenKolDisabled() {
    when(kols.listByIds(List.of(11L))).thenReturn(List.of(BOB));
    when(items.listCurrentLinksByKol(11L))
        .thenReturn(List.of(link(1L, 11L, "P1", "91"), link(2L, 11L, "P2", "92")));

    assertThat(service.refreshKol(11L)).isEqualTo(2);

    Map<String, String> fields = fieldsWritten("KOL0002");
    assertThat(fields).containsOnlyKeys("P1", "P2");
    assertThat(fields.values()).allSatisfy(v -> assertThat(v).contains("\"status\":0"));
  }

  @Test
  void shouldSkipKol_whenKolUnknown() {
    assertThat(service.refreshKol(99L)).isZero();
    verifyNoInteractions(store);
  }

  @Test
  void shouldGroupFieldsByKol_whenProductStatusChanges() {
    when(items.listCurrentLinksByProduct(20L))
        .thenReturn(List.of(link(1L, 10L, "P1", "91"), link(2L, 11L, "P1", "92")));
    when(kols.listByIds(Set.of(10L, 11L))).thenReturn(List.of(ALICE, BOB));

    assertThat(service.refreshProduct(20L)).isEqualTo(2);

    assertThat(fieldsWritten("KOL0001")).containsOnlyKeys("P1");
    assertThat(fieldsWritten("KOL0002")).containsOnlyKeys("P1");
  }

  @Test
  void shouldRewriteShopFieldsKolByKol_whenShopStatusChanges() {
    when(kols.listAll()).thenReturn(List.of(ALICE, BOB));
    when(items.listCurrentLinksByKolAndShop(10L, 1L))
        .thenReturn(List.of(link(1L, 10L, "P1", "91")));
    when(items.listCurrentLinksByKolAndShop(11L, 1L)).thenReturn(List.of());

    assertThat(service.refreshShop(1L)).isEqualTo(1);

    assertThat(fieldsWritten("KOL0001")).containsOnlyKeys("P1");
    assertThat(fieldsWritten("KOL0002")).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldReplaceEveryKolAndDropOrphans_whenRebuilding() {
    when(kols.listAll()).thenReturn(List.of(ALICE, BOB));
    when(items.listCurrentLinksByKol(10L))
        .thenReturn(List.of(link(1L, 10L, "P1", "91"), link(2L, 10L, "P2", "92")));
    when(items.listCurrentLinksByKol(11L)).thenReturn(List.of());
    when(store.listIndexedKolCodes()).thenReturn(Set.of("KOL0001", "KOL0404"));
    when(store.deleteStagingKeys()).thenReturn(1);

    LinkIndexService.RebuildResult result = service.rebuildAll();

    ArgumentCaptor<Map<String, String>> alice = ArgumentCaptor.forClass(Map.class);
    verify(store).replace(eq("KOL0001"), alice.capture());
    assertThat(alice.getValue()).containsOnlyKeys("P1", "P2");
    verify(store).replace("KOL0002", Map.of());
    verify(store).delete("KOL0404");
    verify(store, never()).delete("KOL0001");
    assertThat(result.kols()).isEqualTo(2);
    assertThat(result.fields()).isEqualTo(2);
    assertThat(result.orphanKeys()).isEqualTo(1);
  }

  @Test
  void shouldNotTouchStore_whenProductHasNoCurrentMappings() {
    when(items.listCurrentLinksByProduct(20L)).thenReturn(List.of());
    when(kols.listByIds(any())).thenReturn(List.of());
    assertThat(service.refreshProduct(20L)).isZero();
    verifyNoInteractions(store);
  }
}
