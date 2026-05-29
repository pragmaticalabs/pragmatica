// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.lang.Contract;

import java.util.concurrent.atomic.AtomicReference;

/// Node-local holder for the node's self-reported readiness state
/// (membership-architecture-v2-spec §7.5.1). Owned per-node (NOT leader-only). The
/// node ANDs its own local conditions and reports a single [NodeReportedState] on
/// every outgoing pong; `ClusterSyncCollector.buildPong` reads [current] and stamps
/// the value onto `ClusterSyncPong.lifecycleState` (repurposed per §7.5.3).
///
/// State is recomputed on every transition from three flags:
/// - **consensus-active** — set/cleared by [onConsensusActive] / [onConsensusPassive]
///   (driven by RabiaEngine `ConsensusActive` / `ConsensusPassive` edges).
/// - **subsystems-ready** — set by [onSubsystemsReady] once local subsystems are up.
/// - **draining** — a sticky flag set by [onDrainStarted]; once draining the node
///   stays [NodeReportedState#DRAINING] (drain is uninterruptible per spec I9).
///
/// Resulting state: [NodeReportedState#DRAINING] if draining, else
/// [NodeReportedState#READY] if BOTH consensus-active and subsystems-ready, else
/// [NodeReportedState#SYNCING]. Starts in [NodeReportedState#SYNCING].
///
/// All transitions are thread-safe via a single [AtomicReference] holding an immutable
/// flag snapshot updated with a CAS loop. Transition methods are idempotent.
@Contract public interface NodeReportedStateHolder {
    /// Read the current node-reported state. Invoked by `ClusterSyncCollector` when
    /// building outgoing pongs.
    NodeReportedState current();

    /// Local consensus reached `Active` — RabiaEngine `ConsensusActive` edge.
    @Contract void onConsensusActive();

    /// Local consensus dropped to `Passive` — RabiaEngine `ConsensusPassive` edge.
    /// Clears the consensus-active flag (a `READY` node falls back to `SYNCING` and
    /// must re-sync), unless already draining.
    @Contract void onConsensusPassive();

    /// Local subsystems are up. Combined with consensus-active this promotes the node
    /// to `READY`.
    @Contract void onSubsystemsReady();

    /// The node has entered the §8 drain procedure. Sticky: once set the node stays
    /// `DRAINING` regardless of subsequent consensus / subsystem edges (I9).
    @Contract void onDrainStarted();

    /// Default in-memory holder backed by an [AtomicReference] of an immutable flag
    /// snapshot. Starts in [NodeReportedState#SYNCING].
    static NodeReportedStateHolder nodeReportedStateHolder() {
        return new AtomicNodeReportedStateHolder();
    }

    final class AtomicNodeReportedStateHolder implements NodeReportedStateHolder {
        private final AtomicReference<Flags> flags = new AtomicReference<>(Flags.INITIAL);

        @Override public NodeReportedState current() {
            return flags.get().toState();
        }

        @Override @Contract public void onConsensusActive() {
            flags.updateAndGet(Flags::withConsensusActive);
        }

        @Override @Contract public void onConsensusPassive() {
            flags.updateAndGet(Flags::withConsensusPassive);
        }

        @Override @Contract public void onSubsystemsReady() {
            flags.updateAndGet(Flags::withSubsystemsReady);
        }

        @Override @Contract public void onDrainStarted() {
            flags.updateAndGet(Flags::withDraining);
        }

        private record Flags(boolean consensusActive, boolean subsystemsReady, boolean draining) {
            private static final Flags INITIAL = new Flags(false, false, false);

            private NodeReportedState toState() {
                return draining
                       ? NodeReportedState.DRAINING
                       : consensusActive && subsystemsReady
                         ? NodeReportedState.READY
                         : NodeReportedState.SYNCING;
            }

            private Flags withConsensusActive() {
                return new Flags(true, subsystemsReady, draining);
            }

            private Flags withConsensusPassive() {
                return new Flags(false, subsystemsReady, draining);
            }

            private Flags withSubsystemsReady() {
                return new Flags(consensusActive, true, draining);
            }

            private Flags withDraining() {
                return new Flags(consensusActive, subsystemsReady, true);
            }
        }
    }
}
