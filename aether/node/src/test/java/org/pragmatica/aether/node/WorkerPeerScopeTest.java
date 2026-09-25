// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.node.health.WorkerPeerScope;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;
import static org.assertj.core.api.Assertions.assertThat;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;


class WorkerPeerScopeTest {
    @Test
    void verifiedDirectoryReplacesBootstrapCoreHintsWithoutDeclaringPeersAlive() {
        var self = new NodeId("worker-self");
        var oldCore = new NodeId("old-core");
        var newCore = new NodeId("new-core");
        var peer = new NodeId("community-peer");
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis,
            Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
        var codec = SliceCodec.sliceCodec(List.of());
        var topology = new TopologyConfig(self, 1, TimeSpan.timeSpan(1).seconds(),
            TimeSpan.timeSpan(10).seconds(), List.of(info(self, "worker"), info(oldCore, "core")));
        var swim = CoreSwimHealthDetector.coreSwimHealthDetector(MessageRouter.mutable(), topology, codec, codec);
        var scope = WorkerPeerScope.workerPeerScope(self, () -> Set.of(oldCore), membership, swim);
        membership.onMemberDescriptor(info(oldCore, "core"));
        assertThat(scope.routingCoreIds()).containsExactly(oldCore);
        var directory = List.of(info(newCore, "core"), info(peer, "worker"));
        scope.installDirectory(directory);
        assertThat(scope.routingCoreIds()).containsExactly(newCore);
        assertThat(scope.contains(oldCore)).isFalse();
        assertThat(scope.contains(self)).isTrue();
        assertThat(scope.contains(peer)).isTrue();
        assertThat(membership.memberStates()).containsOnlyKeys(newCore, peer);
        assertThat(membership.countedMembers()).isEmpty();
        membership.setTrackingEligibility(_ -> false);
    }

    private static NodeInfo info(NodeId node, String role) {
        return NodeInfo.nodeInfo(node, NodeAddress.nodeAddress("localhost", 6000).unwrap(), Map.of(NodeInfo.LABEL_ROLE, role));
    }
}
