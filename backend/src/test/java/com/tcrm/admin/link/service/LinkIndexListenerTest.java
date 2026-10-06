package com.tcrm.admin.link.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.product.api.KolItemChangedEvent;
import com.tcrm.admin.product.api.ProductStatusChangedEvent;
import com.tcrm.admin.product.api.ShopStatusChangedEvent;
import org.junit.jupiter.api.Test;

class LinkIndexListenerTest {
  private final LinkIndexService index = mock(LinkIndexService.class);
  private final LinkIndexListener listener = new LinkIndexListener(index);

  @Test
  void shouldRouteEachEventToItsRefresh() {
    listener.onKolItemChanged(new KolItemChangedEvent(10L, 20L));
    listener.onProductStatusChanged(new ProductStatusChangedEvent(20L));
    listener.onShopStatusChanged(new ShopStatusChangedEvent(1L));
    listener.onKolStatusChanged(new KolStatusChangedEvent(10L));

    verify(index).refreshMapping(10L, 20L);
    verify(index).refreshProduct(20L);
    verify(index).refreshShop(1L);
    verify(index).refreshKol(10L);
  }

  @Test
  void shouldSwallowFailure_whenRedisUnavailable() {
    when(index.refreshKol(10L)).thenThrow(new IllegalStateException("redis down"));
    assertThatCode(() -> listener.onKolStatusChanged(new KolStatusChangedEvent(10L)))
        .doesNotThrowAnyException();
  }
}
