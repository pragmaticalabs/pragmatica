package org.pragmatica.cluster.node.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;

import java.util.List;

/// Response from a core node after applying forwarded commands via consensus.
public record ForwardApplyResponse<R>(
    NodeId sender,
    long correlationId,
    List<R> results,
    Option<String> error
) implements ProtocolMessage {
    @Override
    public boolean deliverToPassive() {
        return true;
    }
}
