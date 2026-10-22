package com.tcrm.admin.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.model.KolItemDO;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class KolItemCodesTest {
  private final KolItemMapper items = mock(KolItemMapper.class);

  @Test
  void shouldGenerateUniqueLinkCodes() {
    Set<String> codes = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      String code = KolItemCodes.next();
      assertThat(code).matches("KPL-[0-9A-F]{12}");
      codes.add(code);
    }
    assertThat(codes).hasSize(20);
  }

  @Test
  void shouldRetryOnlyTheLinkCodeUniqueKey() {
    AtomicInteger calls = new AtomicInteger();
    when(items.insert(any(KolItemDO.class)))
        .thenAnswer(
            inv -> {
              KolItemDO row = inv.getArgument(0);
              if (calls.incrementAndGet() == 1) {
                row.setId(5L);
                throw linkConflict();
              }
              assertThat(row.getId()).isNull();
              assertThat(row.getLinkCode()).matches("KPL-[0-9A-F]{12}");
              return 1;
            });

    KolItemDO row = new KolItemDO();
    KolItemCodes.insertNew(items, row);

    verify(items, times(2)).insert(any(KolItemDO.class));
    assertThat(row.getLinkCode()).matches("KPL-[0-9A-F]{12}");
  }

  @Test
  void shouldNotRetryShopItemConflict() {
    when(items.insert(any(KolItemDO.class)))
        .thenThrow(
            new DuplicateKeyException(
                "dup", new RuntimeException("duplicate key uk_crm_kol_item_shop_item")));

    assertThatThrownBy(() -> KolItemCodes.insertNew(items, new KolItemDO()))
        .isInstanceOf(DuplicateKeyException.class);
    verify(items, times(1)).insert(any(KolItemDO.class));
  }

  private static DuplicateKeyException linkConflict() {
    return new DuplicateKeyException(
        "dup",
        new RuntimeException(
            "duplicate key value violates unique constraint \"uk_crm_kol_item_link\""));
  }
}
