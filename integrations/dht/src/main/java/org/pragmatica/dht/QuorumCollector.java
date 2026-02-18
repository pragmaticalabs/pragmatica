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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

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
    public void onSuccess(T value) {
        bestValue.accumulateAndGet(value, this::selectBest);
        if (successCount.incrementAndGet() == quorum) {
            promise.succeed(bestValue.get());
        }
    }

    /// Record a failed response. Fails promise when quorum becomes impossible.
    public void onFailure(Cause cause) {
        var failures = failureCount.incrementAndGet();
        if (failures > total - quorum) {
            promise.fail(DHTError.quorumNotReached(quorum, successCount.get()));
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
