/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
 */
package org.pragmatica.dht;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Collects responses from multiple nodes and resolves a promise when quorum is reached.
/// Thread-safe: multiple threads can call onSuccess/onFailure concurrently.
///
/// @param <T> the type of successful response value
public final class QuorumCollector<T> {
    private final int quorum;
    private final int total;
    private final Promise<T> promise;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<T> bestValue = new AtomicReference<>();
    private final UnaryOperator<T> valueMerger;
    private final long createdNanos = System.nanoTime();
    private final AtomicReference<String> valueSource = new AtomicReference<>();
    private final Promise<Unit> allReplied = Promise.promise();

    private QuorumCollector(int quorum, int total, Promise<T> promise, UnaryOperator<T> valueMerger) {
        this.quorum = quorum;
        this.total = total;
        this.promise = promise;
        this.valueMerger = valueMerger;
    }

    /// Create a quorum collector that keeps the first value received.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <T> QuorumCollector<T> quorumCollector(int quorum, int total, Promise<T> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity());
    }

    /// Create a quorum collector for Option values that prefers non-empty over empty.
    /// This prevents the race where an empty Option from one replica masks actual data from another.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <V> QuorumCollector<Option<V>> optionCollector(int quorum, int total, Promise<Option<V>> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity());
    }

    /// Record a successful response. Resolves promise when quorum reached.
    @Contract
    public void onSuccess(T value) {
        onSuccess(value, "");
    }

    /// Record a successful response and who sent it. The first replica whose reply carried a PRESENT value is
    /// remembered by [#valueSource]: when the quorum resolved empty, a value that arrives afterwards is a late
    /// value the read discarded, and this names the replica that held it. Attribution only; the resolved value
    /// is chosen exactly as before.
    @Contract
    public void onSuccess(T value, String source) {
        bestValue.accumulateAndGet(value, this::selectBest);
        if (value instanceof Option<?> option && option.isPresent()) {
            valueSource.compareAndSet(null, source);
        }

        var successes = successCount.incrementAndGet();

        if (successes >= quorum) {
            promise.succeed(bestValue.get());
        }

        settleIfAllReplied(successes, failureCount.get());
    }

    /// Record a failed response. Fails promise when quorum becomes arithmetically impossible —
    /// i.e. once the remaining responses that could still succeed (`total - failures`) drop below
    /// the required `quorum`. Equivalent to `failures > total - quorum`. This is the
    /// failure-accrual fast-fail: with `total` live targets and `quorum` required, the
    /// `(quorum)`-th failure (when `total == quorum`) or earlier (when `total > quorum`) aborts
    /// immediately rather than letting the promise stall to the per-op timeout.
    @Contract
    public void onFailure(Cause cause) {
        var failures = failureCount.incrementAndGet();

        if (total - failures < quorum) {
            promise.fail(DHTError.quorumNotReached(quorum, successCount.get()));
        }

        settleIfAllReplied(successCount.get(), failures);
    }

    private void settleIfAllReplied(int successes, int failures) {
        if (successes + failures >= total) {
            allReplied.succeed(Unit.unit());
        }
    }

    /// Resolves once every expected reply (success or failure) has arrived. Never resolves if a target never
    /// answers, so callers bound it with their own timeout.
    public Promise<Unit> allReplied() {
        return allReplied;
    }

    /// The replica whose reply carried a present value, if any did. After a read resolved EMPTY this is a late
    /// value the read discarded.
    public Option<String> valueSource() {
        return Option.option(valueSource.get());
    }

    /// Milliseconds since this collector was created, i.e. since the read it serves began.
    public long elapsedMillis() {
        return (System.nanoTime() - createdNanos) / 1_000_000L;
    }

    /// Replies recorded so far, including those arriving after the quorum resolved the promise.
    public int successCount() {
        return successCount.get();
    }

    private T selectBest(T existing, T incoming) {
        if (existing == null) {
            return incoming;
        }
        // For Option values: prefer present (non-empty) over absent (empty)
        if (existing instanceof Option<?> existingOpt && incoming instanceof Option<?> incomingOpt) {
            return incomingOpt.isPresent() && existingOpt.isEmpty()
                   ? incoming
                   : existing;
        }

        return existing;
    }
}
