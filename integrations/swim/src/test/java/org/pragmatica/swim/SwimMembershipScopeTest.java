/* Licensed under the Apache License, Version 2.0. */
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import static org.assertj.core.api.Assertions.assertThat;

class SwimMembershipScopeTest {
    @Test
    void foreignGossipIsNotRetainedButPingStillReceivesAck() {
        var transport = new SwimProtocolTest.RecordingTransport();
        var listener = new SwimProtocolTest.RecordingListener();
        var self = new NodeId("self");
        var peer = new NodeId("community-peer");
        var sender = new NodeId("other-community");
        var address = new InetSocketAddress("127.0.0.1", 9000);
        var protocol = SwimProtocol.swimProtocol(SwimConfig.swimConfig(), transport, listener, self, address).unwrap();
        protocol.setMembershipEligibility(Set.of(peer)::contains);
        for (int index = 0; index < 10_000; index++) {
            var update = MembershipUpdate.membershipUpdate(new NodeId("foreign-" + index), MemberState.ALIVE, 1, address);
            protocol.onMessage(address, Ping.ping(sender, index, List.of(update)));
        }
        assertThat(protocol.members()).isEmpty();
        assertThat(transport.sentMessages).hasSize(10_000);
        protocol.addSeedMember(peer, address);
        assertThat(protocol.members().get(peer).state()).isEqualTo(MemberState.OBSERVED);
        protocol.setMembershipEligibility(_ -> false);
        assertThat(protocol.members()).isEmpty();
        assertThat(listener.left).isEmpty();
        assertThat(listener.faulty).isEmpty();
        protocol.setMembershipEligibility(Set.of(peer)::contains);
        protocol.addSeedMember(peer, address);
        assertThat(protocol.members().get(peer).state()).isEqualTo(MemberState.OBSERVED);
        assertThat(protocol.everSeenHealthyForTest(peer)).isFalse();
    }
}
