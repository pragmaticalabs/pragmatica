// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/// Membership v2 (B5a) — leader-local registry of nodes the leader wants to command DRAIN.
///
/// Owned by the leader. The set of registered targets is snapshotted by the ping dispatch path
/// (`ClusterSyncContext.sendOnePing`) so each outbound `ClusterSyncPing` carries
/// `NodePingCommand.DRAIN` for any peer present in this registry, else `NONE`. A node is cleared
/// once its drain is acknowledged / observed complete by the leader-side reconciler.
///
/// Thread-safe: backed by `ConcurrentHashMap.newKeySet()` so the periodic ping tick (read via
/// [`#isDrainRequested`] / [`#drainTargets`]) and the operator/CTM trigger (write via
/// [`#requestDrain`] / [`#clearDrain`]) don't race. All operations are idempotent.
public final class DrainCommandRegistry {
    private final Set<NodeId> targets = ConcurrentHashMap.newKeySet();

    private DrainCommandRegistry() {}

    /// Default registry. Empty until the leader registers drain targets.
    public static DrainCommandRegistry drainCommandRegistry() {
        return new DrainCommandRegistry();
    }

    /// Register `node` as a DRAIN target. Idempotent: repeated calls are no-ops. `null` ignored.
    @Contract public void requestDrain(NodeId node) {
        if (node == null) {return;}
        targets.add(node);
    }

    /// Clear `node` from the DRAIN target set. Idempotent: clearing an absent node is a no-op.
    @Contract public void clearDrain(NodeId node) {
        if (node == null) {return;}
        targets.remove(node);
    }

    /// Whether `node` is currently a DRAIN target. Read by the ping dispatch path.
    public boolean isDrainRequested(NodeId node) {
        return node != null && targets.contains(node);
    }

    /// Immutable snapshot of the current DRAIN target set.
    public Set<NodeId> drainTargets() {
        return Set.copyOf(targets);
    }
}
