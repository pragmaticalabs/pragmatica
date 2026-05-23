// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;


/// Per-node tracker of the "ready to promote to ON_DUTY" candidate signal.
///
/// Owned per-node (NOT leader-only). The Rabia layer flips the candidate on once it has applied
/// a full KV-sync snapshot from the leader; `ClusterSyncCollector` reads the current value when
/// building outgoing pongs and exposes it through `ClusterSyncPong.readyCandidate`. The leader's
/// `ClusterSyncPongSignalFan` reacts to non-empty values by emitting
/// `LifecycleCommand.ForceOnDuty` through `LifecycleWriter.applyCommand`.
///
/// The candidate is cleared once this node observes its OWN `NodeLifecycleValue.state == ON_DUTY`
/// via KV notification, closing the loop without manual orchestration. All operations are
/// idempotent — `markReady` may be invoked multiple times during sync-complete fan-out and
/// `clear` is safe whether or not a candidate is currently set.
///
/// See `aether/docs/specs/cluster-convergence-reconciler-spec.md` §SYNCING.
@Contract public interface NodeReadinessTracker {
    /// Read current candidate — invoked by `ClusterSyncCollector` when building outgoing pong.
    /// `Option.none()` during steady state and on the leader's own pongs.
    Option<NodeId> candidate();

    /// Set candidate. Called from the Rabia post-sync-complete signal — this node has just
    /// applied a full KV snapshot from the leader and is now ready to be promoted to ON_DUTY.
    /// Idempotent: re-invocation while the candidate is already set is a no-op.
    @Contract void markReady(NodeId self);

    /// Clear candidate. Called when this node observes its own `NodeLifecycleValue` transition
    /// to ON_DUTY via KV notification. Idempotent: invocation while the candidate is already
    /// `Option.none()` is a no-op.
    @Contract void clear();

    /// Default in-memory tracker backed by an `AtomicReference<Option<NodeId>>`.
    static NodeReadinessTracker nodeReadinessTracker() {
        return new AtomicNodeReadinessTracker();
    }

    final class AtomicNodeReadinessTracker implements NodeReadinessTracker {
        private final AtomicReference<Option<NodeId>> candidate = new AtomicReference<>(Option.none());

        @Override public Option<NodeId> candidate() {
            return candidate.get();
        }

        @Override @Contract public void markReady(NodeId self) {
            if (self == null) {return;}
            candidate.compareAndSet(Option.none(), Option.some(self));
        }

        @Override @Contract public void clear() {
            candidate.set(Option.none());
        }
    }
}
