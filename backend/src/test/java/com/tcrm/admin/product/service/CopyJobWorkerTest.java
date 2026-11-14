package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.Update;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.product.api.WeidianItemCopyApi.CopyResult;
import com.tcrm.admin.product.api.WeidianItemCopyApi.ProbedItem;
import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import com.tcrm.admin.product.model.CopyJobItemDO;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TestTransactions;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class CopyJobWorkerTest {
  private final CopyJobMapper jobs = mock(CopyJobMapper.class);
  private final CopyJobItemMapper items = mock(CopyJobItemMapper.class);
  private final ShopMapper shops = mock(ShopMapper.class);
  private final ProductMapper products = mock(ProductMapper.class);
  private final KolItemMapper kolItems = mock(KolItemMapper.class);
  private final ProductCodeGenerator codes = mock(ProductCodeGenerator.class);
  private final CopyTokenApi tokens = mock(CopyTokenApi.class);
  private final WeidianItemCopyApi copies = mock(WeidianItemCopyApi.class);
  private final CopyJobProgress progress = mock(CopyJobProgress.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private CopyJobWorker worker;

  @BeforeAll
  static void initMybatisPlus() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, CopyJobItemDO.class);
    TableInfoHelper.initTableInfo(assistant, ShopDO.class);
    TableInfoHelper.initTableInfo(assistant, ProductDO.class);
    TableInfoHelper.initTableInfo(assistant, KolItemDO.class);
  }

  @BeforeEach
  void setUp() {
    worker =
        new CopyJobWorker(
            jobs,
            items,
            shops,
            products,
            kolItems,
            codes,
            tokens,
            copies,
            progress,
            events,
            TestTransactions.template(),
            new DirectExecutor());
    // Happy-path inserts bump target-shop capacity (task 37); mock default int is 0.
    when(shops.incrementCurrentItems(anyLong(), any())).thenReturn(1);
  }

  @Test
  void shouldSucceedWithoutCopyOrQuota_whenOwnExclusiveItem() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90001");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(10L);
    exclusive.setWeidianItemId("90001");
    when(kolItems.findCurrentByWeidianItem("90001")).thenReturn(exclusive);

    worker.processJob(1L);

    verify(copies, never()).copy(anyLong(), any(), anyLong());
    verify(tokens, never()).consume(anyLong(), anyInt());
    verify(items).update(eq(null), any());
    ArgumentCaptor<Wrapper<CopyJobItemDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).update(eq(null), cap.capture());
  }

  @Test
  void shouldCopyFromTheCopy_whenSourceIsOtherKolsExclusiveItem() {
    // Task 152: another KOL's copied link submitted as source is copied FROM ITSELF (its own
    // shop and item id); the new mapping still registers product_id -> the original product.
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90002");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(99L);
    exclusive.setProductId(50L);
    exclusive.setShopId(6L);
    exclusive.setWeidianItemId("90002");
    when(kolItems.findCurrentByWeidianItem("90002")).thenReturn(exclusive);
    when(products.selectById(50L)).thenReturn(product(50L, 5L, "10001"));
    when(shops.selectById(6L)).thenReturn(shop(6L));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(6L, "90002", 5L)).thenReturn(new CopyResult("90003", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    verify(copies, times(1)).copy(6L, "90002", 5L);
    verify(tokens).consume(20L, 1);
    ArgumentCaptor<KolItemDO> inserted = ArgumentCaptor.forClass(KolItemDO.class);
    verify(kolItems).insert(inserted.capture());
    assertThat(inserted.getValue().getKolId()).isEqualTo(10L);
    assertThat(inserted.getValue().getProductId()).isEqualTo(50L);
    assertThat(inserted.getValue().getShopId()).isEqualTo(5L);
    assertThat(inserted.getValue().getWeidianItemId()).isEqualTo("90003");
    assertThat(inserted.getValue().getSource()).isEqualTo("COPY");
    ArgumentCaptor<Wrapper<CopyJobItemDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).update(eq(null), cap.capture());
    assertThat(((LambdaUpdateWrapper<?>) cap.getValue()).getParamNameValuePairs().values())
        .contains("90003", 6L);
  }

  @Test
  void shouldReuseExistingMapping_whenKolAlreadyOwnsResolvedProduct() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90002");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(99L);
    exclusive.setProductId(50L);
    exclusive.setShopId(6L);
    exclusive.setWeidianItemId("90002");
    when(kolItems.findCurrentByWeidianItem("90002")).thenReturn(exclusive);
    when(products.selectById(50L)).thenReturn(product(50L, 5L, "10001"));
    when(shops.selectById(6L)).thenReturn(shop(6L));
    KolItemDO existing = new KolItemDO();
    existing.setWeidianItemId("90005");
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(existing);

    worker.processJob(1L);

    verify(copies, never()).copy(anyLong(), any(), anyLong());
    verify(tokens, never()).consume(anyLong(), anyInt());
    verify(shops, never()).incrementCurrentItems(anyLong(), any());
    ArgumentCaptor<Wrapper<CopyJobItemDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).update(eq(null), cap.capture());
    assertThat(((LambdaUpdateWrapper<?>) cap.getValue()).getParamNameValuePairs().values())
        .contains("90005", 6L);
  }

  @Test
  void shouldFailUntraceable_whenExclusiveMappingHasNoProduct() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90002");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(99L);
    exclusive.setWeidianItemId("90002");
    when(kolItems.findCurrentByWeidianItem("90002")).thenReturn(exclusive);

    worker.processJob(1L);

    verify(copies, never()).copy(anyLong(), any(), anyLong());
    verify(tokens, never()).consume(anyLong(), anyInt());
    verifyFailedMessage(ProductLinkMessages.EXCLUSIVE_UNTRACEABLE);
  }

  @Test
  void shouldFailUntraceable_whenOriginalProductDeleted() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90002");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(99L);
    exclusive.setProductId(50L);
    exclusive.setWeidianItemId("90002");
    when(kolItems.findCurrentByWeidianItem("90002")).thenReturn(exclusive);
    when(products.selectById(50L)).thenReturn(null);

    worker.processJob(1L);

    verify(copies, never()).copy(anyLong(), any(), anyLong());
    verify(tokens, never()).consume(anyLong(), anyInt());
    verifyFailedMessage(ProductLinkMessages.EXCLUSIVE_UNTRACEABLE);
  }

  @Test
  void shouldCopyFromTheCopy_whenOriginalShopDisabled() {
    // Task 152, the production scenario: originals live in shop A, copies in pool shop B, shop A
    // is disabled. The copy's own shop serves as the physical source; shop A is never consulted.
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90002");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(99L);
    exclusive.setProductId(50L);
    exclusive.setShopId(6L);
    exclusive.setWeidianItemId("90002");
    when(kolItems.findCurrentByWeidianItem("90002")).thenReturn(exclusive);
    when(products.selectById(50L)).thenReturn(product(50L, 5L, "10001"));
    ShopDO disabledOriginalShop = shop(5L);
    disabledOriginalShop.setStatus(0);
    when(shops.selectById(5L)).thenReturn(disabledOriginalShop);
    when(shops.selectById(6L)).thenReturn(shop(6L));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(6L, "90002", 5L)).thenReturn(new CopyResult("90003", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    verify(copies, times(1)).copy(6L, "90002", 5L);
    verify(tokens).consume(20L, 1);
    ArgumentCaptor<Wrapper<CopyJobItemDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).update(eq(null), cap.capture());
    assertThat(((LambdaUpdateWrapper<?>) cap.getValue()).getParamNameValuePairs().values())
        .contains("90003", 6L);
  }

  @Test
  void shouldFail_whenTheCopysOwnShopIsDisabled() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90002");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(99L);
    exclusive.setProductId(50L);
    exclusive.setShopId(6L);
    exclusive.setWeidianItemId("90002");
    when(kolItems.findCurrentByWeidianItem("90002")).thenReturn(exclusive);
    when(products.selectById(50L)).thenReturn(product(50L, 5L, "10001"));
    ShopDO disabledCopyShop = shop(6L);
    disabledCopyShop.setStatus(0);
    when(shops.selectById(6L)).thenReturn(disabledCopyShop);

    worker.processJob(1L);

    verify(copies, never()).copy(anyLong(), any(), anyLong());
    verify(tokens, never()).consume(anyLong(), anyInt());
    verifyFailedMessage(ProductLinkMessages.EXCLUSIVE_SHOP_DISABLED);
  }

  @Test
  void shouldLockTheCopy_beforeRegisteringExclusiveCopy() {
    // The copy source shares the shop-item lock with the direct paths, so concurrent jobs
    // converting the same copied link cannot double-copy or double-register.
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "90002");
    stubJob(job, row);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(99L);
    exclusive.setProductId(50L);
    exclusive.setShopId(6L);
    exclusive.setWeidianItemId("90002");
    when(kolItems.findCurrentByWeidianItem("90002")).thenReturn(exclusive);
    when(products.selectById(50L)).thenReturn(product(50L, 5L, "10001"));
    when(shops.selectById(6L)).thenReturn(shop(6L));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(6L, "90002", 5L)).thenReturn(new CopyResult("90003", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    InOrder order = inOrder(products, kolItems);
    order.verify(products).lockShopItem(ProductRules.shopItemLockKey(5L, "10001"));
    order.verify(products).lockShopItem(ProductRules.shopItemLockKey(6L, "90002"));
    order.verify(kolItems).insert(org.mockito.ArgumentMatchers.<KolItemDO>any());
  }

  @Test
  void shouldReuseExistingMapping_withoutQuota() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    ProductDO product = product(50L, 5L, "10001");
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of(product));
    KolItemDO existing = new KolItemDO();
    existing.setWeidianItemId("90001");
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(existing);

    worker.processJob(1L);

    verify(copies, never()).copy(anyLong(), any(), anyLong());
    verify(tokens, never()).consume(anyLong(), anyInt());
    ArgumentCaptor<Wrapper<CopyJobItemDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).update(eq(null), cap.capture());
    assertThat(((Update<?, ?>) cap.getValue()).getSqlSet()).contains("source_shop_id");
  }

  @Test
  void shouldFailConflict_whenSourceInMultipleShops() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L), shop(6L)));
    ProductDO a = product(50L, 5L, "10001");
    ProductDO b = product(51L, 6L, "10001");
    when(products.selectList(any())).thenReturn(List.of(a, b));

    worker.processJob(1L);

    verifyFailedMessage(ProductLinkMessages.SOURCE_CONFLICT);
    verify(copies, never()).copy(anyLong(), any(), anyLong());
  }

  @Test
  void shouldRegisterUnderSellerShop_whenEveryShopProbesTheItem() {
    // Task 137: with two authorised shops Weidian item.get answered both tokens, so every
    // unregistered source failed as SOURCE_CONFLICT; the seller-confirmed shop must win.
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L), shop(6L)));
    when(products.selectList(any())).thenReturn(List.of());
    when(copies.probe(5L, "10001"))
        .thenReturn(Optional.of(new ProbedItem("10001", "Src", BigDecimal.ONE, null, false)));
    when(copies.probe(6L, "10001"))
        .thenReturn(Optional.of(new ProbedItem("10001", "Src", BigDecimal.ONE, null, true)));
    when(products.selectOne(any())).thenReturn(null);
    when(codes.next()).thenReturn("P000001");
    when(products.insert(org.mockito.ArgumentMatchers.<ProductDO>any()))
        .thenAnswer(
            inv -> {
              ProductDO p = inv.getArgument(0);
              p.setId(51L);
              return 1;
            });
    when(products.selectById(51L)).thenAnswer(inv -> product(51L, 6L, "10001"));
    when(kolItems.findCurrentByKolProduct(10L, 51L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(6L, "10001", 5L)).thenReturn(new CopyResult("90001", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    ArgumentCaptor<ProductDO> registered = ArgumentCaptor.forClass(ProductDO.class);
    verify(products).insert(registered.capture());
    assertThat(registered.getValue().getShopId()).isEqualTo(6L);
    verify(copies).copy(6L, "10001", 5L);
  }

  @Test
  void shouldFailConflict_whenSeveralShopsProbeWithoutSellerConfirmation() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L), shop(6L)));
    when(products.selectList(any())).thenReturn(List.of());
    when(copies.probe(anyLong(), eq("10001")))
        .thenReturn(Optional.of(new ProbedItem("10001", "Src", BigDecimal.ONE, null, false)));

    worker.processJob(1L);

    verifyFailedMessage(ProductLinkMessages.SOURCE_CONFLICT);
    verify(copies, never()).copy(anyLong(), any(), anyLong());
  }

  @Test
  void shouldFail_whenSourceMissing() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of());
    when(copies.probe(5L, "10001")).thenReturn(Optional.empty());

    worker.processJob(1L);

    verifyFailedMessage(ProductLinkMessages.SOURCE_NOT_IN_SHOPS);
    ArgumentCaptor<Wrapper<CopyJobItemDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(items).update(eq(null), cap.capture());
    assertThat(((Update<?, ?>) cap.getValue()).getSqlSet()).doesNotContain("source_shop_id");
  }

  @Test
  void shouldFailIncomplete_whenProbeLacksPrice() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of());
    when(copies.probe(5L, "10001"))
        .thenReturn(Optional.of(new ProbedItem("10001", "Name", null, null, false)));

    worker.processJob(1L);

    verifyFailedMessage(ProductLinkMessages.SOURCE_INCOMPLETE);
    verify(products, never()).insert(org.mockito.ArgumentMatchers.<ProductDO>any());
  }

  @Test
  void shouldFailQuota_whenConsumeReturnsFalse() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    ProductDO product = product(50L, 5L, "10001");
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of(product));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(false);

    worker.processJob(1L);

    verifyFailedMessage(ProductLinkMessages.QUOTA_INSUFFICIENT);
    verify(copies, never()).copy(anyLong(), any(), anyLong());
  }

  @Test
  void shouldMarkFailedOnce_whenWeidianCopyThrows_andNotRetry() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    ProductDO product = product(50L, 5L, "10001");
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of(product));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(5L, "10001", 5L))
        .thenThrow(new BusinessException(ErrorCode.WEIDIAN_API_FAILED, "微店接口调用失败：500", null));

    worker.processJob(1L);

    verify(copies, times(1)).copy(5L, "10001", 5L);
    verifyFailedMessage("微店接口调用失败：500");
  }

  @Test
  void shouldSucceedWithDetailWarning_whenCopyReturnsWarning() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    ProductDO product = product(50L, 5L, "10001");
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of(product));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(5L, "10001", 5L))
        .thenReturn(new CopyResult("90099", ProductLinkMessages.DETAIL_SAVE_WARNING));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    ArgumentCaptor<KolItemDO> inserted = ArgumentCaptor.forClass(KolItemDO.class);
    verify(kolItems).insert(inserted.capture());
    assertThat(inserted.getValue().getSource()).isEqualTo("COPY");
    assertThat(inserted.getValue().getWeidianItemId()).isEqualTo("90099");
    assertThat(inserted.getValue().getLinkCode()).matches("KPL-[0-9A-F]{12}");
    verify(copies, times(1)).copy(5L, "10001", 5L);
  }

  @Test
  void shouldRecordWeidianId_whenMappingPersistFailsAfterAdd() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    ProductDO product = product(50L, 5L, "10001");
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of(product));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(5L, "10001", 5L)).thenReturn(new CopyResult("90111", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any()))
        .thenThrow(new RuntimeException("db down"));

    worker.processJob(1L);

    verifyFailedMessage(ProductLinkMessages.mappingPersistFailed("90111"));
  }

  @Test
  void shouldPropagateProbeApiFailure() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of());
    when(copies.probe(5L, "10001"))
        .thenThrow(new BusinessException(ErrorCode.WEIDIAN_API_FAILED, "微店接口调用失败：42", null));

    worker.processJob(1L);

    verifyFailedMessage("微店接口调用失败：42");
  }

  @Test
  void shouldCopyHappyPath_andRegisterProbedProduct() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of());
    when(copies.probe(5L, "10001"))
        .thenReturn(
            Optional.of(
                new ProbedItem(
                    "10001",
                    "Src",
                    new BigDecimal("9.9"),
                    "https://wd.geilicdn.com/a.jpg",
                    false)));
    when(products.selectOne(any())).thenReturn(null);
    when(codes.next()).thenReturn("P000001");
    AtomicInteger productId = new AtomicInteger(50);
    when(products.insert(org.mockito.ArgumentMatchers.<ProductDO>any()))
        .thenAnswer(
            inv -> {
              ProductDO p = inv.getArgument(0);
              p.setId((long) productId.get());
              return 1;
            });
    when(products.selectById(50L)).thenAnswer(inv -> product(50L, 5L, "10001"));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(5L, "10001", 5L)).thenReturn(new CopyResult("90001", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    ArgumentCaptor<ProductDO> registered = ArgumentCaptor.forClass(ProductDO.class);
    verify(products).insert(registered.capture());
    assertThat(registered.getValue().getImageUrl()).isEqualTo("https://wd.geilicdn.com/a.jpg");
    ArgumentCaptor<KolItemDO> inserted = ArgumentCaptor.forClass(KolItemDO.class);
    verify(kolItems).insert(inserted.capture());
    assertThat(inserted.getValue().getSource()).isEqualTo("COPY");
    assertThat(inserted.getValue().getWeidianItemId()).isEqualTo("90001");
    verify(tokens).consume(20L, 1);
  }

  @Test
  void shouldLockShopItem_beforeRegisteringProbedProduct() {
    // Production 2026-11-10: two jobs for the same source item registered it concurrently and
    // one failed on uk_crm_product_shop_item. The registration must run under the shop-item lock
    // the import paths use.
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of());
    when(copies.probe(5L, "10001"))
        .thenReturn(
            Optional.of(new ProbedItem("10001", "Src", new BigDecimal("9.9"), null, false)));
    when(products.selectOne(any())).thenReturn(null);
    when(codes.next()).thenReturn("P000001");
    when(products.insert(org.mockito.ArgumentMatchers.<ProductDO>any()))
        .thenAnswer(
            inv -> {
              ProductDO p = inv.getArgument(0);
              p.setId(50L);
              return 1;
            });
    when(products.selectById(50L)).thenAnswer(inv -> product(50L, 5L, "10001"));
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true);
    when(copies.copy(5L, "10001", 5L)).thenReturn(new CopyResult("90001", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    InOrder order = inOrder(products);
    order.verify(products).lockShopItem(ProductRules.shopItemLockKey(5L, "10001"));
    order.verify(products).insert(org.mockito.ArgumentMatchers.<ProductDO>any());
  }

  @Test
  void shouldLockShopItem_beforeReusingExistingMapping() {
    // Registered source item: the lock also serializes the copy itself, so a parallel job cannot
    // create the same exclusive item twice.
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO row = pending(100L, "10001");
    stubJob(job, row);
    when(kolItems.findCurrentByWeidianItem("10001")).thenReturn(null);
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of(product(50L, 5L, "10001")));
    KolItemDO existing = new KolItemDO();
    existing.setWeidianItemId("90001");
    when(kolItems.findCurrentByKolProduct(10L, 50L)).thenReturn(existing);

    worker.processJob(1L);

    verify(products).lockShopItem(ProductRules.shopItemLockKey(5L, "10001"));
    verify(copies, never()).copy(anyLong(), any(), anyLong());
  }

  @Test
  void shouldExhaustQuotaMidJob_onSecondItem() {
    CopyJobDO job = job(1L, 10L, 20L, 5L);
    CopyJobItemDO a = pending(100L, "10001");
    CopyJobItemDO b = pending(101L, "10002");
    when(jobs.selectById(1L)).thenReturn(job);
    when(items.listPendingByJob(1L)).thenReturn(List.of(a, b));
    when(items.selectById(100L)).thenReturn(a);
    when(items.selectById(101L)).thenReturn(b);
    when(kolItems.findCurrentByWeidianItem(any())).thenReturn(null);
    ProductDO p1 = product(50L, 5L, "10001");
    ProductDO p2 = product(51L, 5L, "10002");
    when(shops.selectList(any())).thenReturn(List.of(shop(5L)));
    when(products.selectList(any())).thenReturn(List.of(p1)).thenReturn(List.of(p2));
    when(kolItems.findCurrentByKolProduct(eq(10L), anyLong())).thenReturn(null);
    when(tokens.consume(20L, 1)).thenReturn(true).thenReturn(false);
    when(copies.copy(5L, "10001", 5L)).thenReturn(new CopyResult("90001", null));
    when(kolItems.insert(org.mockito.ArgumentMatchers.<KolItemDO>any())).thenReturn(1);

    worker.processJob(1L);

    verify(copies, times(1)).copy(anyLong(), any(), anyLong());
    verify(tokens, times(2)).consume(20L, 1);
    verify(progress, times(3)).recountAndMaybeFinish(1L);
  }

  private void stubJob(CopyJobDO job, CopyJobItemDO row) {
    when(jobs.selectById(job.getId())).thenReturn(job);
    when(items.listPendingByJob(job.getId())).thenReturn(List.of(row));
    when(items.selectById(row.getId())).thenReturn(row);
  }

  private void verifyFailedMessage(String message) {
    // markFailed always goes through items.update; assert the persisted error_message content.
    ArgumentCaptor<Wrapper<CopyJobItemDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(items, times(1)).update(eq(null), cap.capture());
    assertThat(((LambdaUpdateWrapper<?>) cap.getValue()).getParamNameValuePairs().values())
        .contains(message);
  }

  private static CopyJobDO job(long id, long kolId, long tokenId, long shopId) {
    CopyJobDO job = new CopyJobDO();
    job.setId(id);
    job.setTenantId(1L);
    job.setKolId(kolId);
    job.setTokenId(tokenId);
    job.setTargetShopId(shopId);
    job.setStatus(CopyJobDO.STATUS_PROCESSING);
    return job;
  }

  private static CopyJobItemDO pending(long id, String sourceId) {
    CopyJobItemDO row = new CopyJobItemDO();
    row.setId(id);
    row.setJobId(1L);
    row.setSourceChannel(ProductLinkService.CHANNEL_WEIDIAN);
    row.setSourceProductId(sourceId);
    row.setStatus(CopyJobItemDO.STATUS_PENDING);
    return row;
  }

  private static ShopDO shop(long id) {
    ShopDO shop = new ShopDO();
    shop.setId(id);
    shop.setStatus(1);
    return shop;
  }

  private static ProductDO product(long id, long shopId, String itemId) {
    ProductDO product = new ProductDO();
    product.setId(id);
    product.setShopId(shopId);
    product.setWeidianItemId(itemId);
    product.setName("P");
    product.setPrice(new BigDecimal("1.00"));
    return product;
  }

  /** Runs tasks on the calling thread so TenantScope stays on the test thread. */
  private static final class DirectExecutor extends AbstractExecutorService {
    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }
}
