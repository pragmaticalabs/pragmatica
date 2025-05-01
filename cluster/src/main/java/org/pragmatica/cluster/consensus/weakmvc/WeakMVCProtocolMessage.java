package org.pragmatica.cluster.consensus.weakmvc;

import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.Command;

/**
 * Message types for the Weak MVC consensus protocol.
 */
public sealed interface WeakMVCProtocolMessage extends ProtocolMessage {
    NodeId sender();

    /**
     * Initial proposal from a node.
     */
    record Propose<C extends Command>(NodeId sender, Phase phase, Batch<C> value)
            implements WeakMVCProtocolMessage {}

    /**
     * Round 1 vote message.
     */
    record VoteRound1(NodeId sender, Phase phase, StateValue stateValue)
            implements WeakMVCProtocolMessage {}

    /**
     * Round 2 vote message.
     */
    record VoteRound2(NodeId sender, Phase phase, StateValue stateValue)
            implements WeakMVCProtocolMessage {}

    /**
     * Decision broadcast message.
     */
    record Decision<C extends Command>(
            NodeId sender, 
            Phase phase, 
            StateValue stateValue,
            Batch<C> value)
            implements WeakMVCProtocolMessage {}

    /**
     * Phase synchronization request.
     */
    record SyncRequest(NodeId sender, long attempt) implements WeakMVCProtocolMessage {}

    /**
     * Phase synchronization response.
     */
    record SyncResponse(NodeId sender, Phase phase, boolean inPhase, byte[] snapshot) implements WeakMVCProtocolMessage {}
}
