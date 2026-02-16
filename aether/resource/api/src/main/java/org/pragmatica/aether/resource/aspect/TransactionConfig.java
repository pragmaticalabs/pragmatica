package org.pragmatica.aether.resource.aspect;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Configuration for transaction behavior.
///
/// @param propagation   Transaction propagation behavior
/// @param isolation     Transaction isolation level
/// @param timeout       Transaction timeout (optional)
/// @param readOnly      Whether the transaction is read-only
/// @param rollbackFor   Exception classes that should trigger rollback (empty = rollback on all)
public record TransactionConfig(TransactionPropagation propagation,
                                IsolationLevel isolation,
                                Option<TimeSpan> timeout,
                                boolean readOnly,
                                Class<?>[] rollbackFor) {
    private static final TransactionPropagation DEFAULT_PROPAGATION = TransactionPropagation.REQUIRED;
    private static final IsolationLevel DEFAULT_ISOLATION = IsolationLevel.DEFAULT;
    private static final Class<?>[] EMPTY_ROLLBACK_FOR = new Class<?>[0];

    /// Creates default transaction configuration.
    public static Result<TransactionConfig> transactionConfig() {
        return success(new TransactionConfig(DEFAULT_PROPAGATION, DEFAULT_ISOLATION, none(), false, EMPTY_ROLLBACK_FOR));
    }

    /// Creates configuration with specified propagation.
    public static Result<TransactionConfig> transactionConfig(TransactionPropagation propagation) {
        return option(propagation).toResult(TransactionError.invalidConfig("Propagation cannot be null"))
                     .map(TransactionConfig::withDefaultsFrom);
    }

    /// Creates configuration with specified propagation and isolation.
    public static Result<TransactionConfig> transactionConfig(TransactionPropagation propagation,
                                                              IsolationLevel isolation) {
        var validPropagation = option(propagation).toResult(TransactionError.invalidConfig("Propagation cannot be null"));
        var validIsolation = option(isolation).toResult(TransactionError.invalidConfig("Isolation cannot be null"));
        return Result.all(validPropagation, validIsolation)
                     .map(TransactionConfig::withPropagationAndIsolation);
    }

    @SuppressWarnings({"JBCT-VO-02", "JBCT-NAM-01"})
    private static TransactionConfig withDefaultsFrom(TransactionPropagation p) {
        return new TransactionConfig(p, DEFAULT_ISOLATION, none(), false, EMPTY_ROLLBACK_FOR);
    }

    @SuppressWarnings({"JBCT-VO-02", "JBCT-NAM-01"})
    private static TransactionConfig withPropagationAndIsolation(TransactionPropagation p, IsolationLevel i) {
        return new TransactionConfig(p, i, none(), false, EMPTY_ROLLBACK_FOR);
    }

    /// Creates a new configuration with the specified propagation.
    @SuppressWarnings("JBCT-VO-02")
    public TransactionConfig withPropagation(TransactionPropagation propagation) {
        return new TransactionConfig(propagation, isolation, timeout, readOnly, rollbackFor);
    }

    /// Creates a new configuration with the specified isolation level.
    @SuppressWarnings("JBCT-VO-02")
    public TransactionConfig withIsolation(IsolationLevel isolation) {
        return new TransactionConfig(propagation, isolation, timeout, readOnly, rollbackFor);
    }

    /// Creates a new configuration with the specified timeout.
    @SuppressWarnings("JBCT-VO-02")
    public TransactionConfig withTimeout(TimeSpan timeout) {
        return new TransactionConfig(propagation, isolation, option(timeout), readOnly, rollbackFor);
    }

    /// Creates a new configuration marked as read-only.
    @SuppressWarnings("JBCT-VO-02")
    public TransactionConfig asReadOnly() {
        return new TransactionConfig(propagation, isolation, timeout, true, rollbackFor);
    }

    /// Creates a new configuration with specified rollback exceptions.
    @SuppressWarnings("JBCT-VO-02")
    public TransactionConfig withRollbackFor(Class<?>... exceptions) {
        return new TransactionConfig(propagation, isolation, timeout, readOnly, exceptions);
    }
}
