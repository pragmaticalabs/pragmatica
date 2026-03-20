package org.pragmatica.cluster.node.forward;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;

import java.util.List;

/// Request to forward commands to a core node for consensus application.
public record ForwardApplyRequest<C extends Command>(
    NodeId sender,
    long correlationId,
    List<C> commands
) implements ProtocolMessage {
    @Override
    public boolean deliverToPassive() {
        return true;
    }
}
