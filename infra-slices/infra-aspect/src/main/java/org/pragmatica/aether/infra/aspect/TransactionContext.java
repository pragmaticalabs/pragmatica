package org.pragmatica.aether.infra.aspect;

import org.pragmatica.lang.Option;

import java.time.Instant;
import java.util.UUID;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;

/**
 * Context representing an active transaction.
 * Thread-safe and immutable.
 *
 * @param id            Unique transaction identifier
 * @param config        Transaction configuration
 * @param status        Current transaction status
 * @param startTime     When the transaction started
 * @param parentContext Parent transaction context (for nested transactions)
 */
public record TransactionContext(String id,
                                 TransactionConfig config,
                                 TransactionStatus status,
                                 Instant startTime,
                                 Option<TransactionContext> parentContext) {
    /**
     * Transaction status.
     */
    public enum TransactionStatus {
        ACTIVE,
        COMMITTED,
        ROLLED_BACK,
        SUSPENDED
    }

    /**
     * Creates a new transaction context.
     */
    public static TransactionContext transactionContext(TransactionConfig config) {
        return new TransactionContext(UUID.randomUUID()
                                          .toString(),
                                      config,
                                      TransactionStatus.ACTIVE,
                                      Instant.now(),
                                      none());
    }

    /**
     * Creates a nested transaction context.
     */
    public static TransactionContext nestedContext(TransactionConfig config, TransactionContext parent) {
        return new TransactionContext(UUID.randomUUID()
                                          .toString(),
                                      config,
                                      TransactionStatus.ACTIVE,
                                      Instant.now(),
                                      option(parent));
    }

    /**
     * Creates a new context with committed status.
     */
    public TransactionContext commit() {
        return new TransactionContext(id, config, TransactionStatus.COMMITTED, startTime, parentContext);
    }

    /**
     * Creates a new context with rolled back status.
     */
    public TransactionContext rollback() {
        return new TransactionContext(id, config, TransactionStatus.ROLLED_BACK, startTime, parentContext);
    }

    /**
     * Creates a new context with suspended status.
     */
    public TransactionContext suspend() {
        return new TransactionContext(id, config, TransactionStatus.SUSPENDED, startTime, parentContext);
    }

    /**
     * Creates a new context with active status (resume from suspended).
     */
    public TransactionContext resume() {
        return new TransactionContext(id, config, TransactionStatus.ACTIVE, startTime, parentContext);
    }

    /**
     * Checks if the transaction is active.
     */
    public boolean isActive() {
        return status == TransactionStatus.ACTIVE;
    }

    /**
     * Checks if the transaction has a parent (is nested).
     */
    public boolean isNested() {
        return parentContext.isPresent();
    }

    /**
     * Checks if the transaction has timed out.
     */
    public boolean isTimedOut() {
        return config.timeout()
                     .filter(timeout -> {
                                 var elapsed = java.time.Duration.between(startTime,
                                                                          Instant.now());
                                 return elapsed.toMillis() > timeout.millis();
                             })
                     .isPresent();
    }
}
