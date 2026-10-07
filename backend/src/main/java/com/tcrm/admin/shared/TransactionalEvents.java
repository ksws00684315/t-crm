package com.tcrm.admin.shared;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes application events only while a transaction is active.
 * {@code @TransactionalEventListener} drops an event published outside a transaction ({@code
 * fallbackExecution} is false), so every {@code *ChangedEvent} goes through here.
 */
public final class TransactionalEvents {
  private TransactionalEvents() {}

  /**
   * Publishes {@code event} on {@code publisher}.
   *
   * @throws IllegalStateException when no transaction is active
   */
  public static void publish(ApplicationEventPublisher publisher, Object event) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Application events must be published inside a transaction");
    }
    publisher.publishEvent(event);
  }
}
