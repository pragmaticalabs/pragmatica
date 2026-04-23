/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.NodeId;

import java.util.List;

/// Events that drive the leader-election state machine. The `type()` enum is used by the state
/// machine definition for transition matching; payload is carried on the record and made
/// available to transition actions via the user context.
public sealed interface LeaderElectionEvent {
    /// Enumerated event kinds. Transition matching in the state machine is based on this value
    /// (records carry identity/payload; enum values provide stable equality for dispatch).
    enum Type {
        QUORUM_ESTABLISHED,
        QUORUM_DISAPPEARED,
        CONSENSUS_READY,
        NODE_ADDED,
        NODE_GONE,
        LEADER_COMMITTED,
        ELECTION_TICK,
        PROPOSAL_SETTLED,
        SHUTDOWN
    }

    Type type();

    record QuorumEstablished() implements LeaderElectionEvent {
        @Override public Type type() { return Type.QUORUM_ESTABLISHED; }
    }

    record QuorumDisappeared() implements LeaderElectionEvent {
        @Override public Type type() { return Type.QUORUM_DISAPPEARED; }
    }

    record ConsensusReady() implements LeaderElectionEvent {
        @Override public Type type() { return Type.CONSENSUS_READY; }
    }

    record NodeAdded(NodeId nodeId, List<NodeId> topology) implements LeaderElectionEvent {
        @Override public Type type() { return Type.NODE_ADDED; }
    }

    /// Unified "node is gone" — covers the previous NodeRemoved (SWIM/QUIC detected departure)
    /// and NodeDown (local shutdown) paths. The leader-election FSM does not distinguish;
    /// routing callers feed both sources here.
    record NodeGone(NodeId nodeId, List<NodeId> topology) implements LeaderElectionEvent {
        @Override public Type type() { return Type.NODE_GONE; }
    }

    record LeaderCommitted(NodeId leader) implements LeaderElectionEvent {
        @Override public Type type() { return Type.LEADER_COMMITTED; }
    }

    record ElectionTick() implements LeaderElectionEvent {
        @Override public Type type() { return Type.ELECTION_TICK; }
    }

    /// Emitted when an in-flight proposal completes (success/failure) or the proposal deadline
    /// fires. `success` is true if `onSuccess` fired before the deadline.
    record ProposalSettled(NodeId candidate, boolean success, String detail) implements LeaderElectionEvent {
        @Override public Type type() { return Type.PROPOSAL_SETTLED; }
    }

    record Shutdown() implements LeaderElectionEvent {
        @Override public Type type() { return Type.SHUTDOWN; }
    }
}
