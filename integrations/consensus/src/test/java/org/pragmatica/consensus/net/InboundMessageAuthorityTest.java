package org.pragmatica.consensus.net;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.StateValue;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;

import static org.assertj.core.api.Assertions.assertThat;

class InboundMessageAuthorityTest {
    private final NodeId core = NodeId.nodeId("core").unwrap();
    private final NodeId origin = NodeId.nodeId("origin").unwrap();
    private final NodeId worker = NodeId.nodeId("worker").unwrap();

    @Test
    void directSenderBindingCannotBeBypassedByVoterRelayPermission() {
        var vote = new VoteRound1(origin, Phase.ZERO, StateValue.V1);
        assertThat(InboundMessageAuthority.isBound(origin, vote, _ -> false)).isTrue();
        assertThat(InboundMessageAuthority.isBound(core, vote, _ -> true)).isFalse();
        assertThat(InboundMessageAuthority.isBound(worker, new SyncRequest(core), _ -> true)).isFalse();
    }

    @Test
    void onlyInstalledVotersMayRelayProposalEvidence() {
        var proposal = new Propose<>(origin, Phase.ZERO, Batch.emptyBatch());
        assertThat(InboundMessageAuthority.isBound(origin, proposal, _ -> false)).isTrue();
        assertThat(InboundMessageAuthority.isBound(core, proposal, core::equals)).isTrue();
        assertThat(InboundMessageAuthority.isBound(worker, proposal, core::equals)).isFalse();
        assertThat(InboundMessageAuthority.isBound(core, proposal, _ -> false)).isFalse();
    }

    @Test
    void discoveryAndLegacySnapshotRequestsCannotImpersonatePeers() {
        assertThat(InboundMessageAuthority.isBound(worker, new NetworkMessage.DiscoverNodes(core), _ -> false)).isFalse();
        assertThat(InboundMessageAuthority.isBound(worker, new NetworkMessage.KVSyncRequest(core), _ -> false)).isFalse();
    }
}
