// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.lang.Contract;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;


/// Node-local holder for the node's self-reported readiness state
/// (membership-architecture-v2-spec §7.5.1). Owned per-node (NOT leader-only). The
/// node ANDs its own local conditions and reports a single [NodeReportedState] on
/// every outgoing pong; `ClusterSyncCollector.buildPong` reads [current] and stamps
/// the value onto `ClusterSyncPong.lifecycleState` (repurposed per §7.5.3).
///
/// State is recomputed on every read of [current] from one LIVE input and two latched flags:
/// - **consensus-active** — a LIVE LEVEL signal sampled on every [current] call via the
///   injected `consensusActiveLevel` supplier (wired to `RabiaEngine::isActive`, the SAME
///   accessor leader election uses as its `consensusReadySupplier`). This replaces the former
///   edge-cached flag, which desynced when a `ConsensusPassive` edge was not followed by a
///   fresh `ConsensusActive` edge (the CAS quorum edge `false→true` does not re-fire while
///   quorum is already established) — leaving a healthy in-quorum node stuck reporting
///   `SYNCING`. Sampling the level every pong self-heals: a catching-up node reports `SYNCING`
///   (RabiaEngine `isActive` returns false during `Syncing`) and flips to `READY` on the next
///   pong once active.
/// - **subsystems-ready** — latched by [onSubsystemsReady] once local subsystems are up.
/// - **draining** — a sticky flag set by [onDrainStarted]; once draining the node
///   stays [NodeReportedState#DRAINING] (drain is uninterruptible per spec I9).
///
/// Resulting state: [NodeReportedState#DRAINING] if draining, else [NodeReportedState#READY]
/// if BOTH consensus-active (live) and subsystems-ready, else [NodeReportedState#SYNCING].
/// Starts in [NodeReportedState#SYNCING] (consensus-active level false at construction).
///
/// The two latched flags are held in a single [AtomicReference] over an immutable snapshot
/// updated with a CAS loop; the consensus-active level is read directly from the supplier.
/// Latch methods are idempotent.
@Contract
public interface NodeReportedStateHolder {
    /// Read the current node-reported state. Invoked by `ClusterSyncCollector` when
    /// building outgoing pongs. Samples the live consensus-active level on each call.
    NodeReportedState current();

    /// Local subsystems are up. Combined with a live consensus-active level this promotes the
    /// node to `READY`.
    @Contract
    void onSubsystemsReady();

    /// The node has entered the §8 drain procedure. Sticky: once set the node stays
    /// `DRAINING` regardless of subsequent consensus level / subsystem state (I9).
    @Contract
    void onDrainStarted();

    /// Default in-memory holder backed by an [AtomicReference] of an immutable latch snapshot.
    /// `consensusActiveLevel` is a LIVE level signal sampled on each [current] call (wire it to
    /// `RabiaEngine::isActive`). Starts in [NodeReportedState#SYNCING].
    static NodeReportedStateHolder nodeReportedStateHolder(BooleanSupplier consensusActiveLevel) {
        return new AtomicNodeReportedStateHolder(consensusActiveLevel);
    }

    final class AtomicNodeReportedStateHolder implements NodeReportedStateHolder {
        private final BooleanSupplier consensusActiveLevel;
        private final AtomicReference<Latches> latches = new AtomicReference<>(Latches.INITIAL);

        private AtomicNodeReportedStateHolder(BooleanSupplier consensusActiveLevel) {
            this.consensusActiveLevel = consensusActiveLevel;
        }

        @Override
        public NodeReportedState current() {
            return latches.get()
                          .toState(consensusActiveLevel.getAsBoolean());
        }

        @Override
        @Contract
        public void onSubsystemsReady() {
            latches.updateAndGet(Latches::withSubsystemsReady);
        }

        @Override
        @Contract
        public void onDrainStarted() {
            latches.updateAndGet(Latches::withDraining);
        }

        private record Latches(boolean subsystemsReady, boolean draining) {
            private static final Latches INITIAL = new Latches(false, false);

            private NodeReportedState toState(boolean consensusActive) {
                return draining
                       ? NodeReportedState.DRAINING
                       : consensusActive && subsystemsReady
                         ? NodeReportedState.READY
                         : NodeReportedState.SYNCING;
            }

            private Latches withSubsystemsReady() {
                return new Latches(true, draining);
            }

            private Latches withDraining() {
                return new Latches(subsystemsReady, true);
            }
        }
    }
}
