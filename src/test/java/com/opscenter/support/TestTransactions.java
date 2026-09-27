package com.opscenter.support;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link TransactionTemplate} for Mockito unit tests: runs the callback immediately with no
 * database behind it. Services that own their transaction boundary programmatically
 * ({@code AuthenticationService}, {@code IdempotencyService}, {@code AdminAccountBootstrap}) can
 * then be tested exactly like before, and the "throw after commit" ordering is still exercised
 * because the callback returns before the caller inspects the outcome.
 */
public final class TestTransactions {

    private TestTransactions() {
    }

    public static TransactionTemplate passThrough() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // nothing to commit
            }

            @Override
            public void rollback(TransactionStatus status) {
                // nothing to roll back
            }
        });
    }
}
