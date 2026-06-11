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
/// - [`#CONFIG_CHANGE`] — a KV-subscribed config change (`ClusterConfigKey` commit — scale
///   up/down, restore-to-N) was observed. Wired from `AetherNode`'s KV-notification router
///   to [`LeaderReconciler#onConfigChange`] (H1 / #257), so a config-driven target change
///   reconciles within the debounce window instead of waiting for unrelated SWIM churn.
/// - [`#DEFICIT_FOLLOW_UP`] — the reconciler's own deficit-convergence follow-up (H1 / #257
///   completion): a pass that ended with an UNRESOLVED confirmed-member deficit (whatever
///   the suppression reason — `WITHIN_DEBOUNCE`, `NOT_QUORUM_SAFE`, the cold-start latches,
///   or an in-flight-masked raw deficit) armed one deduped, debounce-spaced re-evaluation;
///   this trigger is that follow-up firing. Self-rearming until the confirmed-member count
///   converges to the configured target — a bounded convergence loop, NOT a periodic tick
///   (it arms only while a deficit exists and stops the moment it converges).
public enum ReconcileTrigger {
    LEADER_ACTIVATION,
    NTT_FIRE,
    QUORUM_LOSS,
    MEMBER_APPEARED,
    CONFIG_CHANGE,
    DEFICIT_FOLLOW_UP
}
