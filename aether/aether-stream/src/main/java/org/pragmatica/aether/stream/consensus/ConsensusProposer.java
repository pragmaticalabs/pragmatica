package org.pragmatica.aether.stream.consensus;

import org.pragmatica.lang.Promise;


/// Abstraction over Rabia consensus proposal for stream events.
///
/// Implementations submit a `StreamConsensusCommand` through the consensus protocol
/// and return the offset assigned by the state machine once the command is committed.
///
/// The actual Rabia wiring is provided by the node module; this interface keeps
/// the stream module decoupled from consensus infrastructure.
@FunctionalInterface public interface ConsensusProposer {
    Promise<Long> propose(StreamConsensusCommand command);

    ConsensusProposer NOOP = _ -> Promise.success(- 1L);
}
