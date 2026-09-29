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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Lock-free token-bucket rate limiter.
///
/// State (token count + last-refill timestamp) is packed into a single `AtomicLong` and
/// updated via CAS. The hot path (`tryAcquire`) performs no allocation and never blocks.
///
/// Tokens refill continuously at one token per `period / rate` nanoseconds — at rate=5/sec,
/// one token is restored every 200ms. No 2× burst at period boundaries.
///
/// Thread-safe. One instance per protected resource/endpoint.
public interface RateLimiter {
    /// Attempt to acquire one permit.
    ///
    /// Lock-free, allocation-free. Refills tokens lazily from elapsed time before deciding.
    /// When the bucket is empty and no refill is due, returns without touching the state
    /// cache line — keeping the line shared across cores under sustained rejection load.
    ///
    /// @return `true` if a permit was acquired, `false` otherwise
    boolean tryAcquire();
    /// Time until the next permit becomes available.
    ///
    /// Meaningful after a `tryAcquire()` returning `false`. Always returns a positive duration.
    /// Lock-free read; no CAS.
    TimeSpan retryAfter();

    /// Execute an operation if a permit is available; fail with [RateLimiterError.LimitExceeded] otherwise.
    ///
    /// Convenience wrapper. On the admitted path, no Promise is allocated by the limiter — the
    /// operation's own Promise IS the result.
    default <T> Promise<T> execute(Supplier<Promise<T>> operation) {
        if (tryAcquire()) {
            return operation.get();
        }

        return Promise.failure(new RateLimiterError.LimitExceeded(retryAfter()));
    }

    sealed interface RateLimiterError extends Cause {
        record LimitExceeded(TimeSpan retryAfter) implements RateLimiterError, Cause.Transient {
            @Override
            public String message() {
                return "Rate limit exceeded. Retry after " + retryAfter;
            }
        }

        /// #1316 — a configuration the packed state cannot represent, refused at construction instead of
        /// being truncated or failing later in the acquisition arithmetic.
        record InvalidConfiguration(String message) implements RateLimiterError {}
    }

    /// Create a simple rate limiter with default settings.
    ///
    /// @param rate   Number of permits per period
    /// @param period Time period for rate calculation
    ///
    /// @return A new rate limiter, or [RateLimiterError.InvalidConfiguration] when the configuration cannot be
    /// represented (see [OptionalStage#timeSource])
    static Result<RateLimiter> rateLimiter(int rate, TimeSpan period) {
        return builder().rate(rate)
                      .period(period)
                      .withDefaultTimeSource();
    }

    @Deprecated(forRemoval = true)
    static Result<RateLimiter> create(int rate, TimeSpan period) {
        return rateLimiter(rate, period);
    }

    /// Create a rate limiter builder.
    ///
    /// @return A new builder
    static StageRate builder() {
        return rate -> period -> new OptionalStage(rate, period, 0, null);
    }

    interface StageRate {
        StagePeriod rate(int permits);
    }

    interface StagePeriod {
        OptionalStage period(TimeSpan period);
    }

    record OptionalStage(int rate, TimeSpan period, int burst, TimeSource timeSource) {
        public OptionalStage burst(int extraPermits) {
            return new OptionalStage(rate, period, extraPermits, timeSource);
        }

        /// Finalize the builder with the given time source.
        ///
        /// State layout (64 bits): `[tokens:16 | lastRefillNanos:48]`. `lastRefillNanos` is rebased
        /// against a base captured here and stored mod-2^48 (~3.26 days). Active limiters are
        /// unaffected; a limiter idle for longer than 2^48 ns may transiently under-refill on its
        /// next call and self-correct.
        ///
        /// #1316 — that layout bounds what can be represented, so construction refuses, with
        /// [RateLimiterError.InvalidConfiguration], a rate below 1, a negative burst, a capacity
        /// `rate + burst` above 65535 (16 token bits), a missing period or time source, a period shorter
        /// than one nanosecond per token (`period / rate` would be 0 and divide by zero on acquisition),
        /// and one token per 2^48 ns or more (the 48-bit clock could never show a token as due).
        public Result<RateLimiter> timeSource(TimeSource source) {
            return validate(source).map(_ -> build(source));
        }

