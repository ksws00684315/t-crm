package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.kol.api.CopyTokenGrantDTO;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
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
import com.tcrm.admin.product.service.ProductLinkService.ConvertCommand;
import com.tcrm.admin.product.service.ProductLinkService.ConvertItem;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProductLinkServiceTest {
  private final CopyTokenApi tokens = mock(CopyTokenApi.class);
  private final CopyJobMapper jobs = mock(CopyJobMapper.class);
  private final CopyJobItemMapper items = mock(CopyJobItemMapper.class);
  private final ShopMapper shops = mock(ShopMapper.class);
  private final KolItemMapper kolItems = mock(KolItemMapper.class);
  private final ProductLinkRateLimiter rates = mock(ProductLinkRateLimiter.class);
  private final CopyJobWorker worker = mock(CopyJobWorker.class);
  private final CopyJobProgress progress = mock(CopyJobProgress.class);
  private final WeidianItemCopyApi copies = mock(WeidianItemCopyApi.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ProductMapper products = mock(ProductMapper.class);
  private final AtomicLong ids = new AtomicLong(100);
  private ProductLinkService service;

  @BeforeEach
  void setUp() {
    service =
        new ProductLinkService(
            tokens,
            null,
            jobs,
            items,
            shops,
            kolItems,
            rates,
            worker,
            progress,
            copies,
            jdbc,
            products,
            new CopyJobFailureRecorder(jobs),
            "https://t.example",
            new SecureRandom(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
    when(jobs.insert(any(CopyJobDO.class)))
        .thenAnswer(
            inv -> {
              CopyJobDO job = inv.getArgument(0);
              job.setId(ids.incrementAndGet());
              return 1;
            });
    when(items.insert(any(CopyJobItemDO.class)))
        .thenAnswer(
            inv -> {
              CopyJobItemDO row = inv.getArgument(0);
              row.setId(ids.incrementAndGet());
              return 1;
            });
  }

  @AfterEach
  void clearScope() {
    // Ensure no leaked tenant scope from failed assertions.
    while (TenantScope.companyId() != null || TenantScope.inSystem()) {
      // nothing to close from outside; tests open via callCompany
      break;
    }
  }

  @Test
  void shouldRejectConvert_whenNoTargetShop() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", null, 5));
    when(shops.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "tok",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "123", null)))))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.COPY_TARGET_SHOP_MISSING);
              assertThat(be.getMessage()).contains("没有可用的目标店铺");
            });
    verify(jobs, never()).insert(any(CopyJobDO.class));
    verify(worker, never()).submit(anyLong());
  }

  @Test
  void shouldCreateJob_andSubmitWorker_whenTokenHasShop() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", 5L, 5));
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setStatus(1);
    shop.setWeidianShopId("9001");
    when(shops.selectById(5L)).thenReturn(shop);
    when(copies.isShopAuthorized(5L)).thenReturn(true);
    when(items.listByJob(anyLong())).thenReturn(List.of());

    var result =
        service.convert(
            new ConvertCommand(
                "tok",
                "google_sheet_plugin",
                List.of(
                    new ConvertItem("WEIDIAN", "111", "r1"),
                    new ConvertItem("WEIDIAN", "111", "r2"), // dedupe
                    new ConvertItem("OTHER", "222", null))));

    assertThat(result.jobCode()).hasSize(22);
    ArgumentCaptor<CopyJobDO> jobCap = ArgumentCaptor.forClass(CopyJobDO.class);
    verify(jobs).insert(jobCap.capture());
    assertThat(jobCap.getValue().getTotalCount()).isEqualTo(2);
    assertThat(jobCap.getValue().getTargetChannel()).isEqualTo("WEIDIAN");
    assertThat(jobCap.getValue().getCallerPlatform()).isEqualTo("google_sheet_plugin");
    verify(items, org.mockito.Mockito.times(2)).insert(any(CopyJobItemDO.class));
    verify(worker).submit(jobCap.getValue().getId());
    verify(rates).checkConvertToken(1L);
  }

  @Test
  void shouldListTemplates_withRelayFirstAndDedup() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(
            List.of(
                new ProductLinkService.LinkTemplateView(
                    "1", "a", "A", "https://t.example/i/{PRODUCT_ID}", "WEIDIAN", 1),
                new ProductLinkService.LinkTemplateView(
                    "2", "b", "B", "https://buy.example/{PRODUCT_ID}", "WEIDIAN", 2)));
    var templates = service.templates("WEIDIAN");
    assertThat(templates).hasSize(2);
    assertThat(templates.getFirst().templateCode()).isEqualTo("TCRM_RELAY");
    assertThat(templates.get(1).templateUrl()).isEqualTo("https://buy.example/{PRODUCT_ID}");
  }

  @Test
  void shouldWrapAuthErrors_inChinese() {
    when(tokens.authenticate("bad")).thenThrow(new BusinessException(ErrorCode.COPY_TOKEN_EXPIRED));
    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "bad",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "1", null)))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getMessage())
        .isEqualTo("复制令牌已过期");
  }

  @Test
  void shouldRejectExpiredToken_inChinese() {
    when(tokens.authenticate("tok")).thenThrow(new BusinessException(ErrorCode.COPY_TOKEN_EXPIRED));
    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "tok",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "1", null)))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getMessage())
        .isEqualTo("复制令牌已过期");
  }

  @Test
  void shouldRejectQuotaExceeded_inChinese() {
    when(tokens.authenticate("tok"))
        .thenThrow(new BusinessException(ErrorCode.COPY_TOKEN_QUOTA_EXCEEDED));
    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "tok",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "1", null)))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getMessage())
        .isEqualTo("复制令牌额度已用尽");
  }

  @Test
  void shouldRejectDisabledKol_inChinese() {
    when(tokens.authenticate("tok")).thenThrow(new BusinessException(ErrorCode.KOL_DISABLED));
    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "tok",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "1", null)))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getMessage())
        .isEqualTo("达人账号已停用");
  }

  @Test
  void shouldRejectUnauthorizedTargetShop() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", 5L, 5));
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setStatus(1);
    when(shops.selectById(5L)).thenReturn(shop);
    when(copies.isShopAuthorized(5L)).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "tok",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "1", null)))))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
              assertThat(be.getMessage()).contains("未完成微店授权");
            });
    verify(jobs, never()).insert(any(CopyJobDO.class));
    verify(items, never()).insert(any(CopyJobItemDO.class));
    verify(worker, never()).submit(anyLong());
  }

  @Test
  void shouldRejectDisabledTargetShop() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", 5L, 5));
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setStatus(0);
    when(shops.selectById(5L)).thenReturn(shop);

    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "tok",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "1", null)))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SHOP_DISABLED);
    verify(jobs, never()).insert(any(CopyJobDO.class));
    verify(worker, never()).submit(anyLong());
  }

  @Test
  void shouldRejectMissingNamedShop_withoutCreatingATask() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", 5L, 5));
    when(shops.selectById(5L)).thenReturn(null);

    assertThatThrownBy(
            () ->
                service.convert(
                    new ConvertCommand(
                        "tok",
                        "google_sheet_plugin",
                        List.of(new ConvertItem("WEIDIAN", "1", null)))))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.COPY_TARGET_SHOP_MISSING);
              assertThat(be.getMessage()).isEqualTo("没有可用的目标店铺");
            });
    verify(jobs, never()).insert(any(CopyJobDO.class));
    verify(items, never()).insert(any(CopyJobItemDO.class));
  }

  @Test
  void shouldRecordSourceShop_whenExactlyOneOriginalProduct() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", 5L, 5));
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setStatus(1);
    when(shops.selectById(5L)).thenReturn(shop);
    when(copies.isShopAuthorized(5L)).thenReturn(true);
    ProductDO product = new ProductDO();
    product.setShopId(8L);
    product.setWeidianItemId("111");
    when(products.selectList(any())).thenReturn(List.of(product));

    service.convert(
        new ConvertCommand(
            "tok", "google_sheet_plugin", List.of(new ConvertItem("WEIDIAN", "111", null))));

    ArgumentCaptor<CopyJobItemDO> itemCap = ArgumentCaptor.forClass(CopyJobItemDO.class);
    verify(items).insert(itemCap.capture());
    assertThat(itemCap.getValue().getSourceShopId()).isEqualTo(8L);
  }

  @Test
  void shouldLeaveSourceShopEmpty_whenSeveralShopsHaveTheItem() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", 5L, 5));
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setStatus(1);
    when(shops.selectById(5L)).thenReturn(shop);
    when(copies.isShopAuthorized(5L)).thenReturn(true);
    ProductDO first = new ProductDO();
    first.setShopId(8L);
    ProductDO second = new ProductDO();
    second.setShopId(9L);
    when(products.selectList(any())).thenReturn(List.of(first, second));

    service.convert(
        new ConvertCommand(
            "tok", "google_sheet_plugin", List.of(new ConvertItem("WEIDIAN", "111", null))));

    ArgumentCaptor<CopyJobItemDO> itemCap = ArgumentCaptor.forClass(CopyJobItemDO.class);
    verify(items).insert(itemCap.capture());
    assertThat(itemCap.getValue().getSourceShopId()).isNull();
  }

  @Test
  void shouldRejectResolve_whenTokenBelongsToOtherKol() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 1L, 99L, "WEIDIAN", 5L, 5));
    CopyJobDO job = new CopyJobDO();
    job.setJobCode("abc");
    job.setKolId(10L);
    job.setTenantId(1L);
    when(jobs.findByCode("abc")).thenReturn(job);

    assertThatThrownBy(
            () ->
                service.resolve(
                    "abc",
                    new ProductLinkService.ResolveCommand(
                        "tok", List.of(new ProductLinkService.ResolveItem("WEIDIAN", "1")))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COPY_TOKEN_INVALID);
  }

  @Test
  void shouldReturnAlreadyApplied_whenSourceIsOwnExclusive() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 1L, 10L, "WEIDIAN", 5L, 5));
    CopyJobDO job = new CopyJobDO();
    job.setId(7L);
    job.setJobCode("jobcode");
    job.setKolId(10L);
    job.setTenantId(1L);
    job.setTargetShopId(5L);
    job.setStatus(CopyJobDO.STATUS_COMPLETED);
    job.setUpdateTime(java.time.Instant.now());
    when(jobs.findByCode("jobcode")).thenReturn(job);
    when(items.listByJob(7L)).thenReturn(List.of());
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setWeidianShopId("1001");
    when(shops.selectById(5L)).thenReturn(shop);
    KolItemDO exclusive = new KolItemDO();
    exclusive.setKolId(10L);
    exclusive.setWeidianItemId("90001");
    when(kolItems.selectOne(any(LambdaQueryWrapper.class))).thenReturn(exclusive);

    var result =
        service.resolve(
            "jobcode",
            new ProductLinkService.ResolveCommand(
                "tok", List.of(new ProductLinkService.ResolveItem("WEIDIAN", "90001"))));

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().status()).isEqualTo("already_applied");
  }

  @Test
  void shouldGenerateUrlSafeJobCode_ofExpectedLength() {
    when(tokens.authenticate("tok"))
        .thenReturn(new CopyTokenGrantDTO(1L, 10L, 20L, "WEIDIAN", 5L, 5));
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setStatus(1);
    when(shops.selectById(5L)).thenReturn(shop);
    when(copies.isShopAuthorized(5L)).thenReturn(true);
    when(items.listByJob(anyLong())).thenReturn(List.of());

    var result =
        service.convert(
            new ConvertCommand(
                "tok", "google_sheet_plugin", List.of(new ConvertItem("WEIDIAN", "111", null))));

    assertThat(result.jobCode()).hasSize(22);
    assertThat(result.jobCode()).matches("[A-Za-z0-9_-]{22}");
  }

  @Test
  void shouldNotLeakOtherCompanyJobItems_whenJobCodeUnknownInCompany() {
    // getJob uses system scope to find by code then company scope — missing job is 404
    when(jobs.findByCode("missing")).thenReturn(null);
    assertThatThrownBy(() -> service.getJob("missing"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
  }
}
