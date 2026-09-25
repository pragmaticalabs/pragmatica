// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import static org.assertj.core.api.Assertions.assertThat;

class AetherNetworkInboundPolicyTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId WORKER = new NodeId("worker");

    @Test
    void fullSnapshotRequestsCannotBypassWorkerProjectionScope() {
        var request = new NetworkMessage.KVSyncRequest(WORKER);
        assertThat(AetherNetworkInboundPolicy.isAllowed(WORKER, request, true, CORE::equals, CORE::equals)).isFalse();
        assertThat(AetherNetworkInboundPolicy.isAllowed(CORE, new NetworkMessage.KVSyncRequest(CORE),
            false, CORE::equals, CORE::equals)).isFalse();
        assertThat(AetherNetworkInboundPolicy.isAllowed(CORE, new NetworkMessage.KVSyncRequest(CORE),
            true, CORE::equals, CORE::equals)).isTrue();
    }

    @Test
    void snapshotResponseRequiresTrustedActualResponderAndCoreRecipient() {
        var response = new NetworkMessage.KVSyncResponse(CORE, new byte[0]);
        assertThat(AetherNetworkInboundPolicy.isAllowed(WORKER, response, true, CORE::equals, CORE::equals)).isFalse();
        assertThat(AetherNetworkInboundPolicy.isAllowed(CORE, response, false, CORE::equals, CORE::equals)).isFalse();
        assertThat(AetherNetworkInboundPolicy.isAllowed(CORE, response, true, _ -> false, CORE::equals)).isTrue();
    }

    @Test
    void communitySnapshotMustComeFromItsNamedGovernorAndGoToCore() {
        var snapshot = new CommunityMetricsSnapshot("community", WORKER, 1, List.of(), 0, 0, 0);
        assertThat(AetherNetworkInboundPolicy.isAllowed(CORE, snapshot, true, CORE::equals, CORE::equals)).isFalse();
        assertThat(AetherNetworkInboundPolicy.isAllowed(WORKER, snapshot, false, CORE::equals, CORE::equals)).isFalse();
        assertThat(AetherNetworkInboundPolicy.isAllowed(WORKER, snapshot, true, CORE::equals, CORE::equals)).isTrue();
    }
}
