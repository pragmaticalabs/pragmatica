package org.pragmatica.cluster.node.forward;

import java.util.List;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;


/// Request to forward commands to a core node for consensus application.
public record ForwardApplyRequest<C extends Command>(NodeId sender, long correlationId, List<C> commands) implements ProtocolMessage {
    @Override
    public StreamType streamType() {
        return StreamType.CONSENSUS;
    }
}
