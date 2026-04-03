package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for circuit breaker interceptor.
///
/// @param failureThreshold Number of failures before opening the circuit
/// @param resetTimeout     Time to wait before attempting to close the circuit
/// @param testAttempts     Number of successful calls in half-open state before closing
public record CircuitBreakerConfig(int failureThreshold, TimeSpan resetTimeout, int testAttempts) {
    @SuppressWarnings("JBCT-VO-02") private static final CircuitBreakerConfig DEFAULTS = new CircuitBreakerConfig(5,
                                                                                                                  timeSpan(30).seconds(),
                                                                                                                  3);

    public static CircuitBreakerConfig circuitBreakerConfig() {
        return DEFAULTS;
    }

    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold) {
        return ensure(failureThreshold, Verify.Is::positive).map(threshold -> new CircuitBreakerConfig(threshold,
                                                                                                       timeSpan(30).seconds(),
                                                                                                       3));
    }

    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold,
                                                                    TimeSpan resetTimeout,
                                                                    int testAttempts) {
        var validThreshold = ensure(failureThreshold, Verify.Is::positive);
        var validTimeout = ensure(resetTimeout, Verify.Is::notNull);
        var validAttempts = ensure(testAttempts, Verify.Is::positive);
        return all(validThreshold, validTimeout, validAttempts).map(CircuitBreakerConfig::new);
    }
}
