package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Factory for creating transaction aspects.
 * Wraps Promise-returning slice method invocations with transaction management.
 */
public interface TransactionAspectFactory extends Slice {
    /**
     * Create a transaction aspect with the given configuration.
     *
     * @param config Transaction configuration
     * @param <T>    Target type
     * @return Aspect that wraps the target with transaction management
     */
    <T> Aspect<T> create(TransactionConfig config);

    /**
     * Create a transaction aspect with default configuration (REQUIRED propagation).
     *
     * @param <T> Target type
     * @return Aspect that wraps the target with transaction management
     */
    default <T> Aspect<T> create() {
        return create(TransactionConfig.transactionConfig()
                                       .unwrap());
    }

    /**
     * Create a transaction aspect with specified propagation.
     *
     * @param propagation Transaction propagation behavior
     * @param <T>         Target type
     * @return Aspect that wraps the target with transaction management
     */
    default <T> Aspect<T> create(TransactionPropagation propagation) {
        return create(TransactionConfig.transactionConfig(propagation)
                                       .unwrap());
    }

    /**
     * Get the current transaction context (if any).
     *
     * @return Current transaction context
     */
    Option<TransactionContext> currentTransaction();

    /**
     * Begin a new transaction manually.
     *
     * @param config Transaction configuration
     * @return Promise with the transaction context
     */
    Promise<TransactionContext> begin(TransactionConfig config);

    /**
     * Commit the current transaction.
     *
     * @return Promise with Unit on success
     */
    Promise<Unit> commit();

    /**
     * Rollback the current transaction.
     *
     * @return Promise with Unit on success
     */
    Promise<Unit> rollback();

    /**
     * Enable or disable transaction management globally.
     *
     * @param enabled Whether transactions are enabled
     * @return Unit
     */
    Unit setEnabled(boolean enabled);

    /**
     * Check if transaction management is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Factory method.
     *
     * @return TransactionAspectFactory instance
     */
    static TransactionAspectFactory transactionAspectFactory() {
        return new DefaultTransactionAspectFactory();
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
