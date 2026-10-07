package com.tcrm.admin.shared;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** A transaction manager that marks a real transaction active, for unit tests of publish rules. */
public final class TestTransactions {
  private TestTransactions() {}

  public static TransactionTemplate template() {
    return new TransactionTemplate(
        new AbstractPlatformTransactionManager() {
          @Override
          protected Object doGetTransaction() {
            return new Object();
          }

          @Override
          protected void doBegin(Object transaction, TransactionDefinition definition) {}

          @Override
          protected void doCommit(DefaultTransactionStatus status) {}

          @Override
          protected void doRollback(DefaultTransactionStatus status) {}
        });
  }
}
