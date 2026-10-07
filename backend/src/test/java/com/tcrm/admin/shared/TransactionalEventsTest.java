package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionalEventsTest {
  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

  @Test
  void shouldRejectPublish_whenNoTransactionIsActive() {
    Object event = new Object();

    assertThatThrownBy(() -> TransactionalEvents.publish(publisher, event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inside a transaction");

    verifyNoInteractions(publisher);
  }

  @Test
  void shouldPublish_whenTransactionIsActive() {
    Object event = new Object();
    TransactionTemplate tx = TestTransactions.template();

    tx.executeWithoutResult(status -> TransactionalEvents.publish(publisher, event));

    verify(publisher).publishEvent(event);
  }
}
