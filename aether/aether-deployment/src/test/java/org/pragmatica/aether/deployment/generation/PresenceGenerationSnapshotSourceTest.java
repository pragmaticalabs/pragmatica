// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// Verifies the presence-derived `PresenceGenerationSnapshotSource` (step 2 of the
/// membership/placement split, `aether/docs/specs/membership-placement-split-spec.md` §8).
/// The cold-start gate is a one-way quorum latch: sub-quorum presence before the latch flips
/// yields `none()` (legacy peer-count fallback bootstraps formation); reaching quorum flips
/// the latch permanently so a later sub-quorum drop is reported as a low count (dissolve path),
/// not a revert to the fallback. `desiredCoreSize=5` → quorum = 5/2+1 = 3.
class PresenceGenerationSnapshotSourceTest {
    private static final NodeId N1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId N2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId N3 = NodeId.nodeId("node-3").unwrap();
    private static final NodeId N4 = NodeId.nodeId("node-4").unwrap();
    private static final NodeId N5 = NodeId.nodeId("node-5").unwrap();

    @Test
    void currentMembershipView_belowQuorumNotYetLatched_returnsNone() {
        var source = PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource(() -> Set.of(N1), () -> 5, Set::of, () -> 0L);

        assertThat(source.currentMembershipView().isPresent()).isFalse();
    }

    @Test
    void currentMembershipView_reachesQuorum_returnsViewWithMatchingCoreMembers() {
        var members = Set.of(N1, N2, N3);
        var source = PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource(() -> members, () -> 5, Set::of, () -> 0L);

        source.currentMembershipView()
              .onEmpty(() -> fail("expected present membership view at quorum"))
              .onPresent(view -> assertThat(view.coreMemberIds()).isEqualTo(members));
    }

    @Test
    void currentMembershipView_afterLatchingDropsBelowQuorum_stillSomeWithLowCount() {
        var members = new AtomicReference<Set<NodeId>>(Set.of(N1, N2, N3, N4, N5));
        var source = PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource(members::get, () -> 5, Set::of, () -> 0L);

        // First call at quorum flips the one-way latch.
        source.currentMembershipView()
              .onEmpty(() -> fail("expected present membership view at quorum"));

        // Drop below quorum (2 < 3); latch must keep the presence view governing, reporting the low count.
        members.set(Set.of(N1, N2));
        source.currentMembershipView()
              .onEmpty(() -> fail("expected present view after latch despite sub-quorum drop"))
              .onPresent(view -> assertThat(view.coreMemberIds()).containsExactlyInAnyOrder(N1, N2))
              .onPresent(view -> assertThat(view.healthyOnDutyCount()).isEqualTo(2));
    }

    @Test
    void currentMembershipView_atQuorum_carriesDesiredCoreSizeAndCtmProvisioned() {
        var members = Set.of(N1, N2, N3);
        var ctmProvisioned = Set.of(N4);
        var source = PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource(() -> members, () -> 5, () -> ctmProvisioned, () -> 0L);

        source.currentMembershipView()
              .onEmpty(() -> fail("expected present membership view at quorum"))
              .onPresent(view -> assertThat(view.desiredCoreSize()).isEqualTo(5))
              .onPresent(view -> assertThat(view.ctmProvisionedNodeIds()).isEqualTo(ctmProvisioned));
    }

    @Test
    void observedRabiaTerm_returnsSuppliedTerm() {
        var source = PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource(Set::of, () -> 0, Set::of, () -> 42L);

        assertThat(source.observedRabiaTerm()).isEqualTo(42L);
    }
}
