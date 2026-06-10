// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;


/// Readiness-broadcast (failover-readability): a follower's cache of the LEADER's authoritative
/// `NodeId → NodeReportedState` view, delivered piggy-backed on `ClusterSyncPing.readinessView`.
///
/// The cached view is an immutable [Snapshot] swapped atomically on every accepted leader ping, so
/// readers never observe a half-updated map. Validity is STATELESS — there is no leader-change
/// observer; freshness is recomputed on each read as `cachedLeader == currentLeader AND
/// (nowTick - tick) < ttl`. A leader change therefore invalidates the cache automatically (the
/// stored `cachedLeader` no longer matches), and a stale leader's view ages out via the TTL.
///
/// Empty views and views from non-leaders are never stored (see [maybeStore]); only a non-empty
/// view stamped by the node the follower currently believes is leader is cached.
final class FollowerReadinessCache {
    /// TTL expressed as a multiple of the ping interval — a cached view survives this many missed
    /// leader pings before it is considered stale. Three intervals tolerates transient
    /// ping loss / scheduling jitter without serving a long-dead view.
    static final int TTL_PING_INTERVALS = 3;

    private final LeaderManager leaderManager;
    private final LongSupplier nowNanos;
    private final long ttlNanos;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    /// Immutable cache snapshot. `view` is already parsed into the enum so reads are allocation-free
    /// beyond the defensive copy at construction. `tick` is the `nowNanos` reading at store time.
    record Snapshot(Option<NodeId> cachedLeader, long term, Map<NodeId, NodeReportedState> view, long tick) {
        static Snapshot empty() {
            return new Snapshot(Option.none(), 0L, Map.of(), 0L);
        }
    }

    FollowerReadinessCache(LeaderManager leaderManager, LongSupplier nowNanos, long ttlNanos) {
        this.leaderManager = leaderManager;
        this.nowNanos = nowNanos;
        this.ttlNanos = ttlNanos;
    }

    /// Store the leader-broadcast view IFF `sender` is the node this follower currently believes is
    /// leader and `wireView` is non-empty. Empty views and views from non-leaders are ignored (a
    /// no-op leaves the prior snapshot intact). Idempotent and lock-free.
    @Contract
    void maybeStore(NodeId sender, long term, Map<NodeId, String> wireView) {
        if (wireView.isEmpty() || !leaderManager.leader().map(sender::equals).or(false)) {
            return;
        }

        snapshot.set(new Snapshot(Option.some(sender), term, parseView(wireView), nowNanos.getAsLong()));
    }

    /// FRESH iff the cached leader matches the CURRENT leader and the entry is within TTL. Recomputed
    /// per call — no observer, no background invalidation.
    boolean isFresh() {
        return freshSnapshot().isPresent();
    }

    /// The cached view when [isFresh]; otherwise the empty map. Used by `reportedStates()` as the
    /// follower fallback.
    Map<NodeId, NodeReportedState> freshView() {
        return freshSnapshot().map(Snapshot::view)
                            .or(Map.of());
    }

    private Option<Snapshot> freshSnapshot() {
        var current = snapshot.get();

        return current.cachedLeader()
                      .filter(this::matchesCurrentLeader)
                      .filter(_ -> withinTtl(current.tick()))
                      .map(_ -> current);
    }

    private boolean matchesCurrentLeader(NodeId cachedLeader) {
        return leaderManager.leader()
                            .map(cachedLeader::equals)
                            .or(false);
    }

    private boolean withinTtl(long tick) {
        return (nowNanos.getAsLong() - tick) < ttlNanos;
    }

    private static Map<NodeId, NodeReportedState> parseView(Map<NodeId, String> wireView) {
        return wireView.entrySet()
                       .stream()
                       .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                             e -> NodeReportedState.fromWire(e.getValue())));
    }
}
