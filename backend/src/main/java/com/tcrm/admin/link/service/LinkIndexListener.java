package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.product.api.KolItemChangedEvent;
import com.tcrm.admin.product.api.ProductStatusChangedEvent;
import com.tcrm.admin.product.api.ShopStatusChangedEvent;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Incremental link index updates after the business transaction commits (task 06 §4.3). A failure
 * (Redis unavailable, ...) is only logged at ERROR: the committed change stands and the daily full
 * rebuild repairs the index.
 */
@Component
public class LinkIndexListener {
  private static final Logger LOG = LoggerFactory.getLogger(LinkIndexListener.class);
  private final LinkIndexService index;

  public LinkIndexListener(LinkIndexService index) {
    this.index = index;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onKolItemChanged(KolItemChangedEvent event) {
    apply("mapping", event, () -> index.refreshMapping(event.kolId(), event.productId()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProductStatusChanged(ProductStatusChangedEvent event) {
    apply("product status", event, () -> index.refreshProduct(event.productId()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onShopStatusChanged(ShopStatusChangedEvent event) {
    apply("shop status", event, () -> index.refreshShop(event.shopId()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onKolStatusChanged(KolStatusChangedEvent event) {
    apply("KOL status", event, () -> index.refreshKol(event.kolId()));
  }

  private static void apply(String change, Object event, IntSupplier update) {
    try {
      int fields = update.getAsInt();
      LOG.debug("Link index updated after {} change {}: {} fields", change, event, fields);
    } catch (RuntimeException e) {
      LOG.error(
          "Link index update after {} change {} failed; the daily rebuild will repair it",
          change,
          event,
          e);
    }
  }
}
