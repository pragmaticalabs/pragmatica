// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;

import java.util.Map;


/// Leader-derived cluster-canonical reachability snapshot, broadcast to followers
/// in each outbound `ClusterSyncPing`. Followers cache the most-recent snapshot for
/// warm-takeover when they become leader. `/api/status` consumes this in place of
/// per-reader local QUIC state, eliminating reader-variance on `coreCount`.
///
/// Single-writer rule: the snapshot is derived state, NOT authoritative. KV
/// (`NodeLifecycleKey`, written by `HealthReconciler`) remains the source of
/// truth for cluster membership. The snapshot only describes "currently
/// transport-reachable peers" as observed across N nodes with TTL + quorum.
///
/// See `aether/docs/specs/reachability-aggregator-spec.md`.
///
/// @param generatedAtMs wall-clock millis on the leader when the snapshot was built
/// @param states        per-target reachability state, keyed by target NodeId
@Codec public record AggregatedReachabilitySnapshot(long generatedAtMs,
                                                    Map<NodeId, ReachabilityState> states) {
    public AggregatedReachabilitySnapshot {
        states = states == null ? Map.of() : Map.copyOf(states);
    }

    /// Per-target derived reachability with provenance for diagnostics.
    ///
    /// @param target            node the state describes
    /// @param kind              REACHABLE / UNREACHABLE / UNKNOWN
    /// @param observerCount     how many distinct observers contributed within TTL
    /// @param lastObservedAtMs  newest observation timestamp among the observers
    @Codec public record ReachabilityState(NodeId target,
                                           ReachabilityKind kind,
                                           int observerCount,
                                           long lastObservedAtMs) {}

    @Codec public enum ReachabilityKind {REACHABLE, UNREACHABLE, UNKNOWN}

    public boolean isReachable(NodeId peer) {
        var state = states.get(peer);
        return state != null && state.kind() == ReachabilityKind.REACHABLE;
    }
}
