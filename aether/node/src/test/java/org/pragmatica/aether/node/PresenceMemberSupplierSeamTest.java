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

/// The #557 wiring pin, at the REAL seam. `AetherNode.presenceMemberSupplier` is what the
/// presence-generation snapshot source's quorum numerator reads; before #644's assembly pass it was
/// an inline lambda inside `assembleNode`, so `PresenceGenerationSnapshotSourceQuorumCompositionTest`
/// (aether-deployment) could only MIRROR the wiring — a rewire of `AetherNode` itself would have
/// left that test green. This class pins the extracted method against a real boot-seeded
/// [MembershipFsm], so swapping the observed projection for the counted one (the exact #557
/// regression) goes red HERE.
class PresenceMemberSupplierSeamTest {
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

    /// THE #557 pin. After the boot seed every configured core is COUNTED, but none is OBSERVED —
    /// the supplier must answer from the observed projection, or boot quorum is a statement about
    /// configuration. Armed by asserting the counted projection genuinely diverges here: if the two
    /// sets ever coincide in this state, this test proves nothing and must fail loudly.
    @Test
    void presenceMemberSupplier_afterBootSeedWithNoObservation_yieldsOnlySelf() {
        var fsm = bootSeededFsm();

        assertThat(fsm.coreCountedMembers()).as("arming: the counted projection must diverge from the observed one"
                                                + " in the boot-seed state, or the assertion below is vacuous")
                                            .containsExactlyInAnyOrder(SELF, PEER_B, PEER_C);

        var supplied = AetherNode.presenceMemberSupplier(() -> fsm, SELF)
                                 .get();

        assertThat(supplied).as("the quorum numerator must read OBSERVED reachability, never the config-seeded count")
                            .containsExactly(SELF);
    }

    /// Reachability evidence flows through: an observed peer joins the supplied set, so the seam is
    /// live and not pinned to an empty answer.
    @Test
    void presenceMemberSupplier_afterSwimHealthyPeer_includesThatPeer() {
        var fsm = bootSeededFsm();

        fsm.onSwimHealthy(PEER_B, 1L);

        assertThat(AetherNode.presenceMemberSupplier(() -> fsm, SELF)
                             .get()).containsExactlyInAnyOrder(SELF, PEER_B);
    }

    /// The pre-FSM-published boot window: the holder is still empty when the supplier is first
    /// consulted. The answer is the empty set — none()-until-converged — never a throw.
    @Test
    void presenceMemberSupplier_beforeFsmPublished_yieldsEmptySet() {
        assertThat(AetherNode.presenceMemberSupplier(() -> null, SELF)
                             .get()).isEmpty();
    }
}
