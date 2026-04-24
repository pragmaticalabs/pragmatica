// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;

/// Domain event vocabulary for the cluster-sync scheduler FSM.
///
/// This interface extends [`ClusterFsmEvent`] so the FSM's event parameter stays uniform —
/// shared cluster events (`QuorumEstablished`, `QuorumDisappeared`, `NodeAdded`, `NodeGone`,
/// `Shutdown`, `LeaderChange`) and the domain-specific events below are all treated as a
/// single sealed vocabulary from the state handlers' point of view.
///
/// - [`PingTick`] — either scheduled by the internal [`org.pragmatica.lang.utils.SharedScheduler`]
///   (on entry to `Pinging`) or triggered manually by the public `sendPingsNow()` entry point. In
///   `Pinging` the handler sends pings, updates `lastSentEpoch` per target, increments the
///   per-peer miss counter, and emits [`org.pragmatica.aether.slice.generation.HealthSignal.PingTimeout`]
///   once the counter crosses the threshold. In `Dormant` / `Stopped` the event is ignored.
/// - [`PongReceived`] — inbound pong observed. In `Pinging` the handler clears the per-peer miss
///   counter by swapping the state record. In `Dormant` / `Stopped` the event is ignored (no
///   counter to reset).
public interface ClusterSyncEvents extends ClusterFsmEvent {

    /// Scheduled tick — caller supplies the authoritative epoch to embed in outbound pings.
    /// Using the caller-provided epoch (rather than re-reading the supplier inside the handler)
    /// keeps the dispatched event self-describing and avoids a second epoch observation under
    /// the CAS loop.
    record PingTick(Epoch currentEpoch) implements ClusterSyncEvents {}

    /// Pong observed from a peer — resets that peer's miss counter.
    record PongReceived(NodeId peer) implements ClusterSyncEvents {}
}
