/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.fsm;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Shared cluster-lifecycle event vocabulary for FSMs that react to cluster state changes.
///
/// Non-sealed on purpose: FSMs declare their own domain events (ElectionTick, RenewalSucceeded,
/// BlueprintPut, …) that also implement `ClusterFsmEvent`, and the FSM's event type parameter
/// is `ClusterFsmEvent`. This keeps state-handler pattern matching uniform — every event is a
/// `ClusterFsmEvent`, whether cluster-generated or domain-specific.
///
/// [`ClusterFsmRouter`] maps `MessageRouter` notifications (`TransportObservation`,
/// `ClusterStateNotification`, `LeaderNotification.LeaderChange`) into the concrete records
/// below and dispatches them to the target FSM. (`ClusterFsmRouter` consumes the local
/// transport stream because the leader-election FSM needs a fast-path during cluster
/// bootstrap before consensus exists. For canonical decisions, see `MembershipDecision`.)
public interface ClusterFsmEvent {
    record QuorumEstablished() implements ClusterFsmEvent {}

    record QuorumDisappeared() implements ClusterFsmEvent {}

    record LeaderChange(Option<NodeId> leader, boolean localIsLeader) implements ClusterFsmEvent {}

    /// A peer-set change carrying the observer's whole view (#2971). `sequence` is the transport observation's
    /// sequence (`TransportObservation#sequence`): a consumer that replaces its view wholesale drops an event whose
    /// sequence is not above the last one it applied, so a delayed older view cannot overwrite a newer one.
    /// [`#NO_SEQUENCE`] marks an unordered event: FSMs without a fence apply it unconditionally, while leader election
    /// drops it, because only sequenced transport views may replace its view (#3044).
    sealed interface TopologyChange extends ClusterFsmEvent {
        long NO_SEQUENCE = 0L;
        NodeId node();
        List<NodeId> topology();
        long sequence();
    }

    record NodeAdded(NodeId node, List<NodeId> topology, long sequence) implements TopologyChange {
        /// Sequence-less convenience constructor — an event carrying [`TopologyChange#NO_SEQUENCE`].
        public NodeAdded(NodeId node, List<NodeId> topology) {
            this(node, topology, NO_SEQUENCE);
        }
    }

    /// Unifies the previous `NodeRemoved` (SWIM/QUIC-detected departure) and `NodeDown`
    /// (local shutdown with non-empty topology) paths into a single event. The FSM treats
    /// both identically.
    record NodeGone(NodeId node, List<NodeId> topology, long sequence) implements TopologyChange {
        /// Sequence-less convenience constructor — an event carrying [`TopologyChange#NO_SEQUENCE`].
        public NodeGone(NodeId node, List<NodeId> topology) {
            this(node, topology, NO_SEQUENCE);
        }
    }

    record Shutdown() implements ClusterFsmEvent {}
}
