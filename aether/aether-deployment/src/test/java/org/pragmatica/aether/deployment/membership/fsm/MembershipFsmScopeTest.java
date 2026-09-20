// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;
import static org.assertj.core.api.Assertions.assertThat;

class MembershipFsmScopeTest {
    @Test
    void excludesForeignObservationsAndForgetsScopeWithoutDeath() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis,
                                             Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
        var ownPeer = new NodeId("community-peer");
        var foreign = new NodeId("foreign-peer");
        var deaths = new ArrayList<NodeId>();
        fsm.onConfirmedDeparture(deaths::add);
        fsm.setTrackingEligibility(Set.of(ownPeer)::contains);
        for (int index = 0; index < 10_000; index++) {
            var peer = new NodeId("other-" + index);
            fsm.onMemberDescriptor(info(peer));
            fsm.onSwimHealthy(peer, 1);
        }
        fsm.onMemberDescriptor(info(ownPeer));
        fsm.onSwimHealthy(ownPeer, 1);
        assertThat(fsm.memberStates()).containsOnlyKeys(ownPeer);
        assertThat(fsm.countedMembers()).containsExactly(ownPeer);

        fsm.setTrackingEligibility(Set.of(foreign)::contains);
        fsm.onSwimFaulty(ownPeer, 1);
        fsm.onMemberDescriptor(info(foreign));
        assertThat(fsm.memberStates()).containsOnlyKeys(foreign);
        assertThat(deaths).isEmpty();

        fsm.setTrackingEligibility(Set.of(ownPeer)::contains);
        fsm.onMemberDescriptor(info(ownPeer));
        assertThat(fsm.memberStates()).containsEntry(ownPeer, "Observed");
        assertThat(fsm.countedMembers()).isEmpty();
        fsm.setTrackingEligibility(_ -> false);
    }

    private static NodeInfo info(NodeId id) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 6000).unwrap(),
                                 Map.of(NodeInfo.LABEL_ROLE, "worker"));
    }
}
