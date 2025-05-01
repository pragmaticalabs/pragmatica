package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.Command;

/// Message types for the Rabia consensus protocol.
public sealed interface RabiaProtocolMessage extends ProtocolMessage {
    NodeId sender();

    /// Initial proposal from a node.
    record Propose<C extends Command>(NodeId sender, Phase phase, Batch<C> value)
            implements RabiaProtocolMessage {}

    /// Round 1 vote message.
    record VoteRound1(NodeId sender, Phase phase, StateValue stateValue)
            implements RabiaProtocolMessage {}

    /// Round 2 vote message.
    record VoteRound2(NodeId sender, Phase phase, StateValue stateValue)
            implements RabiaProtocolMessage {}

    /// Decision broadcast message.
    record Decision<C extends Command>(
            NodeId sender, 
            Phase phase, 
            StateValue stateValue,
            Batch<C> value)
            implements RabiaProtocolMessage {}

    /// Phase synchronization request.
    record SyncRequest(NodeId sender) implements RabiaProtocolMessage {}

    /// Phase synchronization response.
    record SyncResponse(NodeId sender, Phase phase, byte[] snapshot) implements RabiaProtocolMessage {}
}
