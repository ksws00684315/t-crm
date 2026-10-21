package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import com.tcrm.admin.product.model.CopyJobItemDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CopyJobQueryServiceTest {
  @BeforeAll
  static void tableInfo() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, CopyJobDO.class);
    TableInfoHelper.initTableInfo(assistant, CopyJobItemDO.class);
    TableInfoHelper.initTableInfo(assistant, ShopDO.class);
  }

  private final CopyJobMapper jobs = mock(CopyJobMapper.class);
  private final CopyJobItemMapper items = mock(CopyJobItemMapper.class);
  private final ShopMapper shops = mock(ShopMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private CopyJobQueryService service;

  @BeforeEach
  void setUp() {
    service = new CopyJobQueryService(jobs, items, shops, kols, current);
  }

  @Test
  void shouldReturnEmpty_whenBdOwnsNoKol() {
    when(current.user()).thenReturn(staff(7L));
    when(kols.listByBdUserId(7L)).thenReturn(List.of());

    var page = service.page(new CopyJobQueryDTO(null, null, null, 1, 20));

    assertThat(page.total()).isZero();
    assertThat(page.items()).isEmpty();
    verify(jobs, never()).selectPage(any(), any());
  }

  @Test
  void shouldEscapeUnderscore_inJobCodePrefix() {
    assertThat(CopyJobQueryService.likePrefix("ab_c")).isEqualTo("ab\\_c%");
    assertThat(CopyJobQueryService.likePrefix("a%b\\c")).isEqualTo("a\\%b\\\\c%");
    when(current.user()).thenReturn(admin());
    when(jobs.selectPage(any(), any())).thenReturn(new Page<>(1, 20, 0));

    service.page(new CopyJobQueryDTO("ab_c", null, null, 1, 20));

    ArgumentCaptor<Wrapper<CopyJobDO>> cap = ArgumentCaptor.forClass(Wrapper.class);
    verify(jobs).selectPage(any(), cap.capture());
    assertThat(cap.getValue().getSqlSegment()).contains("job_code LIKE");
    assertThat(((AbstractWrapper<?, ?, ?>) cap.getValue()).getParamNameValuePairs().values())
        .contains("ab\\_c%");
  }

  @Test
  void shouldRejectUnknownStatusAndIllegalChannel() {
    when(current.user()).thenReturn(admin());
    assertThatThrownBy(() -> service.page(new CopyJobQueryDTO(null, null, "done", 1, 20)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
    assertThatThrownBy(() -> service.page(new CopyJobQueryDTO(null, "weidian", null, 1, 20)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  void shouldHideJob_whenBdDoesNotOwnTheKol() {
    when(current.user()).thenReturn(staff(7L));
    CopyJobDO job = job(3L, 20L);
    when(jobs.selectById(3L)).thenReturn(job);
    when(kols.listByIds(Set.of(20L)))
        .thenReturn(List.of(new KolRefDTO(20L, "K1", "Ada", true, 8L)));

    assertThatThrownBy(() -> service.items(3L, 1, 20))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldHideJob_whenItIsOutsideTheCompany() {
    when(current.user()).thenReturn(admin());
    when(jobs.selectById(9L)).thenReturn(null);

    assertThatThrownBy(() -> service.items(9L, 1, 20))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_NOT_FOUND);
  }

  @Test
  void shouldNumberItemsAndJoinWeidianUrl() {
    when(current.user()).thenReturn(admin());
    CopyJobDO job = job(3L, 20L);
    job.setTargetChannel("WEIDIAN");
    job.setTargetShopId(5L);
    when(jobs.selectById(3L)).thenReturn(job);
    CopyJobItemDO row = new CopyJobItemDO();
    row.setId(40L);
    row.setSourceChannel("WEIDIAN");
    row.setSourceProductId("10001");
    row.setSourceShopId(8L);
    row.setTargetItemId("90001");
    row.setStatus(CopyJobItemDO.STATUS_SUCCESS);
    Page<CopyJobItemDO> page = new Page<>(2, 2, 3);
    page.setRecords(List.of(row));
    when(items.selectPage(any(), any())).thenReturn(page);
    ShopDO source = new ShopDO();
    source.setId(8L);
    source.setShopName("Source");
    source.setWeidianShopId("800");
    ShopDO target = new ShopDO();
    target.setId(5L);
    target.setShopName("Target");
    target.setWeidianShopId("500");
    when(shops.selectList(any())).thenReturn(List.of(source, target));

    var result = service.items(3L, 2, 2);

    assertThat(result.items()).hasSize(1);
    CopyJobItemDTO item = result.items().getFirst();
    assertThat(item.seq()).isEqualTo(3);
    assertThat(item.targetItemUrl()).isEqualTo("https://weidian.com/item.html?itemID=90001");
    assertThat(item.sourceMerchantNo()).isEqualTo("800");
    assertThat(item.sourceShopName()).isEqualTo("Source");
    assertThat(item.targetShopName()).isEqualTo("Target");
    assertThat(CopyJobQueryService.targetItemUrl(null)).isNull();
    assertThat(CopyJobQueryService.targetItemUrl("  ")).isNull();
  }

  @Test
  void shouldLeaveShopCodeEmpty_andKeepTaskError() {
    when(current.user()).thenReturn(admin());
    CopyJobDO job = job(3L, 20L);
    job.setJobCode("abc");
    job.setTargetChannel("WEIDIAN");
    job.setStatus(CopyJobDO.STATUS_FAILED);
    job.setErrorMessage("目标店铺未完成微店授权");
    job.setTotalCount(0);
    job.setSuccessCount(0);
    job.setFailCount(0);
    job.setCreateTime(Instant.parse("2026-10-21T00:00:00Z"));
    job.setFinishTime(Instant.parse("2026-10-21T00:01:00Z"));
    job.setTargetShopId(5L);
    Page<CopyJobDO> page = new Page<>(1, 20, 1);
    page.setRecords(List.of(job));
    when(jobs.selectPage(any(), any())).thenReturn(page);
    when(kols.listByIds(Set.of(20L)))
        .thenReturn(List.of(new KolRefDTO(20L, "K1", "Ada", true, 7L)));
    ShopDO shop = new ShopDO();
    shop.setId(5L);
    shop.setShopName("Main");
    when(shops.selectList(any())).thenReturn(List.of(shop));

    var result = service.page(new CopyJobQueryDTO(null, "WEIDIAN", "failed", 1, 20));

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().targetShopCode()).isNull();
    assertThat(result.items().getFirst().targetShopName()).isEqualTo("Main");
    assertThat(result.items().getFirst().errorMessage()).isEqualTo("目标店铺未完成微店授权");
    assertThat(result.items().getFirst().kolCode()).isEqualTo("K1");
  }

  private static CopyJobDO job(long id, long kolId) {
    CopyJobDO job = new CopyJobDO();
    job.setId(id);
    job.setKolId(kolId);
    job.setTenantId(1L);
    return job;
  }

  private static AccountDTO admin() {
    return new AccountDTO(
        1L,
        "admin",
        null,
        "Admin",
        null,
        Role.SUPER_ADMIN,
        DataScope.ALL,
        1,
        null,
        null,
        0,
        1L,
        "Co",
        true);
  }

  private static AccountDTO staff(long id) {
    return new AccountDTO(
        id, "bd", null, "Bd", null, Role.STAFF, DataScope.OWN, 1, null, null, 0, 1L, "Co", true);
  }
}
