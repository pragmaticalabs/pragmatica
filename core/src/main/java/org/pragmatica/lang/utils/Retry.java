/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.lang.utils;

import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A utility class implementing the Retry pattern using Promise for handling asynchronous operations.
/// This implementation uses a staged fluent builder pattern where all parameters are mandatory.
///
/// ```java
/// Retry.create(5, exponential(timeout(100).millis(),
///                             timeout(5).seconds(),
///                             10.0,
///                             false))
///      .execute(operation)
///```
/// The implementation is stateless and thread-safe, so single instance could be used to run several
/// requests at once.
///
/// ## Logging contract (#718) — at most ONE WARN per [#execute] call
///
/// Volume here is multiplied by the attempt count, so the level is part of the contract rather than a
/// detail. A per-attempt WARN on a hot retry path is an unbounded synchronous log: measured at
/// `200 attempts x 25ms` on the QUIC consensus send wrapper it produced ~4.2 MILLION lines in 72
/// seconds through a single synchronous Console appender, starving the very event loops whose
/// progress the retries were waiting on, and the Forge cluster then missed its 60s formation budget
/// 3/3. Silencing only this logger converted that to 4/4 healthy formations in 8.4s.
///
/// Therefore:
///
///   - **Per-attempt progress is DEBUG.** Off by default; raise
///     `org.pragmatica.lang.utils.Retry` to DEBUG to get every attempt back. This is also the level
///     the busiest caller already documents for its own per-retry lines
///     (`QuicClusterNetwork#retryBackpressuredWrite`), so WARN here was overriding a level the call
///     site had already chosen.
///   - **Giving up is WARN, and carries the attempt count.** Both terminal paths — an unretryable
///     `Cause` and a spent attempt budget — emit exactly one line. This is what keeps a retry burst
///     discoverable after the demotion: the count IS the aggregate. Before #718 the spent-budget
///     path logged nothing at all.
///
/// The resulting bound is structural, not a measurement: WARN lines <= calls to [#execute],
/// independent of `maxAttempts`. Do not reintroduce a WARN inside the retry loop.
public interface Retry {
    /// Executes an asynchronous operation with retry logic.
    ///
    /// @param operation The async operation to retry
    /// @param <T>       The type of result returned by the operation
    ///
    /// @return A Promise containing the result of the successful operation
    <T> Promise<T> execute(Supplier<Promise<T>> operation);

    /// Create Retry with specified maximal number of attempts and delay calculation strategy.
    static RetryStageMaxAttempts retry() {
        record retry(int maxAttempts, BackoffStrategy backoffStrategy) implements Retry {
            @Override
            public <T> Promise<T> execute(Supplier<Promise<T>> operation) {
                return executeWithLoop(operation, 1, Promise.promise());
            }

            private <T> Promise<T> executeWithLoop(Supplier<Promise<T>> operation, int attempt, Promise<T> output) {
                operation.get().fold(result -> handle(operation, attempt, output, result));

                return output;
            }

            private <T> Promise<T> handle(Supplier<Promise<T>> operation,
                                          int attempt,
                                          Promise<T> output,
                                          Result<T> result) {
                return switch (result) {
                    case Result.Success<T> success -> output.succeed(success.value());
                    case Result.Failure<T> failure when failure.cause().isTerminal() -> {
                        log.warn("Operation failed with a TERMINAL cause (attempt {}/{}), not retrying: {}",
                                 attempt,
                                 maxAttempts,
                                 failure.cause().message());
                        yield output.fail(failure.cause());
                    }
                    case Result.Failure<T> failure when(attempt >= maxAttempts) -> {
                        log.warn("Operation failed after {} of {} attempts, giving up: {}",
                                 attempt,
                                 maxAttempts,
                                 failure.cause().message());
                        yield output.fail(failure.cause());
                    }
                    case Result.Failure<T> failure -> {
                        var delay = backoffStrategy.nextTimeout(attempt);

                        log.debug("Operation failed (attempt {}/{}), retrying after {}: {}",
                                  attempt,
                                  maxAttempts,
                                  delay,
                                  failure.cause().message());
                        SharedScheduler.schedule(() -> executeWithLoop(operation, attempt + 1, output), delay);
                        yield output;
                    }
                };
            }

            private static final Logger log = LoggerFactory.getLogger(Retry.class);
        }

        return maxAttempts -> backoffStrategy -> new retry(maxAttempts, backoffStrategy);
    }

