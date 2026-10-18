package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.kol.api.CopyTokenGrantDTO;
import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import com.tcrm.admin.product.model.CopyJobItemDO;
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
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AtomicLong ids = new AtomicLong(100);
  private ProductLinkService service;

  @BeforeEach
  void setUp() {
    service =
        new ProductLinkService(
            tokens,
            jobs,
            items,
            shops,
            kolItems,
            rates,
            worker,
            progress,
            jdbc,
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
              assertThat(be.getMessage()).contains("默认复制店铺");
            });
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
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(5L))).thenReturn(1);
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
}
