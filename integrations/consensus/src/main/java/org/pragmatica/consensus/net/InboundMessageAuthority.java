package org.pragmatica.consensus.net;

import java.util.function.Predicate;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.messaging.Message;


/// Binds claimed message origin to the authenticated connection identity. Rabia proposal
/// evidence is the sole relay exception: trusted installed voters may repeat a proposal,
/// while the engine independently validates its original sender and electorate epoch.
public interface InboundMessageAuthority {
    static boolean isBound(NodeId peer, Message.Wired message, Predicate<NodeId> proposalRelays) {
        return switch (message) {
            case RabiaProtocolMessage.Synchronous.Propose<?> proposal -> peer.equals(proposal.sender()) || proposalRelays.test(peer);
            case ProtocolMessage protocol -> peer.equals(protocol.sender());
            case NetworkMessage.DiscoverNodes discovery -> peer.equals(discovery.self());
            case NetworkMessage.Hello hello -> peer.equals(hello.sender());
            case NetworkMessage.KeepAlive keepAlive -> peer.equals(keepAlive.sender());
            case NetworkMessage.KVSyncRequest request -> peer.equals(request.sender());
            default -> true;
        };
    }
}
