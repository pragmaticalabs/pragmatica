// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.deployment.generation.PresenceGenerationSnapshotSource;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2306: through the REAL production wiring chain (a real `MembershipFsm`, `AetherNode.presenceMemberSupplier` with the installed
/// voters, `PresenceGenerationSnapshotSource`, `MembershipView`), the view `TopologyObserver` reads never lists or counts a core member
/// that is observed healthy but is not an installed voter. `TopologyObserver` also counts installed voters only (defence in depth),
/// so that its quorum edge does not rely on its feed being pre-filtered; this test pins the filter in the feed, so removing it is not
/// silently covered by the edge alone.
class MembershipViewFeedExcludesNonVotersTest {
    private static final NodeId SELF = new NodeId("v1");
    private static final Set<NodeId> VOTERS = Set.of(SELF, new NodeId("v2"), new NodeId("v3"), new NodeId("v4"), new NodeId("v5"));
    private static final NodeId OBS = new NodeId("obs");

    @Test
    void viewNeverListsOrCountsAnObservedHealthyNonVoter() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis, Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
        var configured = new java.util.HashSet<>(VOTERS);
        configured.add(OBS);
        fsm.seed(configured);
        configured.forEach(n -> { if (!n.equals(SELF)) fsm.onSwimHealthy(n, 1L); });
        assertThat(fsm.coreObservedMembers(SELF)).as("control: the FSM observes the observer as a healthy core member").contains(OBS);

        var installed = new AtomicReference<Set<NodeId>>(VOTERS);
        var source = PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource(
            AetherNode.presenceMemberSupplier(() -> fsm, SELF, installed::get), () -> installed.get().size(), Set::of, () -> 0L);
        var view = source.currentMembershipView().unwrap();

        assertThat(view.onDutyMemberIds()).doesNotContain(OBS).hasSize(5);
        assertThat(view.coreMemberIds()).doesNotContain(OBS);
        assertThat(view.healthyOnDutyCount()).isEqualTo(5);
    }
}