    @Deprecated(forRemoval = true)
    static RetryStageMaxAttempts create() {
        return retry();
    }

    interface RetryStageMaxAttempts {
        RetryStageBackoffStrategy attempts(int maxAttempts);
    }

    interface RetryStageBackoffStrategy {
        Retry strategy(BackoffStrategy backoffStrategy);
    }

    interface BackoffStrategy {
        /// Calculate the delay for a given retry attempt
        ///
        /// @param attempt the current attempt number (1-based)
        ///
        /// @return next attempt delay
        TimeSpan nextTimeout(int attempt);

        /// Creates a fixed backoff strategy that always returns the same delay
        static FixedStage fixed() {
            record fixedBackoffStrategy(TimeSpan interval) implements BackoffStrategy {
                @Override
                public TimeSpan nextTimeout(int attempt) {
                    return interval;
                }
            }

            return fixedBackoffStrategy::new;
        }

        interface FixedStage {
            BackoffStrategy interval(TimeSpan interval);
        }

        /// Creates an exponential backoff strategy with configurable parameters
        static ExponentialStageInitialDelay exponential() {
            record exponentialBackoffStrategy(TimeSpan initialDelay,
                                              TimeSpan maxDelay,
                                              double factor,
                                              boolean withJitter) implements BackoffStrategy {
                @Override
                public TimeSpan nextTimeout(int attempt) {
                    var multiplier = Math.pow(factor, attempt - 1);

                    if (withJitter) {
                        // Add jitter between 0.9 and 1.1
                        multiplier *= 0.9 + Math.random() * 0.2;
                    }

                    long delay = (long)(initialDelay.nanos() * multiplier);

                    return timeSpan(Math.min(delay, maxDelay.nanos())).nanos();
                }
            }

            return initialDelay -> maxDelay -> factor -> withJitter -> new exponentialBackoffStrategy(initialDelay,
                                                                                                      maxDelay,
                                                                                                      factor,
                                                                                                      withJitter);
        }

        interface ExponentialStageInitialDelay {
            ExponentialStageMaxDelay initialDelay(TimeSpan initialDelay);
        }

        interface ExponentialStageMaxDelay {
            ExponentialStageFactor maxDelay(TimeSpan maxDelay);
        }

        interface ExponentialStageFactor {
            ExponentialStageWithJitter factor(double factor);
        }

        interface ExponentialStageWithJitter {
            BackoffStrategy jitter(boolean withJitter);

            default BackoffStrategy withJitter() {
                return jitter(true);
            }

            default BackoffStrategy withoutJitter() {
                return jitter(false);
            }
        }

        /// Creates a linear backoff strategy with configurable parameters
        static LinearStageInitialDelay linear() {
            record linearBackoffStrategy(TimeSpan initialDelay, TimeSpan increment, TimeSpan maxDelay) implements BackoffStrategy {
                @Override
                public TimeSpan nextTimeout(int attempt) {
                    long delay = initialDelay.nanos() + (increment.nanos() * (attempt - 1));

                    return timeSpan(Math.min(delay, maxDelay.nanos())).nanos();
                }
            }

            return initialDelay -> increment -> maxDelay -> new linearBackoffStrategy(initialDelay, increment, maxDelay);
        }

        // Linear
        interface LinearStageInitialDelay {
            LinearStageIncrement initialDelay(TimeSpan initialDelay);
        }

        interface LinearStageIncrement {
            LinearStageMaxDelay increment(TimeSpan increment);
        }

        interface LinearStageMaxDelay {
            BackoffStrategy maxDelay(TimeSpan maxDelay);
        }
    }
}
