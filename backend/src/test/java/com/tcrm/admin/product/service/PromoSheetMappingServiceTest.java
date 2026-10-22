package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.PromoSheetClassification;
import com.tcrm.admin.product.api.PromoSheetConfirmResult;
import com.tcrm.admin.product.api.PromoSheetRowDTO;
import com.tcrm.admin.product.api.PromoSheetSkipReason;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.KolItemOwnerRow;
import com.tcrm.admin.product.model.KolItemShopRow;
import com.tcrm.admin.product.model.PromoProductRow;
import com.tcrm.admin.security.api.CurrentUserContext;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link PromoSheetMappingService} classification and confirmation with mocked persistence: the
 * rule table of task 26 §3.3, one category at a time.
 */
class PromoSheetMappingServiceTest {
  private static final String KAKOBUY_A =
      "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop111.v.weidian.com"
          + "%2Fitem.html%3FitemID%3D1111111111";
  private static final String KAKOBUY_B =
      "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop111.v.weidian.com"
          + "%2Fitem.html%3FitemID%3D2222222222";
  private static final String KAKOBUY_C =
      "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop111.v.weidian.com"
          + "%2Fitem.html%3FitemID%3D3333333333";
  private static final String KAKOBUY_UNREGISTERED =
      "https://item.kakobuy.com/item/details?url=https%3A%2F%2Fshop999.v.weidian.com"
          + "%2Fitem.html%3FitemID%3D4444444444";
  private static final String DISABLED_SHOP =
      "https://shop222.v.weidian.com/item.html?itemID=5555555555";

  private KolItemMapper items;
  private ProductMapper products;
  private ShopAccessApi shops;
  private KolApi kols;
  private CurrentUserContext current;
  private ApplicationEventPublisher events;
  private PromoSheetMappingService service;

  @BeforeEach
  void setUp() {
    items = mock(KolItemMapper.class);
    products = mock(ProductMapper.class);
    shops = mock(ShopAccessApi.class);
    kols = mock(KolApi.class);
    current = mock(CurrentUserContext.class);
    events = mock(ApplicationEventPublisher.class);
    when(current.id()).thenReturn(7L);
    when(shops.listShops())
        .thenReturn(
            List.of(
                new ShopAccessDTO(11L, "Main Shop", "111", 1, null),
                new ShopAccessDTO(12L, "Closed Shop", "222", 0, null)));
    // No current owners or products unless a test says otherwise.
    when(items.listCurrentOwners(anyLong(), anyCollection())).thenReturn(List.of());
    when(products.listByShopItems(anyLong(), anyCollection())).thenReturn(List.of());
    when(items.listCurrentItemsWithShop(anyLong())).thenReturn(List.of());
    when(kols.listByIds(anyCollection()))
        .thenAnswer(
            inv -> {
              Collection<Long> ids = inv.getArgument(0);
              return ids.stream()
                  .map(id -> new KolRefDTO(id, "KOL-" + id, "Owner " + id, true, null))
                  .toList();
            });
    service =
        new PromoSheetMappingService(
            items, products, shops, kols, current, new TransactionTemplate(noopManager()), events);
  }

  private PromoSheetRowDTO row(String link, String name) {
    return new PromoSheetRowDTO("ARCHIVE style", 3, link, false, name);
  }

  @Test
  void shouldClassifyNewItemsWithProductCodesAndSnapshots() {
    PromoProductRow product = new PromoProductRow();
    product.setWeidianItemId("1111111111");
    product.setProductId(99L);
    product.setProductCode("P000001");
    when(products.listByShopItems(anyLong(), anyCollection())).thenReturn(List.of(product));

    PromoSheetClassification result = service.classify(5L, List.of(row(KAKOBUY_A, "Silver Ring")));

    assertThat(result.toAdd()).isEqualTo(1);
    assertThat(result.newItems()).hasSize(1);
    assertThat(result.newItems().getFirst().weidianItemId()).isEqualTo("1111111111");
    assertThat(result.newItems().getFirst().shopWeidianId()).isEqualTo("111");
    assertThat(result.newItems().getFirst().itemName()).isEqualTo("Silver Ring");
    assertThat(result.newItems().getFirst().productCode()).isEqualTo("P000001");
    assertThat(result.existing()).isZero();
    assertThat(result.conflicts()).isZero();
    assertThat(result.notInSheet()).isZero();
    assertThat(result.skipCounts()).isEmpty();
  }

