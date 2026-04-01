package org.pragmatica.aether.stream.consensus;

import org.pragmatica.lang.Promise;

import static org.pragmatica.aether.stream.consensus.StreamConsensusCommand.streamConsensusCommand;

/// Publish path for STRONG consistency streams.
///
/// Events are proposed through Rabia consensus before being appended locally.
/// The consensus state machine handler is responsible for applying the committed
/// command to the local `StreamPartitionManager` on every node.
///
/// This class only handles the proposal side — it creates the command and submits
/// it through the `ConsensusProposer`. The local append happens in the state machine
/// callback (wired in the node module), not here.
public final class ConsensusPublishPath {
    private final ConsensusProposer proposer;

    private ConsensusPublishPath(ConsensusProposer proposer) {
        this.proposer = proposer;
    }

    /// Create a ConsensusPublishPath with the given proposer.
    public static ConsensusPublishPath consensusPublishPath(ConsensusProposer proposer) {
        return new ConsensusPublishPath(proposer);
    }

    /// Propose an event through consensus for total-order delivery.
    /// Returns the offset assigned by the state machine after commit.
    public Promise<Long> publish(String streamName, int partition, byte[] payload, long timestamp) {
        return proposer.propose(streamConsensusCommand(streamName, partition, payload, timestamp));
    }
}
