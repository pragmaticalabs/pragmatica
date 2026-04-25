// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
//
// TODO(rc2-#189): real implementation writes a DRAINING NodeLifecycleValue atom via consensus,
// awaits acknowledgement from peers (so they stop sending new traffic to the draining node), and
// marks the drain complete in the KV-Store. Theme C (rc1) installs only the structural handles
// (interface, no-op stub, NodeDeploymentState.Leaving placeholder, AppHttpState.Quiesced) so the
// rc2 implementation drops in without touching the FSM topology again.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

/// Coordinator for the two-phase drain protocol that takes a node out of routing rotation
/// before terminating it (scale-down, rolling update, operator-initiated drain, FAULTY
/// eviction). Sealed in rc1 to a single [`NoOpDrainCoordinator`] implementation; rc2 #189
/// will add the real consensus-backed implementation.
///
/// Lifecycle of a drain operation (rc2 contract):
/// 1. Caller invokes [`#prepareDrain`] — coordinator writes `NodeLifecycleValue(DRAINING)`
///    via consensus and broadcasts the intent to peers.
/// 2. Caller invokes [`#awaitDrainAck`] — promise resolves when peers have acknowledged
///    by stopping new traffic to the draining node, or fails on timeout.
/// 3. Caller invokes [`#markDrainComplete`] — terminal sink that records observability /
///    bookkeeping. Real terminate (`provider.terminate(...)`) happens in the caller.
public sealed interface DrainCoordinator permits NoOpDrainCoordinator {

    /// Phase 1 of the drain protocol. rc1 stub returns immediate success without touching
    /// the KV-Store. rc2 will write `DRAINING` and dispatch a `LeavingRequested` event into
    /// the target node's [`org.pragmatica.aether.deployment.node.fsm.NodeDeploymentState`]
    /// FSM via the standard message-router pathway.
    Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason);

    /// Phase 2 of the drain protocol. rc1 stub returns immediate success — the caller's
    /// terminate path proceeds without waiting. rc2 will block on per-peer acknowledgements
    /// up to the supplied timeout, then either resolve (all peers acked) or fail with a
    /// `DrainTimeout` cause.
    Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout);

    /// Phase 3 of the drain protocol. rc1 stub is a no-op. rc2 will record the drain
    /// completion (observability + tombstone for late-arriving acks).
    @Contract
    void markDrainComplete(NodeId nodeId);

    /// Reason a drain was initiated. Carried through the protocol so observers, metrics,
    /// and the eventual `DECOMMISSIONED` audit trail can distinguish operator action from
    /// automated eviction.
    enum DrainReason {
        /// Cluster-topology-manager scale-down chose this node for termination.
        SCALE_DOWN,
        /// Rolling update / rolling restart cycle.
        ROLLING_UPDATE,
        /// Operator issued an explicit drain via the management API / CLI.
        OPERATOR_DRAIN,
        /// HealthReconciler-driven eviction of a FAULTY peer.
        FAULTY_EVICTION
    }
}
