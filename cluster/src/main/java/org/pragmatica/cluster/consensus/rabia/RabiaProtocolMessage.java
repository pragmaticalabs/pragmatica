package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.Command;

public sealed interface RabiaProtocolMessage extends ProtocolMessage {
    NodeId sender();

    record Propose<C extends Command>(NodeId sender, long slot, Batch<C> batch) implements RabiaProtocolMessage {}

    record Vote(NodeId sender, long slot, BatchId batchId, boolean match) implements RabiaProtocolMessage {}

    record Decide<C extends Command>(NodeId sender, long slot, Batch<C> batch) implements RabiaProtocolMessage {}

    record SnapshotRequest(NodeId sender) implements RabiaProtocolMessage {}

    record SnapshotResponse(NodeId sender, byte[] snapshot, long slot) implements RabiaProtocolMessage {}
}
