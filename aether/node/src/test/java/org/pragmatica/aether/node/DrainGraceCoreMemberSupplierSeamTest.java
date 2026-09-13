// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Set;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The #1050 wiring pin, at the REAL seam. `AetherNode.drainGraceCoreMemberSupplier` is what the CTM's
/// drain-grace backstop re-reads before reaping a surplus-drained node. It must answer from the COUNTED
/// projection — the `LeaderReconciler`'s own drain-decision input — so the drain and its reap are decided
/// by one authority. Pinned against a real boot-seeded [MembershipFsm], where the counted and observed
/// projections genuinely diverge. The call site passing this seam into the CTM factory is one argument
/// line in `assembleNode` that no test reaches.
class DrainGraceCoreMemberSupplierSeamTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");

    private static final long NO_HINT_DECAY = Long.MAX_VALUE;
    private static final TimeSpan BACKSTOP = TimeSpan.timeSpan(40).millis();

    private static MembershipFsm bootSeededFsm() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              BACKSTOP);

        fsm.seed(Set.of(SELF, PEER_B, PEER_C));

        return fsm;
    }

    /// After the boot seed every configured core is COUNTED but only self is OBSERVED. Armed by asserting
    /// the two projections diverge here: if they ever coincide in this state, the assertion below cannot
    /// tell them apart and must fail loudly instead of passing vacuously.
    @Test
    void drainGraceCoreMemberSupplier_afterBootSeed_readsTheCountedProjection() {
        var fsm = bootSeededFsm();

        assertThat(fsm.coreObservedMembers(SELF)).as("arming: the observed projection must diverge from the counted one"
                                                     + " in the boot-seed state, or the assertion below is vacuous")
                                                 .containsExactly(SELF);

        assertThat(AetherNode.drainGraceCoreMemberSupplier(() -> fsm)
                             .get()).as("the drain-grace backstop must read the reconciler's counted set")
                                    .containsExactlyInAnyOrder(SELF, PEER_B, PEER_C);
    }

    /// A drain request moves its target to DEPARTING, which stops it counting, so the backstop's view no
    /// longer includes the node it is about to reap. This is what lets a stable surplus trim see spare capacity.
    @Test
    void drainGraceCoreMemberSupplier_afterDrainRequested_excludesTheDepartingTarget() {
        var fsm = bootSeededFsm();

        fsm.onDrainRequested(PEER_C);

        assertThat(AetherNode.drainGraceCoreMemberSupplier(() -> fsm)
                             .get()).containsExactlyInAnyOrder(SELF, PEER_B);
    }

    /// The pre-FSM-published boot window: the empty answer is read as not-quorum-safe, so a reap is refused
    /// (fail-closed) rather than thrown.
    @Test
    void drainGraceCoreMemberSupplier_beforeFsmPublished_yieldsEmptySet() {
        assertThat(AetherNode.drainGraceCoreMemberSupplier(() -> null)
                             .get()).isEmpty();
    }
}
