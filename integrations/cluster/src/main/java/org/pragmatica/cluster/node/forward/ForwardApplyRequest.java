package org.pragmatica.cluster.node.forward;

import java.util.List;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// Request to forward commands to a core node for consensus application.
/// `@Codec` is load-bearing (#634 boot guard catch): this type is routed and sent over the wire
/// (`ForwardingClusterNode`), and without a generated codec every forwarded command silently
/// vanished at the transport — the #492 class. Boot refuses the assembly when it is missing.
@Codec
public record ForwardApplyRequest<C extends Command>(NodeId sender, long correlationId, List<C> commands) implements ProtocolMessage {
    @Override
    public StreamType streamType() {
        return StreamType.CONSENSUS;
    }
}
