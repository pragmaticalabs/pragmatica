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
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


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
    /// Strict-absence mode: a decisive value completes the read at once; otherwise completion waits
    /// until every slot has answered. Not used (never true) in quorum-count mode.
    private final boolean awaitAll;
    private final Predicate<T> decisive;

    private QuorumCollector(int quorum,
                            int total,
                            Promise<T> promise,
                            UnaryOperator<T> valueMerger,
                            boolean awaitAll,
                            Predicate<T> decisive) {
        this.quorum = quorum;
        this.total = total;
        this.promise = promise;
        this.valueMerger = valueMerger;
        this.awaitAll = awaitAll;
        this.decisive = decisive;
    }

    /// Create a quorum collector that keeps the first value received.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <T> QuorumCollector<T> quorumCollector(int quorum, int total, Promise<T> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity(), false, _ -> false);
    }

    /// Create a quorum collector for Option values that prefers non-empty over empty.
    /// This prevents the race where an empty Option from one replica masks actual data from another.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <V> QuorumCollector<Option<V>> optionCollector(int quorum, int total, Promise<Option<V>> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity(), false, _ -> false);
    }

    /// Create a collector for a read whose ABSENT answer must be earned: the first non-empty reply
    /// completes the read at once (FOUND), while an empty outcome resolves only after EVERY slot has
    /// answered (a slot that failed counts as answered, as long as at least `quorum` slots answered
    /// successfully; otherwise the usual fast-fail fires). A slot that never answers keeps the read
    /// open until the caller's deadline, so a late reply carrying the value is never discarded.
    ///
    /// @param quorum  minimum successful (empty or not) answers needed for an ABSENT result
    /// @param total   number of slots (R-set replicas) expected to answer
    /// @param promise promise to resolve when found, absent, or quorum becomes impossible
    public static <V> QuorumCollector<Option<V>> strictAbsenceCollector(int quorum,
                                                                        int total,
                                                                        Promise<Option<V>> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity(), true, Option::isPresent);
    }

    /// Record a successful response. Resolves promise when quorum reached.
    @Contract
    public void onSuccess(T value) {
        bestValue.accumulateAndGet(value, this::selectBest);
        var answered = successCount.incrementAndGet();

        if (awaitAll
            ? decisive.test(value) || allAnswered(answered, failureCount.get())
            : answered >= quorum) {
            promise.succeed(bestValue.get());
        }
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
        } else if (awaitAll && allAnswered(successCount.get(), failures)) {
            promise.succeed(bestValue.get());
        }
    }

    /// Every slot has answered or failed, and enough answered to stand as a quorum.
    private boolean allAnswered(int answered, int failed) {
        return answered + failed >= total && answered >= quorum;
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
