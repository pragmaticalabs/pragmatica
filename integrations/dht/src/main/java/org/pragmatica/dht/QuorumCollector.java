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
import java.util.function.BinaryOperator;
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
    /// Slots refused by an owner-epoch fence (#1818, the owner's fence ruling).
    private final AtomicInteger fenced = new AtomicInteger(0);
    private final AtomicInteger replicationStale = new AtomicInteger(0);
    private final AtomicInteger fenceUnknown = new AtomicInteger(0);
    private final AtomicReference<T> bestValue = new AtomicReference<>();
    private final UnaryOperator<T> valueMerger;
    /// Picks the answer kept when two replicas answered: a present value over an absent one, or — for stamped
    /// entries (#1777 track 3) — the newest by owner epoch then version, so a stale value loses to a tombstone.
    private final BinaryOperator<T> selector;
    /// Slots refused by a replica still catching up (#1777 track 2). When quorum becomes unreachable and
    /// any slot was such a refusal, the read fails [DHTError.NotCaughtUp] — transient, never "absent".
    private final AtomicInteger refusals = new AtomicInteger(0);
    private final long createdNanos = System.nanoTime();
    private final AtomicReference<String> valueSource = new AtomicReference<>();
    private final AtomicInteger departed = new AtomicInteger();
    private final Promise<Unit> allReplied = Promise.promise();
    /// Successes that came from the coordinator's own slot, and the first piece of EVIDENCE from a REMOTE slot (#1777
    /// v1882 r6 F10, r9): a quorum met by the local slot alone says nothing about the replicas' fences yet.
    private final AtomicInteger localSuccesses = new AtomicInteger(0);
    private final Promise<Unit> remoteEvidence = Promise.promise();

    private QuorumCollector(int quorum,
                            int total,
                            Promise<T> promise,
                            UnaryOperator<T> valueMerger,
                            BinaryOperator<T> selector) {
        this.quorum = quorum;
        this.total = total;
        this.promise = promise;
        this.valueMerger = valueMerger;
        this.selector = selector;
    }

    /// Create a quorum collector that keeps the first value received.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <T> QuorumCollector<T> quorumCollector(int quorum, int total, Promise<T> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity(), QuorumCollector::selectBest);
    }

    /// Create a collector for stamped entries (#1777 track 3) that keeps the NEWEST answer by owner epoch then
    /// version: a live value and a tombstone are ordered alike, so a replica that missed a remove loses to one that
    /// holds its tombstone. An empty answer (no entry) never replaces an entry.
    public static QuorumCollector<Option<DHTMessage.KeyValue>> newestEntryCollector(int quorum,
                                                                                    int total,
                                                                                    Promise<Option<DHTMessage.KeyValue>> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity(), QuorumCollector::newestEntry);
    }

    /// Create a quorum collector for Option values that prefers non-empty over empty.
    /// This prevents the race where an empty Option from one replica masks actual data from another.
    ///
    /// @param quorum  minimum successful responses needed
    /// @param total   total responses expected
    /// @param promise promise to resolve when quorum reached or failed
    public static <V> QuorumCollector<Option<V>> optionCollector(int quorum, int total, Promise<Option<V>> promise) {
        return new QuorumCollector<>(quorum, total, promise, UnaryOperator.identity(), QuorumCollector::selectBest);
    }

    /// Record a successful response. Resolves promise when quorum reached.
    @Contract
    public void onSuccess(T value) {
        onSuccess(value, "");
        // after the count is recorded: a waiter released by this reply must read it
        remoteEvidence.succeed(Unit.unit());
    }

    /// Record the coordinator's own slot succeeding: it counts toward the quorum but is not evidence about any remote
    /// replica (#1777 v1882 r6 F10), so it does not resolve [#remoteEvidence].
    @Contract
    public void onLocalSuccess(T value) {
        localSuccesses.incrementAndGet();
        onSuccess(value, "");
    }

    /// Record the coordinator's own slot failing; like [#onLocalSuccess] it is no remote reply.
    @Contract
    public void onLocalFailure(Cause cause) {
        recordFailure(cause);
    }

    /// Successes from remote slots only.
    public int remoteSuccessCount() {
        return successCount.get() - localSuccesses.get();
    }

    /// Resolves with the first EVIDENCE from a remote slot about the writer's fence: a success, or a refusal as stale. Any
    /// other reply — fence unknown, an owner-epoch fence, a dispatch failure — is NOT evidence and does not resolve it
    /// (v1882 r9: releasing on it let a put ack on its own slot while an applied replica had not yet answered). It also
    /// resolves once every slot has replied, so a put whose remotes all answered without evidence is not held to the
    /// timeout. Never resolves while a remote stays silent, so callers bound it with their own timeout.
    public Promise<Unit> remoteEvidence() {
        return remoteEvidence;
    }

    /// Slots refused because the replica had applied a NEWER replication change than the writer's stamp. Authoritative
    /// evidence that the coordinator is behind, including those arriving after the promise settled.
    public int replicationStaleCount() {
        return replicationStale.get();
    }

    /// Record a successful response and who sent it. The first replica whose reply carried a PRESENT value is
    /// remembered by [#valueSource]: when the quorum resolved empty, a value that arrives afterwards is a late
    /// value the read discarded, and this names the replica that held it. Attribution only; the resolved value
    /// is chosen exactly as before.
    @Contract
    public void onSuccess(T value, String source) {
        bestValue.accumulateAndGet(value, this::select);
        if (isLiveValue(value)) {
            valueSource.compareAndSet(null, source);
        }

        var answered = successCount.incrementAndGet();

        if (answered >= quorum) {
            promise.succeed(bestValue.get());
        }

        settleIfAllReplied(answered, failureCount.get());
    }

    /// Record a failed response. Fails promise when quorum becomes arithmetically impossible —
    /// i.e. once the remaining responses that could still succeed (`total - failures`) drop below
    /// the required `quorum`. Equivalent to `failures > total - quorum`. This is the
    /// failure-accrual fast-fail: with `total` live targets and `quorum` required, the
    /// `(quorum)`-th failure (when `total == quorum`) or earlier (when `total > quorum`) aborts
    /// immediately rather than letting the promise stall to the per-op timeout. A slot refused by a
    /// replica still catching up ([DHTError.ReplicaCatchingUp]) makes that failure [DHTError.NotCaughtUp].
    @Contract
    public void onFailure(Cause cause) {
        recordFailure(cause);
        // after the refusal is counted: a waiter released by this reply must see it
        if (cause instanceof DHTError.ReplicaOnNewerReplication) {
            remoteEvidence.succeed(Unit.unit());
        }
    }

    private void recordFailure(Cause cause) {
        if (cause instanceof DHTError.ReplicaCatchingUp) {
            refusals.incrementAndGet();
        }

        if (cause instanceof DHTError.StaleEpochWrite || cause instanceof DHTError.ReplicaFenced) {
            fenced.incrementAndGet();
        }

        if (cause instanceof DHTError.ReplicaOnNewerReplication) {
            replicationStale.incrementAndGet();
        }

        if (cause instanceof DHTError.ReplicaFenceUnknown) {
            fenceUnknown.incrementAndGet();
        }

        var failures = failureCount.incrementAndGet();

        if (total - failures < quorum) {
            promise.fail(quorumFailure());
        }

        settleIfAllReplied(successCount.get(), failures);
    }

    /// A quorum lost to owner-epoch fences is indeterminate, not a definite failure (#1818, the owner's fence
    /// ruling): a replica whose high-water lagged may have applied the write. That takes precedence over a
    /// catching-up refusal (#1777), which only says some replica could not yet answer authoritatively.
    private Cause quorumFailure() {
        if (fenced.get() > 0) {
            return DHTError.writeIndeterminate(quorum, successCount.get(), fenced.get());
        }

        if (replicationStale.get() > 0) {
            return DHTError.replicationChangeStale(quorum, successCount.get(), replicationStale.get());
        }

        if (fenceUnknown.get() > 0) {
            return DHTError.replicationFenceUnknown(quorum, successCount.get(), fenceUnknown.get());
        }

        return refusals.get() > 0
               ? DHTError.notCaughtUp(quorum, successCount.get())
               : DHTError.quorumNotReached(quorum, successCount.get());
    }

    private void settleIfAllReplied(int successes, int failures) {
        if (successes + failures >= total) {
            remoteEvidence.succeed(Unit.unit());
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

    /// Record that a replica this read was waiting on left the ring mid-read and its slot was taken over.
    @Contract
    public void noteDeparted() {
        departed.incrementAndGet();
    }

    /// Replicas that left the ring mid-read while still owing a reply.
    public int departedCount() {
        return departed.get();
    }

    /// Replies recorded so far, including those arriving after the quorum resolved the promise.
    public int successCount() {
        return successCount.get();
    }

    /// Owner-epoch fence refusals recorded so far, including those arriving after the promise settled.
    public int fencedCount() {
        return fenced.get();
    }

    /// `existing` is the accumulator's value, absent until the first answer.
    private T select(T existing, T incoming) {
        return Option.option(existing)
                     .map(held -> selector.apply(held, incoming))
                     .or(incoming);
    }

    /// A present value — a tombstone is present as an entry but is no value, so it names no late-value source.
    private static boolean isLiveValue(Object value) {
        return value instanceof Option<?> option
               && option.filter(present -> !(present instanceof DHTMessage.KeyValue kv && kv.tombstone()))
                        .isPresent();
    }

    private static Option<DHTMessage.KeyValue> newestEntry(Option<DHTMessage.KeyValue> existing,
                                                           Option<DHTMessage.KeyValue> incoming) {
        return existing.fold(() -> incoming,
                             held -> incoming.filter(candidate -> candidate.compareOrder(held) > 0)
                                             .orElse(existing));
    }

    private static <T> T selectBest(T existing, T incoming) {
        // For Option values: prefer present (non-empty) over absent (empty)
        if (existing instanceof Option<?> existingOpt && incoming instanceof Option<?> incomingOpt) {
            return incomingOpt.isPresent() && existingOpt.isEmpty()
                   ? incoming
                   : existing;
        }

        return existing;
    }
}
