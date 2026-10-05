package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KolItemServiceTest {
  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), KolItemDO.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProductDO.class);
  }

  private final KolItemMapper items = mock(KolItemMapper.class);
  private final ProductMapper products = mock(ProductMapper.class);
  private final ShopMapper shops = mock(ShopMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final CurrentUserContext current = mock(CurrentUserContext.class);
  private KolItemService service;

  @BeforeEach
  void setup() {
    service = new KolItemService(items, products, shops, kols, current);
    when(current.user())
        .thenReturn(
            new AccountDTO(
                1L, "admin", null, "Admin", null, Role.SUPER_ADMIN, DataScope.ALL, 1, null, null));
  }

  @Test
  void shouldReject_whenAllPrecisionFiltersMissing() {
    assertThatThrownBy(() -> service.page(new KolItemFilterDTO(null, null, 1L, null, true, 1, 20)))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @Test
  void shouldReject_whenOnlyBlankStringsProvided() {
    assertThatThrownBy(() -> service.page(new KolItemFilterDTO("  ", "", null, "   ", true, 1, 20)))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.COMMON_VALIDATION_FAILED);
  }

  @ParameterizedTest
  @CsvSource({"KOL0001, , ", ", P000001, ", ", , 123456"})
  void shouldAccept_whenAnyPrecisionFilterAlone(String kolCode, String productCode, String itemId) {
    when(items.selectPage(any(), any()))
        .thenAnswer(
            inv -> {
              Page<KolItemDO> page = inv.getArgument(0);
              page.setRecords(List.of());
              page.setTotal(0);
              return page;
            });
    if (kolCode != null && !kolCode.isBlank()) {
      when(kols.getByCode("KOL0001"))
          .thenReturn(new KolRefDTO(11L, "KOL0001", "Alice", true, null));
    }
    if (productCode != null && !productCode.isBlank()) {
      ProductDO product = new ProductDO();
      product.setId(22L);
      product.setProductCode("P000001");
      when(products.selectOne(any())).thenReturn(product);
    }
    var result =
        service.page(
            new KolItemFilterDTO(
                blankToNull(kolCode),
                blankToNull(productCode),
                null,
                blankToNull(itemId),
                true,
                1,
                20));
    assertThat(result.total()).isZero();
    verify(items).selectPage(any(), any());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
