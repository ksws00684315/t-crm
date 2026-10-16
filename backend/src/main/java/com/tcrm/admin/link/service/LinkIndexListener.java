package com.tcrm.admin.link.service;

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
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registers link-index outbox rows in the business transaction (task 06b, task 11 §4.4). Rows are
 * inserted before commit and disappear with the transaction if the business change rolls back.
 * Redis is written later by {@link LinkIndexSyncJob}; a failure here fails the business
 * transaction.
 */
@Component
public class LinkIndexListener {
  /** KOLs registered per database page when a template change fans out to its users. */
  static final int TEMPLATE_PAGE = 200;

  private final LinkIndexOutboxMapper outbox;
  private final KolApi kols;
  private final Clock clock;

  @Autowired
  public LinkIndexListener(LinkIndexOutboxMapper outbox, KolApi kols) {
    this(outbox, kols, Clock.systemUTC());
  }

  LinkIndexListener(LinkIndexOutboxMapper outbox, KolApi kols, Clock clock) {
    this.outbox = outbox;
    this.kols = kols;
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

  /**
   * A KOL's template or invite code changed: both hashes of that KOL are rebuilt because every
   * {@code targetUrl} of the KOL changes (task 11 §4.4).
   */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onKolLinkTargetChanged(KolLinkTargetChangedEvent event) {
    insert(LinkIndexScope.KOL, event.kolId(), null);
  }

  /**
   * A template's URL or status changed: registers one KOL-scope row per KOL using the template,
   * paged through the database so a popular template does not load every user at once (task 11
   * §4.4).
   */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onLinkTemplateChanged(LinkTemplateChangedEvent event) {
    Long templateId = event.templateId();
    Long after = null;
    while (true) {
      List<Long> kolIds = kols.pageKolIdsWithTemplate(templateId, after, TEMPLATE_PAGE);
      if (kolIds.isEmpty()) {
        return;
      }
      for (Long kolId : kolIds) {
        insert(LinkIndexScope.KOL, kolId, null);
      }
      after = kolIds.getLast();
      if (kolIds.size() < TEMPLATE_PAGE) {
        return;
      }
    }
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
