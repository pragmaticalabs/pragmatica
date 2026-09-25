// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.health.WorkerEndpointDirectory;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerEndpointDirectoryTest {
    @Test
    void dependencyConnectionDoesNotEnlargeMembershipOrAdmitUnknownEndpoints() {
        var directory = WorkerEndpointDirectory.workerEndpointDirectory();
        var membership = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis,
            Long.MAX_VALUE, TimeSpan.timeSpan(40).millis());
        membership.setTrackingEligibility(_ -> false);
        var dependency = new NodeId("other-community-service");
        var unrelated = new NodeId("unrelated");
        directory.install(List.of(NodeInfo.nodeInfo(dependency,
            NodeAddress.nodeAddress("localhost", 6000).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "worker"))));
        assertThat(directory.accessible(List.of(dependency, unrelated), membership, Set.of(dependency, unrelated)))
            .containsExactly(dependency);
        assertThat(directory.accessible(List.of(dependency), membership, Set.of())).isEmpty();
        assertThat(membership.memberStates()).isEmpty();
        assertThat(directory.desiredConnections()).extracting(NodeInfo::id).containsExactly(dependency);
        directory.install(List.of());
        assertThat(directory.accessible(List.of(dependency), membership, Set.of(dependency))).isEmpty();
    }
}
