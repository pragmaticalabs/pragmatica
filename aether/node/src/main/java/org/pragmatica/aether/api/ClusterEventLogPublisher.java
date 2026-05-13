// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterEventLogKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// RC1 Step 1 — per-node publisher for the cluster-scoped replicated event log.
///
/// Each publish call:
/// 1. Token-bucket rate-cap check (drops with `eventsDropped` metric on saturation; does
///    NOT back-pressure the FSM).
/// 2. Stamps `(epoch, seq)` from the local seq counter and the current Rabia term as epoch.
/// 3. Stamps HLC `at` for human-readable diagnostics.
/// 4. Submits `KVCommand.Put<ClusterEventLogKey, ClusterEventValue>` via the cluster
///    command applier — Rabia replicates to all nodes; commit order is the canonical order.
///
/// Single-writer-per-node: the `seq` counter is local. The originator `nodeId` is the
/// middle component of `ClusterEventLogKey(epoch, nodeId, seq)` so each node owns a disjoint
/// sub-keyspace and concurrent writes from different nodes cannot collide on `(epoch, seq)`.
/// Cross-node total order is established by Rabia commit order at the materialised-view
/// subscriber, not by the seq value itself.
///
/// **Concurrency contract.** Single instance per node. `publish` is thread-safe (token bucket
/// + seq are atomic).
public final class ClusterEventLogPublisher {
    private static final Logger log = LoggerFactory.getLogger(ClusterEventLogPublisher.class);

    /// Default sustained rate: 50 events/sec (per-node, leaves headroom under the 100/sec
    /// snapshot-blow-up ceiling from spec §3.6).
    public static final int DEFAULT_TOKENS_PER_SEC = 50;

    /// Default burst size: 1s of sustained rate (smooths bursty leader-takeover replay).
    public static final int DEFAULT_BURST = 50;

    private final NodeId selfId;
    private final HlcClock hlcClock;
    private final LongSupplier epochSupplier;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier;
    private final AtomicLong seq = new AtomicLong();
    private final TokenBucket bucket;
    private final AtomicLong droppedCount = new AtomicLong();

    private ClusterEventLogPublisher(NodeId selfId,
                                     HlcClock hlcClock,
                                     LongSupplier epochSupplier,
                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                     int tokensPerSec,
                                     int burst,
                                     LongSupplier nanoClock) {
        this.selfId = selfId;
        this.hlcClock = hlcClock;
        this.epochSupplier = epochSupplier;
        this.applier = applier;
        this.bucket = new TokenBucket(tokensPerSec, burst, nanoClock);
    }

    public static ClusterEventLogPublisher clusterEventLogPublisher(NodeId selfId,
                                                                     HlcClock hlcClock,
                                                                     LongSupplier epochSupplier,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return new ClusterEventLogPublisher(selfId,
                                            hlcClock,
                                            epochSupplier,
                                            applier,
                                            DEFAULT_TOKENS_PER_SEC,
                                            DEFAULT_BURST,
                                            System::nanoTime);
    }

    public static ClusterEventLogPublisher clusterEventLogPublisher(NodeId selfId,
                                                                     HlcClock hlcClock,
                                                                     LongSupplier epochSupplier,
                                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                                                     int tokensPerSec,
                                                                     int burst,
                                                                     LongSupplier nanoClock) {
        return new ClusterEventLogPublisher(selfId, hlcClock, epochSupplier, applier, tokensPerSec, burst, nanoClock);
    }

    /// Returns the total count of events dropped due to rate-cap saturation (monotonic).
    /// Exposed for tests + future metrics export.
    public long droppedCount() {
        return droppedCount.get();
    }

    /// Reset the seq counter to 0. Called by `ClusterEventLogSweeper` on epoch advance so
    /// `(epoch, seq)` pairs stay tight within an epoch window.
    @Contract public void resetSeqForNewEpoch() {
        seq.set(0L);
    }

    /// Submit one event. Returns:
    /// - `Promise<Unit>` resolved on Rabia commit (or rejected if applier fails),
    /// - immediately-resolved success Unit if dropped by rate-cap (sentinel — caller does
    ///   NOT distinguish drop from successful publish; metric counts drops separately).
    public Promise<Unit> publish(ClusterEventValue.EventType type,
                                 ClusterEventValue.Severity severity,
                                 String message,
                                 Map<String, String> metadata) {
        if (!bucket.tryAcquire()) {
            var dropped = droppedCount.incrementAndGet();
            if (dropped == 1 || (dropped % 100) == 0) {
                log.warn("ClusterEventLogPublisher: rate-cap exceeded, event dropped (total dropped={}, type={})",
                         dropped, type);
            }
            return Promise.success(Unit.unit());
        }
        var at = hlcClock.now();
        var epoch = epochSupplier.getAsLong();
        var nextSeq = seq.getAndIncrement();
        var key = ClusterEventLogKey.clusterEventLogKey(epoch, selfId, nextSeq);
        var value = ClusterEventValue.clusterEventValue(at, type, severity, selfId.id(), message, metadata);
        return applier.apply(List.of(asAetherCommand(key, value)))
                      .mapToUnit()
                      .onFailure(cause -> log.warn("ClusterEventLogPublisher: apply failed for type={} epoch={} nodeId={} seq={}: {}",
                                                    type, epoch, selfId.id(), nextSeq, cause.message()));
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> asAetherCommand(ClusterEventLogKey key,
                                                                                        ClusterEventValue value) {
        return (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    /// Lock-free token bucket. `tokens` holds an integer scaled by `NANOS_PER_SEC` so
    /// per-nanosecond refill arithmetic stays in long-domain without floating-point drift.
    private static final class TokenBucket {
        private static final long NANOS_PER_SEC = 1_000_000_000L;

        private final long capacity;
        private final long refillPerNano;
        private final LongSupplier nanoClock;
        private final java.util.concurrent.atomic.AtomicLong scaledTokens;
        private final java.util.concurrent.atomic.AtomicLong lastRefillNanos;

        TokenBucket(int tokensPerSec, int burst, LongSupplier nanoClock) {
            this.capacity = (long) burst * NANOS_PER_SEC;
            this.refillPerNano = tokensPerSec;
            this.nanoClock = nanoClock;
            this.scaledTokens = new java.util.concurrent.atomic.AtomicLong(capacity);
            this.lastRefillNanos = new java.util.concurrent.atomic.AtomicLong(nanoClock.getAsLong());
        }

        boolean tryAcquire() {
            refill();
            while (true) {
                var current = scaledTokens.get();
                if (current < NANOS_PER_SEC) {return false;}
                if (scaledTokens.compareAndSet(current, current - NANOS_PER_SEC)) {return true;}
            }
        }

        private void refill() {
            var now = nanoClock.getAsLong();
            var last = lastRefillNanos.getAndSet(now);
            var elapsed = now - last;
            if (elapsed <= 0L) {return;}
            var added = elapsed * refillPerNano;
            scaledTokens.accumulateAndGet(added, (cur, add) -> Math.min(capacity, cur + add));
        }
    }
}
