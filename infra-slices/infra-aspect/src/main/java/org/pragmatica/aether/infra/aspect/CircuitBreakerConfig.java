package org.pragmatica.aether.infra.aspect;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Configuration for circuit breaker aspect.
 *
 * @param failureThreshold Number of failures before opening the circuit
 * @param resetTimeout     Time to wait before attempting to close the circuit
 * @param testAttempts     Number of successful calls in half-open state before closing
 */
public record CircuitBreakerConfig(int failureThreshold, TimeSpan resetTimeout, int testAttempts) {
    /**
     * Create circuit breaker configuration with default values.
     * Default: 5 failures, 30 second reset timeout, 3 test attempts.
     *
     * @return Result containing configuration
     */
    public static Result<CircuitBreakerConfig> circuitBreakerConfig() {
        return Result.success(new CircuitBreakerConfig(5, timeSpan(30)
                                                                  .seconds(), 3));
    }

    /**
     * Create circuit breaker configuration with custom failure threshold.
     *
     * @param failureThreshold Number of failures before opening
     * @return Result containing configuration or error
     */
    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold) {
        return ensure(failureThreshold, Verify.Is::positive)
                     .map(threshold -> new CircuitBreakerConfig(threshold,
                                                                timeSpan(30)
                                                                        .seconds(),
                                                                3));
    }

    /**
     * Create circuit breaker configuration with custom parameters.
     *
     * @param failureThreshold Number of failures before opening
     * @param resetTimeout     Time to wait before half-open state
     * @param testAttempts     Successful calls needed in half-open to close
     * @return Result containing configuration or error
     */
    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold,
                                                                    TimeSpan resetTimeout,
                                                                    int testAttempts) {
        return ensure(failureThreshold, Verify.Is::positive)
                     .flatMap(threshold -> ensure(resetTimeout, Verify.Is::notNull)
                                                 .flatMap(timeout -> ensure(testAttempts, Verify.Is::positive)
                                                                           .map(attempts -> new CircuitBreakerConfig(threshold,
                                                                                                                     timeout,
                                                                                                                     attempts))));
    }
}
