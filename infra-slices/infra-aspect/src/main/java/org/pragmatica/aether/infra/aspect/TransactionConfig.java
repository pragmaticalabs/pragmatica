package org.pragmatica.aether.infra.aspect;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/**
 * Configuration for transaction behavior.
 *
 * @param propagation   Transaction propagation behavior
 * @param isolation     Transaction isolation level
 * @param timeout       Transaction timeout (optional)
 * @param readOnly      Whether the transaction is read-only
 * @param rollbackFor   Exception classes that should trigger rollback (empty = rollback on all)
 */
public record TransactionConfig(TransactionPropagation propagation,
                                IsolationLevel isolation,
                                Option<TimeSpan> timeout,
                                boolean readOnly,
                                Class< ?>[] rollbackFor) {
    private static final TransactionPropagation DEFAULT_PROPAGATION = TransactionPropagation.REQUIRED;
    private static final IsolationLevel DEFAULT_ISOLATION = IsolationLevel.DEFAULT;
    private static final Class< ? >[] EMPTY_ROLLBACK_FOR = new Class< ?>[0];

    /**
     * Creates default transaction configuration.
     */
    public static Result<TransactionConfig> transactionConfig() {
        return success(new TransactionConfig(DEFAULT_PROPAGATION,
                                             DEFAULT_ISOLATION,
                                             Option.none(),
                                             false,
                                             EMPTY_ROLLBACK_FOR));
    }

    /**
     * Creates configuration with specified propagation.
     */
    public static Result<TransactionConfig> transactionConfig(TransactionPropagation propagation) {
        return option(propagation)
                     .toResult(TransactionError.invalidConfig("Propagation cannot be null"))
                     .map(p -> new TransactionConfig(p,
                                                     DEFAULT_ISOLATION,
                                                     Option.none(),
                                                     false,
                                                     EMPTY_ROLLBACK_FOR));
    }

    /**
     * Creates configuration with specified propagation and isolation.
     */
    public static Result<TransactionConfig> transactionConfig(TransactionPropagation propagation,
                                                              IsolationLevel isolation) {
        return option(propagation)
                     .toResult(TransactionError.invalidConfig("Propagation cannot be null"))
                     .flatMap(p -> option(isolation)
                                         .toResult(TransactionError.invalidConfig("Isolation cannot be null"))
                                         .map(i -> new TransactionConfig(p,
                                                                         i,
                                                                         Option.none(),
                                                                         false,
                                                                         EMPTY_ROLLBACK_FOR)));
    }

    /**
     * Creates a new configuration with the specified propagation.
     */
    public TransactionConfig withPropagation(TransactionPropagation propagation) {
        return new TransactionConfig(propagation, isolation, timeout, readOnly, rollbackFor);
    }

    /**
     * Creates a new configuration with the specified isolation level.
     */
    public TransactionConfig withIsolation(IsolationLevel isolation) {
        return new TransactionConfig(propagation, isolation, timeout, readOnly, rollbackFor);
    }

    /**
     * Creates a new configuration with the specified timeout.
     */
    public TransactionConfig withTimeout(TimeSpan timeout) {
        return new TransactionConfig(propagation, isolation, Option.option(timeout), readOnly, rollbackFor);
    }

    /**
     * Creates a new configuration marked as read-only.
     */
    public TransactionConfig asReadOnly() {
        return new TransactionConfig(propagation, isolation, timeout, true, rollbackFor);
    }

    /**
     * Creates a new configuration with specified rollback exceptions.
     */
    public TransactionConfig withRollbackFor(Class<?>... exceptions) {
        return new TransactionConfig(propagation, isolation, timeout, readOnly, exceptions);
    }
}
