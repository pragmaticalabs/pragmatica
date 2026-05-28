// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;


/// Trigger classification for a [`ReconcileIntent`] emission (spec §7.4 "hybrid
/// reconciliation triggers"). The four converging paths into the leader-pinned
/// reconcile loop:
///
/// - [`#LEADER_ACTIVATION`] — drained NTT entries on leader gain + an initial backstop
///   reconciliation tick fired synchronously by [`LeaderReconciler#activate()`].
/// - [`#NTT_DRAIN`] — a live [`TopologyUnhealthyEvent`] arrived while this node is the
///   leader (Stage 6 will adapt these from NTT's claim/fire path).
/// - [`#QUORUM_LOSS`] — a [`QuorumLossIntent`] arrived from [`LocalQuorumWatcher`].
///   Emitted on every node (Stage 6 wiring); only the leader's reconciler acts on it.
/// - [`#PERIODIC_TICK`] — the leader-pinned scheduled tick at `provisioningTimeout × 1.5`.
public enum ReconcileTrigger {
    LEADER_ACTIVATION,
    NTT_DRAIN,
    QUORUM_LOSS,
    PERIODIC_TICK
}
