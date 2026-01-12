package org.pragmatica.aether.infra.aspect;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Configuration for retry aspect.
 *
 * @param maxAttempts     Maximum number of retry attempts
 * @param backoffStrategy Strategy for calculating delays between retries
 */
public record RetryConfig(int maxAttempts, BackoffStrategy backoffStrategy) {
    /**
     * Create retry configuration with exponential backoff.
     *
     * @param maxAttempts Maximum retry attempts
     * @return Result containing configuration or error
     */
    public static Result<RetryConfig> retryConfig(int maxAttempts) {
        return ensure(maxAttempts, Verify.Is::positive)
                     .map(attempts -> new RetryConfig(attempts,
                                                      BackoffStrategy.exponential()
                                                                     .initialDelay(timeSpan(100)
                                                                                           .millis())
                                                                     .maxDelay(timeSpan(10)
                                                                                       .seconds())
                                                                     .factor(2.0)
                                                                     .withoutJitter()));
    }

    /**
     * Create retry configuration with fixed backoff.
     *
     * @param maxAttempts Maximum retry attempts
     * @param interval    Fixed interval between retries
     * @return Result containing configuration or error
     */
    public static Result<RetryConfig> retryConfig(int maxAttempts, TimeSpan interval) {
        return ensure(maxAttempts, Verify.Is::positive)
                     .map(attempts -> new RetryConfig(attempts,
                                                      BackoffStrategy.fixed()
                                                                     .interval(interval)));
    }

    /**
     * Create retry configuration with custom backoff strategy.
     *
     * @param maxAttempts     Maximum retry attempts
     * @param backoffStrategy Custom backoff strategy
     * @return Result containing configuration or error
     */
    public static Result<RetryConfig> retryConfig(int maxAttempts, BackoffStrategy backoffStrategy) {
        return ensure(maxAttempts, Verify.Is::positive)
                     .flatMap(attempts -> ensure(backoffStrategy, Verify.Is::notNull)
                                                .map(strategy -> new RetryConfig(attempts, strategy)));
    }
}