  @Test
  void shouldDeduplicateRepeatedItems() {
    PromoSheetClassification result =
        service.classify(5L, List.of(row(KAKOBUY_A, "First"), row(KAKOBUY_A, "Second")));

    assertThat(result.toAdd()).isEqualTo(1);
    assertThat(result.duplicates()).isEqualTo(1);
    assertThat(result.linkRows()).isEqualTo(2);
    // The first occurrence wins, like the first row of the customer sample.
    assertThat(result.newItems().getFirst().itemName()).isEqualTo("First");
  }

  @Test
  void shouldSkipByReasonAndKeepSamples() {
    PromoSheetClassification result =
        service.classify(
            5L,
            List.of(
                row("https://item.taobao.com/item.htm?id=1", null),
                row("https://discord.com/invite/abc", null),
                row(KAKOBUY_UNREGISTERED, null),
                row(DISABLED_SHOP, null),
                new PromoSheetRowDTO("ARCHIVE style", 9, null, true, null)));

    assertThat(result.toAdd()).isZero();
    assertThat(result.skipCounts())
        .containsEntry(PromoSheetSkipReason.NON_WEIDIAN, 1)
        .containsEntry(PromoSheetSkipReason.NO_ITEM_ID, 1)
        .containsEntry(PromoSheetSkipReason.SHOP_NOT_REGISTERED, 2)
        .containsEntry(PromoSheetSkipReason.IN_SHEET, 1);
    assertThat(result.skipSamples()).hasSize(5);
    assertThat(result.skipSamples().getFirst().sheet()).isEqualTo("ARCHIVE style");
    assertThat(result.skipSamples().getFirst().row()).isEqualTo(3);
  }

  @Test
  void shouldCapSkipSamplesAtFifty() {
    List<PromoSheetRowDTO> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 60; i++) {
      rows.add(row("https://discord.com/invite/x" + i, null));
    }
    PromoSheetClassification result = service.classify(5L, rows);

