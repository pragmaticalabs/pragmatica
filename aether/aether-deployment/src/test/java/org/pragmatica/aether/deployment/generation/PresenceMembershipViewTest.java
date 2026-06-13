// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the presence-derived `PresenceMembershipView` adapter (step 1 of the
/// membership/placement split, `aether/docs/specs/membership-placement-split-spec.md` §8).
class PresenceMembershipViewTest {
    private static final NodeId N1 = NodeId.nodeId("node-1").unwrap();
    private static final NodeId N2 = NodeId.nodeId("node-2").unwrap();
    private static final NodeId N3 = NodeId.nodeId("node-3").unwrap();

    @Test
    void from_presenceSet_coreMemberIdsEqualSuppliedMembers() {
        var members = Set.of(N1, N2, N3);
        var view = PresenceMembershipView.from(() -> members, () -> 3, Set::of);

        assertThat(view.coreMemberIds()).isEqualTo(members);
        assertThat(view.onDutyMemberIds()).isEqualTo(members);
        assertThat(view.healthyOnDutyCount()).isEqualTo(3);
    }

    @Test
    void from_desiredCoreSizeSupplier_returnsSuppliedValue() {
        var view = PresenceMembershipView.from(Set::of, () -> 5, Set::of);

        assertThat(view.desiredCoreSize()).isEqualTo(5);
    }

    @Test
    void from_ctmProvisionedSupplier_returnsSuppliedSet() {
        var ctmProvisioned = Set.of(N1, N2);
        var view = PresenceMembershipView.from(Set::of, () -> 0, () -> ctmProvisioned);

        assertThat(view.ctmProvisionedNodeIds()).isEqualTo(ctmProvisioned);
    }

    @Test
    void coreMemberIds_isImmutableDefensiveCopy() {
        var mutableSource = new HashSet<NodeId>(Set.of(N1, N2));
        var view = PresenceMembershipView.from(() -> mutableSource, () -> 2, Set::of);

        mutableSource.add(N3);

        assertThat(view.coreMemberIds()).containsExactlyInAnyOrder(N1, N2);
        assertThat(view.healthyOnDutyCount()).isEqualTo(2);
    }
}
