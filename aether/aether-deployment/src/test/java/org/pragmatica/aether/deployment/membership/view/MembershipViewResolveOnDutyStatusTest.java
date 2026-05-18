// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberStatus;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// Step 6 — table-driven coverage for `MembershipView.resolveOnDutyStatus`.
///
/// Each row drives the resolution of a peer whose KV lifecycle entry is `ON_DUTY`. The 5
/// rows correspond to the documented branches of `resolveOnDutyStatus` after the
/// periodic-emission refactor: SWIM fast path, snapshot-REACHABLE promotion, snapshot
/// UNREACHABLE / UNKNOWN downgrades, and cold-start `Option.none()` fallback.
///
/// **Why a new file.** [MembershipViewTest] covers the pure two-input (SWIM + KV)
/// projection via the `membershipView(...)` factory, which hard-wires the snapshot supplier
/// to `Option::none`. [MembershipViewQuorumTest] covers the strict-vs-bootstrap factory
/// split. Neither exercises the snapshot-aware branches of `resolveOnDutyStatus` directly.
/// This file fills the gap and documents the post-Step-1 UNKNOWN semantic.
class MembershipViewResolveOnDutyStatusTest {
    private static final NodeId PEER = NodeId.nodeId("node-peer").unwrap();
    private static final long T0 = 100_000L;

    private record Case(String label,
                        SwimHealth swimHealth,
                        Option<AggregatedReachabilitySnapshot> snapshot,
                        MemberStatus expected) {}

    @Test
    @DisplayName("resolveOnDutyStatus — 5 documented branches (post-periodic-emission)")
    void resolveOnDutyStatus_allBranches() {
        var cases = List.of(
            new Case("SWIM HEALTHY + quorate → ON_DUTY (fast path; snapshot ignored)",
                     SwimHealth.HEALTHY,
                     Option.none(),
                     MemberStatus.ON_DUTY),
            new Case("SWIM FAULTY + snapshot REACHABLE → ON_DUTY (snapshot promotes)",
                     SwimHealth.FAULTY,
                     snapshotOf(ReachabilityKind.REACHABLE),
                     MemberStatus.ON_DUTY),
            new Case("SWIM FAULTY + snapshot UNREACHABLE → UNTRACKED (transport-honest downgrade)",
                     SwimHealth.FAULTY,
                     snapshotOf(ReachabilityKind.UNREACHABLE),
                     MemberStatus.UNTRACKED),
            new Case("SWIM FAULTY + snapshot UNKNOWN → UNTRACKED (genuinely isolated peer)",
                     SwimHealth.FAULTY,
                     snapshotOf(ReachabilityKind.UNKNOWN),
                     MemberStatus.UNTRACKED),
            new Case("SWIM FAULTY + snapshot Option.none() → UNTRACKED (cold-start fallback)",
                     SwimHealth.FAULTY,
                     Option.none(),
                     MemberStatus.UNTRACKED));

        for (var row : cases) {
            var view = viewFrom(row.swimHealth(), row.snapshot());

            assertThat(view.statusOf(PEER))
                .as(row.label())
                .isEqualTo(row.expected());
        }
    }

    @Test
    @DisplayName("SWIM HEALTHY fast path ignores snapshot UNREACHABLE (regression)")
    void resolveOnDutyStatus_swimHealthyOverridesSnapshotUnreachable() {
        // Regression guard: SWIM HEALTHY must short-circuit before snapshot consultation.
        // If a future refactor reversed the ordering, a transient snapshot UNREACHABLE
        // (e.g. mid-flap) would downgrade a locally-healthy peer — exactly the routing
        // instability the SWIM fast path exists to prevent.
        var view = viewFrom(SwimHealth.HEALTHY, snapshotOf(ReachabilityKind.UNREACHABLE));

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.ON_DUTY);
    }

    @Test
    @DisplayName("Peer missing from snapshot states() map → UNTRACKED")
    void resolveOnDutyStatus_peerAbsentFromSnapshotMap() {
        var otherPeer = NodeId.nodeId("node-other").unwrap();
        var snapshot = Option.some(new AggregatedReachabilitySnapshot(
            T0,
            Map.of(otherPeer, new ReachabilityState(otherPeer, ReachabilityKind.REACHABLE, 3, T0))));

        var view = viewFrom(SwimHealth.FAULTY, snapshot);

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.UNTRACKED);
    }

    @Test
    @DisplayName("Non-quorate forces UNTRACKED even with snapshot REACHABLE")
    void resolveOnDutyStatus_nonQuorateForcesUntracked() {
        var view = strictViewFrom(false, SwimHealth.HEALTHY, snapshotOf(ReachabilityKind.REACHABLE));

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.UNTRACKED);
    }

    private static Option<AggregatedReachabilitySnapshot> snapshotOf(ReachabilityKind kind) {
        return Option.some(new AggregatedReachabilitySnapshot(
            T0,
            Map.of(PEER, new ReachabilityState(PEER, kind, 3, T0))));
    }

    private static MembershipView viewFrom(SwimHealth swimHealth,
                                            Option<AggregatedReachabilitySnapshot> snapshot) {
        return strictViewFrom(true, swimHealth, snapshot);
    }

    private static MembershipView strictViewFrom(boolean quorate,
                                                  SwimHealth swimHealth,
                                                  Option<AggregatedReachabilitySnapshot> snapshot) {
        var healthSnapshot = HealthSnapshot.healthSnapshot(Map.of(PEER, swimHealth));
        var lifecycleEntries = lifecycleEntriesFor(PEER, NodeLifecycleState.ON_DUTY);
        Supplier<Option<AggregatedReachabilitySnapshot>> snapshotSupplier = () -> snapshot;
        return MembershipView.strict(() -> Option.some(healthSnapshot),
                                      consumer -> applyEntries(lifecycleEntries, consumer),
                                      () -> quorate,
                                      snapshotSupplier);
    }

    private static Map<NodeLifecycleKey, NodeLifecycleValue> lifecycleEntriesFor(NodeId peer,
                                                                                  NodeLifecycleState state) {
        var entries = new LinkedHashMap<NodeLifecycleKey, NodeLifecycleValue>();
        entries.put(NodeLifecycleKey.nodeLifecycleKey(peer),
                    NodeLifecycleValue.nodeLifecycleValue(state, T0));
        return entries;
    }

    private static void applyEntries(Map<NodeLifecycleKey, NodeLifecycleValue> entries,
                                      BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer) {
        entries.forEach(consumer);
    }
}
