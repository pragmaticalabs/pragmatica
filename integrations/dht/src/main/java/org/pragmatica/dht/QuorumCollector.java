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
import java.util.function.Function;
import java.util.function.Predicate;
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
    /// Grace mode (see [#graceCollector]): a decisive value completes the read at once; R non-decisive
    /// answers do not, they only start the grace window. Always false in quorum-count mode.
    private final boolean graceMode;
    private final Predicate<T> decisive;
    private final Function<QuorumCollector<T>, Unit> onQuorumNotDecisive;

    private QuorumCollector(int quorum,
                            int total,
                            Promise<T> promise,
                            UnaryOperator<T> valueMerger,
                            boolean graceMode,
                            Predicate<T> decisive,
                            Function<QuorumCollector<T>, Unit> onQuorumNotDecisive) {
        this.quorum = quorum;
        this.total = total;
        this.promise = promise;
        this.valueMerger = valueMerger;
        this.graceMode = graceMode;
        this.decisive = decisive;
        this.onQuorumNotDecisive = onQuorumNotDecisive;
    }

    /// Create a quorum collector that keeps the first value received.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <T> QuorumCollector<T> quorumCollector(int quorum, int total, Promise<T> promise) {
        return new QuorumCollector<>(quorum,
                                     total,
                                     promise,
                                     UnaryOperator.identity(),
                                     false,
                                     _ -> false,
                                     _ -> Unit.unit());
    }

    /// Create a quorum collector for Option values that prefers non-empty over empty.
    /// This prevents the race where an empty Option from one replica masks actual data from another.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <V> QuorumCollector<Option<V>> optionCollector(int quorum, int total, Promise<Option<V>> promise) {
        return new QuorumCollector<>(quorum,
                                     total,
                                     promise,
                                     UnaryOperator.identity(),
                                     false,
                                     _ -> false,
                                     _ -> Unit.unit());
    }

    /// Create a collector for a read with an absent GRACE window. A present reply completes the read
    /// at once (FOUND). Once `quorum` empty replies are in, the read is not resolved immediately:
    /// `onQuorumEmpty` is invoked exactly once so the caller can bound the wait (it should arrange
    /// [#resolveWithBest] after the grace) while the remaining replicas may still deliver a value.
    /// If every slot answers or fails before the grace expires the read resolves absent at once.
    /// With fewer than `quorum` empty replies in, nothing completes the read but a present value, the
    /// fast-fail accrual, or the caller's deadline.
    ///
    /// @param quorum        empty replies needed before the grace window starts
    /// @param total         number of slots (original R-set replicas) expected to answer
    /// @param promise       promise to resolve
    /// @param onQuorumEmpty invoked once when `quorum` empty replies are in and no value was found
    public static <V> QuorumCollector<Option<V>> graceCollector(int quorum,
                                                                int total,
                                                                Promise<Option<V>> promise,
                                                                Function<QuorumCollector<Option<V>>, Unit> onQuorumEmpty) {
        return new QuorumCollector<>(quorum,
                                     total,
                                     promise,
                                     UnaryOperator.identity(),
                                     true,
                                     Option::isPresent,
                                     onQuorumEmpty);
    }

    /// Resolve with the best value collected so far. Used by the grace timer: by construction at
    /// least `quorum` answers are in and none was present, so the best value is the empty Option.
    @Contract
    public void resolveWithBest() {
        promise.succeed(bestValue.get());
    }

    /// Record a successful response. Resolves promise when quorum reached.
    @Contract
    public void onSuccess(T value) {
        bestValue.accumulateAndGet(value, this::selectBest);
        var answered = successCount.incrementAndGet();

        if (graceMode) {
            acceptInGraceMode(value, answered);
        } else if (answered >= quorum) {
            promise.succeed(bestValue.get());
        }
    }

    private void acceptInGraceMode(T value, int answered) {
        if (decisive.test(value) || allAnswered(answered, failureCount.get())) {
            promise.succeed(bestValue.get());
        } else if (answered == quorum) {
            var _ = onQuorumNotDecisive.apply(this);
        }
    }

    /// Every slot has answered or failed, and enough answered to stand as a quorum.
    private boolean allAnswered(int answered, int failed) {
        return answered + failed >= total && answered >= quorum;
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
        } else if (graceMode && allAnswered(successCount.get(), failures)) {
            promise.succeed(bestValue.get());
        }
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
