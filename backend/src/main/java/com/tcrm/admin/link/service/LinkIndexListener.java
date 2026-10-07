package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolStatusChangedEvent;
import com.tcrm.admin.link.mapper.LinkIndexOutboxMapper;
import com.tcrm.admin.link.model.LinkIndexOutboxDO;
import com.tcrm.admin.link.model.LinkIndexScope;
import com.tcrm.admin.product.api.KolItemChangedEvent;
import com.tcrm.admin.product.api.ProductStatusChangedEvent;
import com.tcrm.admin.product.api.ShopStatusChangedEvent;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registers one link-index outbox row in the business transaction (task 06b). The row is inserted
 * before commit and disappears with the transaction if the business change rolls back. Redis is
 * written later by {@link LinkIndexSyncJob}; a failure here fails the business transaction.
 */
@Component
public class LinkIndexListener {
  private final LinkIndexOutboxMapper outbox;
  private final Clock clock;

  @Autowired
  public LinkIndexListener(LinkIndexOutboxMapper outbox) {
    this(outbox, Clock.systemUTC());
  }

  LinkIndexListener(LinkIndexOutboxMapper outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onKolItemChanged(KolItemChangedEvent event) {
    insert(LinkIndexScope.MAPPING, event.kolId(), event.productId());
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onProductStatusChanged(ProductStatusChangedEvent event) {
    insert(LinkIndexScope.PRODUCT, event.productId(), null);
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onShopStatusChanged(ShopStatusChangedEvent event) {
    insert(LinkIndexScope.SHOP, event.shopId(), null);
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onKolStatusChanged(KolStatusChangedEvent event) {
    insert(LinkIndexScope.KOL, event.kolId(), null);
  }

  private void insert(LinkIndexScope scope, Long scopeId, Long productId) {
    Instant now = clock.instant();
    LinkIndexOutboxDO row = new LinkIndexOutboxDO();
    row.setScope(scope);
    row.setScopeId(scopeId);
    row.setProductId(productId);
    row.setAttempts(0);
    row.setNextAttemptAt(now);
    row.setCreateTime(now);
    outbox.insert(row);
  }
}