    assertThat(result.skipCounts()).containsEntry(PromoSheetSkipReason.NO_ITEM_ID, 60);
    assertThat(result.skipSamples()).hasSize(50);
  }

  @Test
  void shouldReportExistingAndConflictsWithOwners() {
    KolItemOwnerRow mine = new KolItemOwnerRow();
    mine.setWeidianItemId("1111111111");
    mine.setKolId(5L);
    KolItemOwnerRow other = new KolItemOwnerRow();
    other.setWeidianItemId("2222222222");
    other.setKolId(66L);
    when(items.listCurrentOwners(anyLong(), anyCollection())).thenReturn(List.of(mine, other));

    PromoSheetClassification result =
        service.classify(5L, List.of(row(KAKOBUY_A, "Mine"), row(KAKOBUY_B, "Theirs")));

    assertThat(result.existing()).isEqualTo(1);
    assertThat(result.conflicts()).isEqualTo(1);
    assertThat(result.conflictItems()).hasSize(1);
    assertThat(result.conflictItems().getFirst().ownerKolCode()).isEqualTo("KOL-66");
    assertThat(result.conflictItems().getFirst().ownerKolName()).isEqualTo("Owner 66");
    assertThat(result.toAdd()).isZero();
  }

  @Test
  void shouldCountMappingMissingFromTheSheet() {
    KolItemShopRow present = new KolItemShopRow();
    present.setWeidianItemId("1111111111");
    present.setWeidianShopId("111");
    KolItemShopRow absent = new KolItemShopRow();
    absent.setWeidianItemId("7777777777");
    absent.setWeidianShopId("111");
    when(items.listCurrentItemsWithShop(anyLong())).thenReturn(List.of(present, absent));

    PromoSheetClassification result = service.classify(5L, List.of(row(KAKOBUY_A, null)));

    assertThat(result.notInSheet()).isEqualTo(1);
  }

  @Test
  void shouldWriteMappingsOnConfirmWithSourceAndSnapshot() {
    PromoProductRow product = new PromoProductRow();
    product.setWeidianItemId("1111111111");
    product.setProductId(99L);
    product.setProductCode("P000001");
    when(products.listByShopItems(anyLong(), anyCollection())).thenReturn(List.of(product));

    PromoSheetConfirmResult result;
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    try {
      result =
          service.confirm(5L, List.of(row(KAKOBUY_A, "Silver Ring"), row(KAKOBUY_C, "No Product")));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    assertThat(result.inserted()).isEqualTo(2);
    ArgumentCaptor<KolItemDO> written = ArgumentCaptor.forClass(KolItemDO.class);
    verify(items, org.mockito.Mockito.times(2)).insert(written.capture());
    List<KolItemDO> mappings = written.getAllValues();
    assertThat(mappings.get(0).getProductId()).isEqualTo(99L);
    assertThat(mappings.get(0).getItemName()).isEqualTo("Silver Ring");
    assertThat(mappings.get(0).getSource()).isEqualTo("SHEET_UPLOAD");
    assertThat(mappings.get(0).getShopId()).isEqualTo(11L);
    assertThat(mappings.get(0).getKolId()).isEqualTo(5L);
    assertThat(mappings.get(0).getValidTo()).isNull();
    assertThat(mappings.get(0).getCreateBy()).isEqualTo(7L);
    // A shop item without a registered original product keeps product_id empty.
    assertThat(mappings.get(1).getProductId()).isNull();
    assertThat(mappings.get(1).getItemName()).isEqualTo("No Product");
    assertThat(mappings).allMatch(row -> row.getLinkCode().matches("KPL-[0-9A-F]{12}"));
    verify(products).lockShopItem(ProductRules.shopItemLockKey(11L, "1111111111"));
    verify(products).lockShopItem(ProductRules.shopItemLockKey(11L, "3333333333"));
    verify(events).publishEvent(any(com.tcrm.admin.product.api.KolMappingsChangedEvent.class));
  }

  @Test
  void shouldNotWriteOrRegisterWhenNothingToAdd() {
    PromoSheetConfirmResult result = service.confirm(5L, List.of());
    assertThat(result.inserted()).isZero();
    verify(items, never()).insert(any(KolItemDO.class));
    verify(events, never()).publishEvent(any());
  }

  @Test
  void shouldRevalidateConflictsAtConfirmTime() {
    // Preview saw a new item; between preview and confirm another KOL took it.
    KolItemOwnerRow owner = new KolItemOwnerRow();
    owner.setWeidianItemId("1111111111");
    owner.setKolId(66L);
    when(items.listCurrentOwners(anyLong(), anyCollection())).thenReturn(List.of(owner));

    PromoSheetConfirmResult result;
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    try {
      result = service.confirm(5L, List.of(row(KAKOBUY_A, "Now theirs")));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    assertThat(result.inserted()).isZero();
    assertThat(result.conflicts()).isEqualTo(1);
    assertThat(result.conflictItems().getFirst().ownerKolCode()).isEqualTo("KOL-66");
    verify(items, never()).insert(any(KolItemDO.class));
    verify(events, never()).publishEvent(any());
  }

  /** Executes the callback directly; the test manages the synchronization state itself. */
  private static PlatformTransactionManager noopManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }
}
