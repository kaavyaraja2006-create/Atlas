package com.atlas.audit;

import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One business command runs in one database transaction and gets one
 * txn_id. Every change event written in that transaction shares it, which is
 * how the audit log groups "the price edit" with "the 3 order totals it
 * changed". The id lives only as long as the transaction.
 */
public final class TransactionIds {

    private static final Object KEY = new Object();

    private TransactionIds() {
    }

    public static UUID current() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Change events must be recorded inside a transaction");
        }
        UUID id = (UUID) TransactionSynchronizationManager.getResource(KEY);
        if (id == null) {
            UUID fresh = UUID.randomUUID();
            TransactionSynchronizationManager.bindResource(KEY, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
                }
            });
            id = fresh;
        }
        return id;
    }
}
