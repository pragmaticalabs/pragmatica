// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #2063 — an operator drain entry ends with the departure it commanded. Before, only the drains the topology manager
/// started itself were ever cleared, so a drained node that rejoined under the same config-derived id was drained again.
class AetherNodeOperatorDrainClearTest {
    private static final NodeId DRAINED = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    private static MembershipTransitionRecord transition(NodeId id, String from, String to) {
        return new MembershipTransitionRecord(id, from, to, "test", 1L, "core", 0L);
    }

    @Test
    void clearOperatorDrainOnDeath_clearsTheEntry_whenTheDrainedNodeReachesDead() {
        var registry = DrainCommandRegistry.drainCommandRegistry();

        registry.requestDrain(DRAINED);
        AetherNode.clearOperatorDrainOnDeath(registry, transition(DRAINED, "Departing", "Dead"));

        assertThat(registry.isDrainRequested(DRAINED)).isFalse();
        assertThat(registry.drainTargets()).isEmpty();
    }

    @Test
    void clearOperatorDrainOnDeath_keepsTheEntry_whileTheNodeIsOnlyDeparting() {
        var registry = DrainCommandRegistry.drainCommandRegistry();

        registry.requestDrain(DRAINED);
        AetherNode.clearOperatorDrainOnDeath(registry, transition(DRAINED, "Member", "Departing"));

        assertThat(registry.isDrainRequested(DRAINED)).isTrue();
    }

    @Test
    void clearOperatorDrainOnDeath_clearsOnlyTheDeadNode() {
        var registry = DrainCommandRegistry.drainCommandRegistry();

        registry.requestDrain(DRAINED);
        registry.requestDrain(OTHER);
        AetherNode.clearOperatorDrainOnDeath(registry, transition(DRAINED, "Departing", "Dead"));

        assertThat(registry.drainTargets()).containsExactly(OTHER);
    }

    /// The transition listener is assembled inside `AetherNode.assemble`, which no unit test constructs; pin that the hook is
    /// called there with the node's own registry (same stripped-source approach as the death-edge wiring pins).
    @Test
    void transitionListener_callsTheClear_withTheNodesRegistry() {
        assertThat(NodeDepartureWiringTest.assemblyCode()).contains("clearOperatorDrainOnDeath(drainCommandRegistry,record);");
    }

    /// The tests above hand-build transition records, so they cannot see which edges a real FSM emits. These drive a
    /// REAL [`MembershipFsm`] with the hook wired the way `AetherNode`'s `onTransition` listener wires it, and enter the
    /// drain through `AetherNode.requestDrainThroughFsm`, the production sink (registry first, then the FSM).
    @Nested
    class DrivenThroughTheRealFsm {
        private static final TimeSpan FIRING_TIMEOUT = TimeSpan.timeSpan(80).millis();
        private static final TimeSpan LONG_TIMEOUT = TimeSpan.timeSpan(30).seconds();

        private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();

        private MembershipFsm fsmWithDepartureTimeout(TimeSpan departureTimeout) {
            var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                                  System::currentTimeMillis,
                                                  Long.MAX_VALUE,
                                                  TimeSpan.timeSpan(40).millis(),
                                                  departureTimeout,
                                                  LONG_TIMEOUT);

            fsm.onTransition(record -> AetherNode.clearOperatorDrainOnDeath(registry, record));
            return fsm;
        }

        private void drain(MembershipFsm fsm, NodeId target) {
            AetherNode.requestDrainThroughFsm(registry, new AtomicReference<>(fsm), target);
        }

        private static void promoteCore(MembershipFsm fsm, NodeId id) {
            fsm.onMemberDescriptor(NodeInfo.nodeInfo(id, new NodeAddress(id.id(), 6000), Map.of(NodeInfo.LABEL_ROLE, "core")));
            fsm.onSwimHealthy(id, 1L);
        }

        @Test
        void drainedNode_whileDeparting_keepsTheEntry() {
            var fsm = fsmWithDepartureTimeout(LONG_TIMEOUT);

            promoteCore(fsm, DRAINED);
            drain(fsm, DRAINED);

            assertThat(fsm.memberStates()).containsEntry(DRAINED, "Departing");
            assertThat(registry.isDrainRequested(DRAINED)).isTrue();
        }