        private static final long MAX_CAPACITY = (1L << 16) - 1L;
        private static final long MAX_NANOS_PER_TOKEN = (1L << 48) - 1L;

        private Result<Unit> validate(TimeSource source) {
            if (rate < 1) {
                return invalid("rate must be at least 1 permit per period, got " + rate);
            }
            if (burst < 0) {
                return invalid("burst must not be negative, got " + burst);
            }
            if ((long) rate + (long) burst > MAX_CAPACITY) {
                return invalid("rate + burst must not exceed " + MAX_CAPACITY + " (16-bit token count), got "
                               + ((long) rate + (long) burst));
            }
            if (period == null || source == null) {
                return invalid("period and time source are required");
            }
            if (period.nanos() / rate < 1L) {
                return invalid("period " + period + " is shorter than one nanosecond per permit at rate " + rate);
            }
            if (period.nanos() / rate > MAX_NANOS_PER_TOKEN) {
                return invalid("period " + period + " / rate " + rate + " exceeds the 48-bit refill clock");
            }
            return Result.unitResult();
        }

        private static Result<Unit> invalid(String reason) {
            return new RateLimiterError.InvalidConfiguration("Invalid rate limiter configuration: " + reason).result();
        }

        private RateLimiter build(TimeSource source) {
            record rateLimiter(long maxTokens,
                               long nanosPerToken,
                               TimeSource timeSource,
                               long baseNanos,
                               AtomicLong state) implements RateLimiter {
                private static final int TOKENS_SHIFT = 48;
                private static final long TIME_MASK = (1L<< 48) - 1L;

                @Override
                public boolean tryAcquire() {
                    while (true) {
                        // #1315: state first, then time, on every attempt. `lastRefill` was written by a
                        // caller that sampled before its CAS, so a time sampled AFTER reading it is never
                        // earlier; a time sampled before the read (or reused after a lost CAS) can be, and
                        // the masked difference then turns a small negative interval into ~2^48 ns of refill.
                        long observed = state.get();
                        long now = (timeSource.nanoTime() - baseNanos) & TIME_MASK;
                        long tokens = observed >>> TOKENS_SHIFT;
                        long lastRefill = observed & TIME_MASK;
                        long elapsed = (now - lastRefill) & TIME_MASK;
                        long tokensToAdd = elapsed / nanosPerToken;
                        long newTokens = Math.min(maxTokens, tokens + tokensToAdd);
                        long newLastRefill = (lastRefill + tokensToAdd * nanosPerToken) & TIME_MASK;

                        if (newTokens >= 1L) {
                            long updated = ((newTokens - 1L) << TOKENS_SHIFT) | newLastRefill;

                            if (state.compareAndSet(observed, updated)) {
                                return true;
                            }
                        } else if (newLastRefill != lastRefill) {
                            long updated = (newTokens << TOKENS_SHIFT) | newLastRefill;

                            if (state.compareAndSet(observed, updated)) {
                                return false;
                            }
                        } else {
                            return false;
                        }
                        // CAS lost — retry with freshly observed state and a fresh `now`.
                    }
                }

                @Override
                public TimeSpan retryAfter() {
                    long observed = state.get();
                    long now = (timeSource.nanoTime() - baseNanos) & TIME_MASK;
                    long lastRefill = observed & TIME_MASK;
                    long timeSinceRefill = (now - lastRefill) & TIME_MASK;
                    long fraction = timeSinceRefill % nanosPerToken;
                    long remainingNanos = nanosPerToken - fraction;

                    return timeSpan(Math.max(1L, remainingNanos)).nanos();
                }
            }
            long maxTokens = (long) rate + (long) burst;
            long nanosPerToken = period.nanos() / rate;
            long baseNanos = source.nanoTime();

            return new rateLimiter(maxTokens, nanosPerToken, source, baseNanos, new AtomicLong(maxTokens << 48));
        }

        public Result<RateLimiter> withDefaultTimeSource() {
            return timeSource(TimeSource.system());
        }
    }
}
