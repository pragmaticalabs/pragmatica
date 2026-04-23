/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;

/// Shared cluster-lifecycle event vocabulary for FSMs that react to cluster state changes.
///
/// Non-sealed on purpose: FSMs declare their own domain events (ElectionTick, RenewalSucceeded,
/// BlueprintPut, …) that also implement `ClusterFsmEvent`, and the FSM's event type parameter
/// is `ClusterFsmEvent`. This keeps state-handler pattern matching uniform — every event is a
/// `ClusterFsmEvent`, whether cluster-generated or domain-specific.
///
/// [`ClusterFsmRouter`] maps `MessageRouter` notifications (`TopologyChangeNotification`,
/// `QuorumStateNotification`, `LeaderNotification.LeaderChange`) into the concrete records
/// below and dispatches them to the target FSM.
public interface ClusterFsmEvent {
    record QuorumEstablished() implements ClusterFsmEvent {}

    record QuorumDisappeared() implements ClusterFsmEvent {}

    record LeaderChange(Option<NodeId> leader, boolean localIsLeader) implements ClusterFsmEvent {}

    record NodeAdded(NodeId node, List<NodeId> topology) implements ClusterFsmEvent {}

    /// Unifies the previous `NodeRemoved` (SWIM/QUIC-detected departure) and `NodeDown`
    /// (local shutdown with non-empty topology) paths into a single event. The FSM treats
    /// both identically.
    record NodeGone(NodeId node, List<NodeId> topology) implements ClusterFsmEvent {}

    record Shutdown() implements ClusterFsmEvent {}
}
