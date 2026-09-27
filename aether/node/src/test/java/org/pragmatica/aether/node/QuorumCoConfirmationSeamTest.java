// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.swim.SwimHealth;

import static org.assertj.core.api.Assertions.assertThat;

/// #1560 wiring pin for `AetherNode.buildQuorumCoConfirmation`, the snapshot the quorum-loss self-fence
/// consults before it drains. #1390 made its counted set the installed voter set, which is health-blind:
/// every peer the FSM had already demoted (DEPARTING / DEAD) came back as a "stuck" member, and an isolated
/// core, whose SWIM still read those peers SUSPECTED, suppressed its own fence with an effective quorum of
/// 5 of 5. The counted set is the FSM's counted CORE members narrowed to the voters.
class QuorumCoConfirmationSeamTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");
    private static final NodeId PEER_D = new NodeId("node-d");
    private static final NodeId PEER_E = new NodeId("node-e");
    private static final Set<NodeId> VOTERS = Set.of(SELF, PEER_B, PEER_C, PEER_D, PEER_E);
    private static final int THRESHOLD = 3;

    /// The isolated-core shape at the firing instant: three peers already demoted by the FSM, one still
    /// SUSPECT, and SWIM reporting every peer SUSPECTED (Lifeguard stretches suspicion on a node whose
    /// probes all fail). Effective quorum must be self + the one SUSPECT peer = 2, below threshold.
    @Test
    void buildQuorumCoConfirmation_fsmDemotedPeersSwimSuspected_notCountedAsStuck() {
        var fsm = isolatedCoreFsm();

        assertThat(fsm.coreCountedMembers()).as("arming: the FSM has demoted B, C and D out of its counted set,"
                                                + " while the voter set still names all five")
                                            .containsExactlyInAnyOrder(SELF, PEER_E);

        var snapshot = AetherNode.buildQuorumCoConfirmation(fsm, _ -> SwimHealth.SUSPECTED, VOTERS);

        assertThat(snapshot.countedCount()).isEqualTo(2);
        assertThat(snapshot.swimAliveStuckMembers()).containsExactly(PEER_E);
        assertThat(snapshot.effectiveQuorumCount()).isEqualTo(2);
        assertThat(snapshot.suppresses(THRESHOLD)).as("an isolated core must not suppress its own fence").isFalse();
    }

    /// The voter narrowing still holds: a counted core that is not an installed voter never lifts the
    /// effective count.
    @Test
    void buildQuorumCoConfirmation_countedCoreOutsideVoters_notCounted() {
        var fsm = isolatedCoreFsm();

        var snapshot = AetherNode.buildQuorumCoConfirmation(fsm, _ -> SwimHealth.HEALTHY, Set.of(SELF, PEER_B, PEER_C));

        assertThat(snapshot.countedCount()).isEqualTo(1);
        assertThat(snapshot.swimAliveStuckMembers()).isEmpty();
    }

    private static MembershipFsm isolatedCoreFsm() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              Long.MAX_VALUE,
                                              TimeSpan.timeSpan(40).millis());

        fsm.seed(VOTERS);
        fsm.onDrainRequested(PEER_B);
        fsm.onDrainRequested(PEER_C);
        fsm.onDrainRequested(PEER_D);
        fsm.onSwimSuspect(PEER_E, 1L);

        return fsm;
    }
}
