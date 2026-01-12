package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Factory for creating retry aspects.
 * Wraps Promise-returning slice method invocations with retry logic.
 */
public interface RetryAspectFactory extends Slice {
    /**
     * Create a retry aspect with the given configuration.
     *
     * @param config Retry configuration
     * @param <T>    Target type
     * @return Aspect that wraps the target with retry logic
     */
    <T> Aspect<T> create(RetryConfig config);

    /**
     * Create a retry aspect with default configuration.
     * Note: This method uses unwrap() as it's infrastructure code where
     * a programming error (invalid maxAttempts) should fail fast.
     *
     * @param maxAttempts Maximum number of retry attempts
     * @param <T>         Target type
     * @return Aspect that wraps the target with retry logic
     */
    default <T> Aspect<T> create(int maxAttempts) {
        return create(RetryConfig.retryConfig(maxAttempts)
                                 .unwrap());
    }

    /**
     * Enable or disable retry logic globally.
     *
     * @param enabled Whether retry is enabled
     * @return Unit
     */
    Unit setEnabled(boolean enabled);

    /**
     * Check if retry is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Factory method.
     *
     * @return RetryAspectFactory instance
     */
    static RetryAspectFactory retryAspectFactory() {
        return new DefaultRetryAspectFactory();
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
