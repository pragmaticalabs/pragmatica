// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.health.WorkerEndpointDirectory;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class HierarchyDialTargetsTest {
    @Test
    void freshWorkerBootstrapsFromCurrentConfiguredCoresWithoutUsingHistoricalGenesis() {
        var self = new NodeId("worker");
        var current = new NodeId("current-core");
        var workerPeer = new NodeId("other-worker");
        var address = NodeAddress.nodeAddress("localhost", 6000).unwrap();
        var seeds = java.util.List.of(NodeInfo.nodeInfo(self, address),
            NodeInfo.nodeInfo(current, address, Map.of(NodeInfo.LABEL_ROLE, "core")),
            NodeInfo.nodeInfo(workerPeer, address, Map.of(NodeInfo.LABEL_ROLE, "worker")));
        assertThat(AetherNode.workerBootstrapCores(self, seeds)).containsExactly(current);
    }

    @Test
    void authoritativeDialScopeIncludesObservedCoreAndWorkersWithoutPromotingHealth() {
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                                     System::currentTimeMillis,
                                                     Long.MAX_VALUE,
                                                     TimeSpan.timeSpan(40).millis());
        var core = new NodeId("verified-core");
        var governor = new NodeId("governor");
        var pending = new NodeId("pending-worker");
        var unrelated = new NodeId("unrelated-worker");

        for (var peer : Set.of(core, governor, pending, unrelated)) {
            membership.onMemberDescriptor(NodeInfo.nodeInfo(peer,
                                                            NodeAddress.nodeAddress("localhost", 6000).unwrap(),
                                                            Map.of(NodeInfo.LABEL_ROLE,
                                                                   peer.equals(core)
                                                                   ? "core"
                                                                   : "worker")));
        }

        var allowed = Set.of(core, governor, pending);
        var targets = AetherNode.desiredDialTargets(membership,
                                                    WorkerEndpointDirectory.workerEndpointDirectory(),
                                                    allowed::contains);

        assertThat(targets.stream().map(NodeInfo::id)).containsExactlyInAnyOrder(core, governor, pending);
        assertThat(membership.countedMembers()).isEmpty();
        membership.setTrackingEligibility(_ -> false);
    }
}
