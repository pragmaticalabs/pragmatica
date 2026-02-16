package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for retry interceptor.
///
/// @param maxAttempts     Maximum number of retry attempts
/// @param backoffStrategy Strategy for calculating delays between retries
public record RetryConfig(int maxAttempts, BackoffStrategy backoffStrategy) {
    /// Create retry configuration with exponential backoff defaults.
    ///
    /// @param maxAttempts Maximum retry attempts
    /// @return Result containing configuration or error
    public static Result<RetryConfig> retryConfig(int maxAttempts) {
        return ensure(maxAttempts, Verify.Is::positive).map(RetryConfig::withExponentialBackoff);
    }

    /// Create retry configuration with fixed backoff.
    ///
    /// @param maxAttempts Maximum retry attempts
    /// @param interval    Fixed interval between retries
    /// @return Result containing configuration or error
    public static Result<RetryConfig> retryConfig(int maxAttempts, TimeSpan interval) {
        return ensure(maxAttempts, Verify.Is::positive).map(attempts -> withFixedBackoff(attempts, interval));
    }

    /// Create retry configuration with custom backoff strategy.
    ///
    /// @param maxAttempts     Maximum retry attempts
    /// @param backoffStrategy Custom backoff strategy
    /// @return Result containing configuration or error
    public static Result<RetryConfig> retryConfig(int maxAttempts, BackoffStrategy backoffStrategy) {
        var validAttempts = ensure(maxAttempts, Verify.Is::positive);
        var validStrategy = ensure(backoffStrategy, Verify.Is::notNull);
        return all(validAttempts, validStrategy).map(RetryConfig::new);
    }

    @SuppressWarnings({"JBCT-VO-02", "JBCT-NAM-01"})
    private static RetryConfig withExponentialBackoff(int attempts) {
        var strategy = BackoffStrategy.exponential()
                                      .initialDelay(timeSpan(100).millis())
                                      .maxDelay(timeSpan(10).seconds())
                                      .factor(2.0)
                                      .withoutJitter();
        return new RetryConfig(attempts, strategy);
    }

    @SuppressWarnings({"JBCT-VO-02", "JBCT-NAM-01"})
    private static RetryConfig withFixedBackoff(int attempts, TimeSpan interval) {
        return new RetryConfig(attempts,
                               BackoffStrategy.fixed()
                                              .interval(interval));
    }
}
