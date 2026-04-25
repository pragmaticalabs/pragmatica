// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.consensus.NodeId;

import java.time.Instant;
import java.util.List;


/// State machine for cluster node count reconciliation.
/// Transitions: INACTIVE -> FORMING -> CONVERGED <-> RECONCILING, Any -> INACTIVE.
public sealed interface NodeReconcilerState {
    record Inactive(String reason) implements NodeReconcilerState{}

    record Forming(Instant since) implements NodeReconcilerState{}

    record Converged() implements NodeReconcilerState{}

    /// Reconciling carries one `ProvisioningSlot` per in-flight provision attempt. Each slot
    /// has its own deadline (`spawnedAtMs + provisioningTimeout`). On every reconcile tick the
    /// CTM expires timed-out slots and recomputes the deficit against `realActual + nonExpiredSlots`;
    /// a stalled or failed provision frees its slot when the deadline passes, allowing the next tick
    /// to dispatch a top-up. `terminating` lists nodes selected for termination during a surplus.
    record Reconciling(int targetSize,
                       int currentSize,
                       List<ProvisioningSlot> inFlight,
                       List<NodeId> terminating,
                       Instant startedAt) implements NodeReconcilerState{}

    /// Tracks one in-flight CTM provisioning attempt. `spawnedAtMs` is when the dispatch fired;
    /// `deadlineMs` is the absolute wall-clock millisecond after which the slot is considered
    /// expired and is dropped from the in-flight list so that the deficit can be recomputed.
    /// FIFO timeout — there is intentionally no slot-to-node binding; the next reconcile tick
    /// recomputes the deficit from real-actual healthy ON_DUTY count plus surviving slots.
    record ProvisioningSlot(long spawnedAtMs, long deadlineMs){}
}