        @Test
        void drainedNode_thenDead_thenRejoined_isNotToldToDrainAgain() {
            var fsm = fsmWithDepartureTimeout(LONG_TIMEOUT);

            promoteCore(fsm, DRAINED);
            drain(fsm, DRAINED);
            fsm.onSwimDeparted(DRAINED, 2L);
            assertThat(fsm.memberStates()).containsEntry(DRAINED, "Dead");
            assertThat(registry.isDrainRequested(DRAINED)).isFalse();

            fsm.onSwimHealthy(DRAINED, 9L);

            assertThat(fsm.memberStates()).containsEntry(DRAINED, "Member");
            assertThat(registry.isDrainRequested(DRAINED)).isFalse();
        }

        /// The drain is refuted: a strictly newer incarnation brings Departing back to Member without ever touching
        /// DEAD, so only the Departing-to-Member edge can end the entry. The node is live and must not be re-drained.
        @Test
        void drainedNode_recoveredByNewerIncarnation_dropsTheEntry() {
            var fsm = fsmWithDepartureTimeout(LONG_TIMEOUT);

            promoteCore(fsm, DRAINED);
            drain(fsm, DRAINED);
            assertThat(registry.isDrainRequested(DRAINED)).isTrue();

            fsm.onSwimHealthy(DRAINED, 2L);

            assertThat(fsm.memberStates()).containsEntry(DRAINED, "Member");
            assertThat(registry.isDrainRequested(DRAINED)).isFalse();
        }

        /// #1054: a drain that never reached its target and has no death evidence is withdrawn by the DEPARTING timeout,
        /// back to Member, through the timer rather than through a SWIM event. Same edge shape, different driver.
        @Test
        void drainedNode_withdrawnUndeliveredByTheTimeout_dropsTheEntry() {
            var fsm = fsmWithDepartureTimeout(FIRING_TIMEOUT);

            promoteCore(fsm, DRAINED);
            drain(fsm, DRAINED);
            assertThat(registry.isDrainRequested(DRAINED)).isTrue();

            await().atMost(2, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(fsm.memberStates()).containsEntry(DRAINED, "Member"));
            assertThat(registry.isDrainRequested(DRAINED)).isFalse();
        }

        /// A drain commanded on a node that is already DEAD is ignored by the FSM, so no edge of its own ever ends it:
        /// the entry sits in the registry until the id rejoins. The rejoin edge (out of DEAD) must clear it.
        @Test
        void drainCommandedOnDeadNode_isNotInheritedByTheRejoin() {
            var fsm = fsmWithDepartureTimeout(LONG_TIMEOUT);

            promoteCore(fsm, DRAINED);
            drain(fsm, DRAINED);
            fsm.onSwimDeparted(DRAINED, 2L);
            assertThat(fsm.memberStates()).containsEntry(DRAINED, "Dead");

            drain(fsm, DRAINED);
            assertThat(fsm.memberStates()).as("instrument check: the FSM ignores a drain on a Dead node, leaving no edge")
                                          .containsEntry(DRAINED, "Dead");
            assertThat(registry.isDrainRequested(DRAINED)).as("instrument check: the leaked entry exists before the rejoin")
                                                          .isTrue();

            fsm.onSwimHealthy(DRAINED, 9L);

            assertThat(fsm.memberStates()).containsEntry(DRAINED, "Member");
            assertThat(registry.isDrainRequested(DRAINED)).isFalse();
        }

        @Test
        void drainOfOneNode_neverClearsAnotherNodesEntry() {
            var fsm = fsmWithDepartureTimeout(LONG_TIMEOUT);

            promoteCore(fsm, DRAINED);
            promoteCore(fsm, OTHER);
            drain(fsm, DRAINED);
            drain(fsm, OTHER);

            fsm.onSwimHealthy(DRAINED, 2L);

            assertThat(registry.drainTargets()).containsExactly(OTHER);
        }
    }
}
