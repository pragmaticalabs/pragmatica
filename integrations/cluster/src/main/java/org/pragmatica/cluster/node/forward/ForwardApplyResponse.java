package org.pragmatica.cluster.node.forward;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;


/// Response from a core node after applying forwarded commands via consensus.
public record ForwardApplyResponse<R>(NodeId sender, long correlationId, List<R> results, Option<String> error) implements ProtocolMessage {
    @Override
    public StreamType streamType() {
        return StreamType.CONSENSUS;
    }
}
