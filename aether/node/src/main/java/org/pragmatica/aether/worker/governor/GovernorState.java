package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;

/// The result of governor election: either this node is the governor, or another node is.
@SuppressWarnings("JBCT-STY-04") // ADT with concrete variants, not a utility interface
public sealed interface GovernorState {
    /// This node has been elected governor.
    record Governor(NodeId self) implements GovernorState {
        public static Governor governor(NodeId self) {
            return new Governor(self);
        }
    }

    /// Another node is the governor.
    record Follower(NodeId governorId) implements GovernorState {
        public static Follower follower(NodeId governorId) {
            return new Follower(governorId);
        }
    }
}
