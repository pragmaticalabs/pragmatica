package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Factory for creating circuit breaker aspects.
 * Wraps Promise-returning slice method invocations with circuit breaker logic.
 */
public interface CircuitBreakerFactory extends Slice {
    /**
     * Create a circuit breaker aspect with the given configuration.
     *
     * @param config Circuit breaker configuration
     * @param <T>    Target type
     * @return Aspect that wraps the target with circuit breaker logic
     */
    <T> Aspect<T> create(CircuitBreakerConfig config);

    /**
     * Create a circuit breaker aspect with default configuration.
     * Note: This method uses unwrap() as it's infrastructure code where
     * a programming error should fail fast.
     *
     * @param <T> Target type
     * @return Aspect that wraps the target with circuit breaker logic
     */
    default <T> Aspect<T> create() {
        return create(CircuitBreakerConfig.circuitBreakerConfig()
                                          .unwrap());
    }

    /**
     * Enable or disable circuit breaker logic globally.
     *
     * @param enabled Whether circuit breaker is enabled
     * @return Unit
     */
    Unit setEnabled(boolean enabled);

    /**
     * Check if circuit breaker is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Factory method.
     *
     * @return CircuitBreakerFactory instance
     */
    static CircuitBreakerFactory circuitBreakerFactory() {
        return new DefaultCircuitBreakerFactory();
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
