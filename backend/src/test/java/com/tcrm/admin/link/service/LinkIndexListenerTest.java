package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolLinkTargetChangedEvent;
import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.kol.api.LinkTemplateChangedEvent;
import com.tcrm.admin.link.mapper.LinkIndexOutboxMapper;
import com.tcrm.admin.link.model.LinkIndexOutboxDO;
import com.tcrm.admin.link.model.LinkIndexScope;
import com.tcrm.admin.product.api.KolItemChangedEvent;
import com.tcrm.admin.product.api.ProductStatusChangedEvent;
import com.tcrm.admin.product.api.ShopStatusChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LinkIndexListenerTest {
  private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
  private final LinkIndexOutboxMapper outbox = mock(LinkIndexOutboxMapper.class);
  private final KolApi kols = mock(KolApi.class);
  private final LinkIndexListener listener =
      new LinkIndexListener(outbox, kols, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void shouldInsertOneOutboxRowPerEvent() {
    listener.onKolItemChanged(new KolItemChangedEvent(10L, 20L));
    listener.onProductStatusChanged(new ProductStatusChangedEvent(20L));
    listener.onShopStatusChanged(new ShopStatusChangedEvent(1L));
    listener.onKolStatusChanged(new KolStatusChangedEvent(10L));

    ArgumentCaptor<LinkIndexOutboxDO> rows = ArgumentCaptor.forClass(LinkIndexOutboxDO.class);
    verify(outbox, times(4)).insert(rows.capture());

    LinkIndexOutboxDO mapping = rows.getAllValues().get(0);
    assertThat(mapping.getScope()).isEqualTo(LinkIndexScope.MAPPING);
    assertThat(mapping.getScopeId()).isEqualTo(10L);
    assertThat(mapping.getProductId()).isEqualTo(20L);
    assertThat(mapping.getAttempts()).isZero();
    assertThat(mapping.getNextAttemptAt()).isEqualTo(NOW);
    assertThat(mapping.getCreateTime()).isEqualTo(NOW);

    assertThat(rows.getAllValues().get(1).getScope()).isEqualTo(LinkIndexScope.PRODUCT);
    assertThat(rows.getAllValues().get(1).getScopeId()).isEqualTo(20L);
    assertThat(rows.getAllValues().get(1).getProductId()).isNull();
    assertThat(rows.getAllValues().get(2).getScope()).isEqualTo(LinkIndexScope.SHOP);
    assertThat(rows.getAllValues().get(2).getProductId()).isNull();
    assertThat(rows.getAllValues().get(3).getScope()).isEqualTo(LinkIndexScope.KOL);
    assertThat(rows.getAllValues().get(3).getScopeId()).isEqualTo(10L);
    assertThat(rows.getAllValues().get(3).getProductId()).isNull();
  }

  @Test
  void shouldRegisterKolRefresh_whenLinkTargetInputsChange() {
    listener.onKolLinkTargetChanged(new KolLinkTargetChangedEvent(10L));

    ArgumentCaptor<LinkIndexOutboxDO> rows = ArgumentCaptor.forClass(LinkIndexOutboxDO.class);
    verify(outbox).insert(rows.capture());
    assertThat(rows.getValue().getScope()).isEqualTo(LinkIndexScope.KOL);
    assertThat(rows.getValue().getScopeId()).isEqualTo(10L);
    assertThat(rows.getValue().getProductId()).isNull();
  }

  @Test
  void shouldRegisterEveryUserOfTheTemplate() {
    when(kols.pageKolIdsWithTemplate(77L, null, LinkIndexListener.TEMPLATE_PAGE))
        .thenReturn(List.of(10L, 11L, 12L));

    listener.onLinkTemplateChanged(new LinkTemplateChangedEvent(77L));

    ArgumentCaptor<LinkIndexOutboxDO> rows = ArgumentCaptor.forClass(LinkIndexOutboxDO.class);
    verify(outbox, times(3)).insert(rows.capture());
    assertThat(rows.getAllValues())
        .allSatisfy(
            row -> {
              assertThat(row.getScope()).isEqualTo(LinkIndexScope.KOL);
              assertThat(row.getProductId()).isNull();
            });
    assertThat(rows.getAllValues().stream().map(LinkIndexOutboxDO::getScopeId))
        .containsExactly(10L, 11L, 12L);
  }

  @Test
  void shouldKeepPaging_whenAPageIsFull() {
    when(kols.pageKolIdsWithTemplate(anyLong(), isNull(), anyInt()))
        .thenReturn(
            java.util.stream.LongStream.rangeClosed(1, LinkIndexListener.TEMPLATE_PAGE)
                .boxed()
                .toList());
    when(kols.pageKolIdsWithTemplate(anyLong(), anyLong(), anyInt())).thenReturn(List.of(999L));

    listener.onLinkTemplateChanged(new LinkTemplateChangedEvent(77L));

    verify(outbox, times(LinkIndexListener.TEMPLATE_PAGE + 1)).insert(any(LinkIndexOutboxDO.class));
  }

  @Test
  void shouldPropagateInsertFailure() {
    when(outbox.insert(any(LinkIndexOutboxDO.class)))
        .thenThrow(new IllegalStateException("db down"));
    assertThatThrownBy(() -> listener.onKolStatusChanged(new KolStatusChangedEvent(10L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("db down");
  }
}
