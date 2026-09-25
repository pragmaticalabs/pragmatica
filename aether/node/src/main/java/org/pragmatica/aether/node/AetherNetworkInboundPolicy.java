// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.util.function.Predicate;

import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.messaging.Message;


/// Application checks supplement the transport's direct ProtocolMessage origin binding.
/// Workers obtain only scoped metadata, never the framework's full-state synchronization path.
public interface AetherNetworkInboundPolicy {
    static boolean isAllowed(NodeId peer,
                             Message.Wired message,
                             boolean coreReceiver,
                             Predicate<NodeId> admittedCore,
                             Predicate<NodeId> stateTransferPeer) {
        return switch (message) {
            case CommunityMetricsSnapshot snapshot -> coreReceiver && peer.equals(snapshot.governorId());
            case NetworkMessage.KVSyncRequest request -> coreReceiver && peer.equals(request.sender()) && admittedCore.test(peer);
            // The historical field name is misleading: dispatchSnapshot writes the responder's ID.
            case NetworkMessage.KVSyncResponse response -> coreReceiver && peer.equals(response.target()) && stateTransferPeer.test(peer);
            default -> true;
        };
    }
}
