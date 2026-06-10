// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// Trigger classification for a [`ReconcileIntent`] emission (spec §7.4 E2 Phase 1.5
/// "fully state-derived reconcile"). All paths converge on a single idempotent CAS-
/// debounced `triggerReconcile` call in [`LeaderReconciler`]:
///
/// - [`#LEADER_ACTIVATION`] — leader gained; reconcile is scheduled as a single one-shot
///   pass after `nttDepartureTimeout × 1.5` so SWIM gossip + QUIC connections quiesce
///   before reconciling. No immediate reconcile is emitted.
/// - [`#NTT_FIRE`] — a per-peer NTT departure timer expired (replaces `NTT_DRAIN`; the
///   reconcile is now state-derived so a single fire — not a map drain — is the unit).
/// - [`#QUORUM_LOSS`] — a [`QuorumLossIntent`] arrived from [`LocalQuorumWatcher`].
/// - [`#MEMBER_APPEARED`] — a SWIM `HealthyObserved` was emitted (a peer became reachable);
///   catches the "surplus appeared" case symmetrically with NTT catching shortage.
/// - [`#CONFIG_CHANGE`] — a KV-subscribed config change (e.g., `coreCount`) was observed.
///   Phase 1.5 wires the entry point; Phase 2 hooks the actual subscription.
public enum ReconcileTrigger {
    LEADER_ACTIVATION,
    NTT_FIRE,
    QUORUM_LOSS,
    MEMBER_APPEARED,
    CONFIG_CHANGE
}
