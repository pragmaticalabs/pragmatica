package org.pragmatica.aether.stream.consensus;

import org.pragmatica.lang.Promise;

/// Abstraction over Rabia consensus proposal for stream events.
///
/// Implementations submit a `StreamConsensusCommand` through the consensus protocol
/// and return the offset assigned by the state machine once the command is committed.
///
/// The actual Rabia wiring is provided by the node module; this interface keeps
/// the stream module decoupled from consensus infrastructure.
@FunctionalInterface
public interface ConsensusProposer {

    /// Propose a stream event through consensus.
    /// Returns the offset assigned by the state machine after the command is committed.
    Promise<Long> propose(StreamConsensusCommand command);

    /// No-op proposer for testing without consensus infrastructure.
    /// Returns -1 as the assigned offset.
    ConsensusProposer NOOP = _ -> Promise.success(-1L);
}
