// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.view.MembershipView.MemberStatus;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;


/// RC1 membership-v2 step 1 — table-driven coverage for `MembershipView`'s SWIM-derived
/// status resolution.
///
/// The KV `NodeLifecycleKey`-override path is dropped: the view is derived purely from SWIM.
/// A SWIM-HEALTHY peer resolves to `ON_DUTY` (when quorate) via the fast path that
/// short-circuits before snapshot consultation; every non-HEALTHY SWIM observation resolves
/// to `UNTRACKED` outright — there is no KV claim left for the aggregated reachability
/// snapshot to confirm or demote. The KV map fed to the view is ignored (the factory still
/// accepts the reader for source compatibility).
class MembershipViewResolveOnDutyStatusTest {
    private static final NodeId PEER = NodeId.nodeId("node-peer").unwrap();
    private static final long T0 = 100_000L;

    private record Case(String label,
                        SwimHealth swimHealth,
                        Option<AggregatedReachabilitySnapshot> snapshot,
                        MemberStatus expected) {}

    @Test
    @DisplayName("status resolution — SWIM-derived branches (HEALTHY ⇒ ON_DUTY fast path; else UNTRACKED)")
    void resolveOnDutyStatus_allBranches() {
        var cases = List.of(
            new Case("SWIM HEALTHY + quorate → ON_DUTY (fast path; snapshot ignored)",
                     SwimHealth.HEALTHY,
                     Option.none(),
                     MemberStatus.ON_DUTY),
            new Case("SWIM FAULTY → UNTRACKED (no KV claim to confirm; snapshot REACHABLE does not promote)",
                     SwimHealth.FAULTY,
                     snapshotOf(ReachabilityKind.REACHABLE),
                     MemberStatus.UNTRACKED),
            new Case("SWIM HEALTHY + snapshot UNREACHABLE → ON_DUTY (HEALTHY fast path short-circuits snapshot)",
                     SwimHealth.HEALTHY,
                     snapshotOf(ReachabilityKind.UNREACHABLE),
                     MemberStatus.ON_DUTY),
            new Case("SWIM HEALTHY + snapshot UNKNOWN → ON_DUTY (fast path)",
                     SwimHealth.HEALTHY,
                     snapshotOf(ReachabilityKind.UNKNOWN),
                     MemberStatus.ON_DUTY),
            new Case("SWIM HEALTHY + snapshot Option.none() → ON_DUTY (cold-boot; SWIM is authoritative)",
                     SwimHealth.HEALTHY,
                     Option.none(),
                     MemberStatus.ON_DUTY));

        for (var row : cases) {
            var view = viewFrom(row.swimHealth(), row.snapshot());

            assertThat(view.statusOf(PEER))
                .as(row.label())
                .isEqualTo(row.expected());
        }
    }

    @Test
    @DisplayName("SWIM HEALTHY + snapshot REACHABLE → ON_DUTY (snapshot confirms, never strands)")
    void resolveOnDutyStatus_swimHealthySnapshotReachable() {
        var view = viewFrom(SwimHealth.HEALTHY, snapshotOf(ReachabilityKind.REACHABLE));

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.ON_DUTY);
    }

    @Test
    @DisplayName("Peer missing from snapshot states() map + SWIM HEALTHY → ON_DUTY (non-information)")
    void resolveOnDutyStatus_peerAbsentFromSnapshotMap() {
        // A HEALTHY peer the leader has not yet observed (absent from states()) is
        // non-information, not negative evidence — the SWIM-derived ON_DUTY holds until an
        // explicit UNREACHABLE quorum demotes it.
        var otherPeer = NodeId.nodeId("node-other").unwrap();
        var snapshot = Option.some(new AggregatedReachabilitySnapshot(
            T0,
            Map.of(otherPeer, new ReachabilityState(otherPeer, ReachabilityKind.REACHABLE, 3, T0))));

        var view = viewFrom(SwimHealth.HEALTHY, snapshot);

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.ON_DUTY);
    }

    @Test
    @DisplayName("Non-quorate forces UNTRACKED even with snapshot REACHABLE")
    void resolveOnDutyStatus_nonQuorateForcesUntracked() {
        var view = strictViewFrom(false, SwimHealth.HEALTHY, snapshotOf(ReachabilityKind.REACHABLE));

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.UNTRACKED);
    }

    @Test
    @DisplayName("Non-HEALTHY SWIM → UNTRACKED regardless of quorum (no KV claim to defer to)")
    void resolveOnDutyStatus_nonHealthySwimUntracked() {
        var view = strictViewFrom(true, SwimHealth.UNKNOWN, Option.none());

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.UNTRACKED);
        assertThat(view.onDutyPeers()).isEmpty();
    }

    @Test
    @DisplayName("SWIM HEALTHY fast path ignores snapshot UNREACHABLE (regression)")
    void resolveOnDutyStatus_swimHealthyOverridesSnapshotUnreachable() {
        // SWIM HEALTHY must short-circuit before snapshot consultation. A transient snapshot
        // UNREACHABLE (e.g. mid-flap) must not downgrade a locally-healthy peer — the routing
        // instability the SWIM fast path exists to prevent.
        var view = strictViewFrom(true, SwimHealth.HEALTHY, snapshotOf(ReachabilityKind.UNREACHABLE));

        assertThat(view.statusOf(PEER)).isEqualTo(MemberStatus.ON_DUTY);
        assertThat(view.onDutyPeers()).containsExactly(PEER);
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
        Supplier<Option<AggregatedReachabilitySnapshot>> snapshotSupplier = () -> snapshot;
        return MembershipView.strict(() -> Option.some(healthSnapshot),
                                      () -> quorate,
                                      snapshotSupplier);
    }
}
