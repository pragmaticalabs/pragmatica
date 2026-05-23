// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.lang.io.TimeSpan;


/// Configuration values consumed by the cluster-membership FSM reducer and wiring.
///
/// All durations are `TimeSpan` per the C.7 migration. The reducer reads only those values
/// it needs to populate side-effect descriptors (e.g., the `ScheduleTimer.delay` field for
/// the join-deadline timer). Wiring-time concerns (drain timeout, scheduler instance) live
/// on the wiring layer, not here.
///
/// Post-E.8 (spec §9): The FSM is always active (no feature flag).
/// Post-H.4 (spec §H): the `(DECOMMISSIONED, SwimHealthy)` revival path is removed; the
/// associated refractory + revival-TTL config fields are gone with it.
///
/// **TODO Phase 4 (cluster-convergence-reconciler) — `[reconciler.holds]` section.** The
/// per-snapshot sync-hold configuration (`min_hold_ms`, `max_hold_ms`, `expected_sync_bps`)
/// currently lives on `org.pragmatica.cluster.node.rabia.SyncHoldConfig` (defaults: 5s/60s/
/// 10 MB/s). When `ReconcilerConfig` lands in Phase 4 with `[reconciler.rules]` and
/// `[reconciler.holds]`, `SyncHoldConfig` should be plumbed from the parsed TOML section
/// rather than the static `defaults()` factory.
public record MembershipFsmConfig(TimeSpan joinDeadline, TimeSpan drainTimeout) {
    public static final TimeSpan DEFAULT_JOIN_DEADLINE = TimeSpan.timeSpan(60).seconds();

    /// Drain hard-deadline (spec §8). The leader's FSM calls
    /// `DrainCoordinator.awaitDrainAck(peer, drainTimeout)` after entering DRAINING; if the
    /// coordinator does not resolve in this window, the FSM feeds back
    /// `DrainOutcome(peer, success=false)` which drives the `(DRAINING, DrainOutcome(false))
    /// → FAILED_DRAIN` transition.
    public static final TimeSpan DEFAULT_DRAIN_TIMEOUT = TimeSpan.timeSpan(60).seconds();

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline, TimeSpan drainTimeout) {
        return new MembershipFsmConfig(joinDeadline, drainTimeout);
    }

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline) {
        return new MembershipFsmConfig(joinDeadline, DEFAULT_DRAIN_TIMEOUT);
    }

    public static MembershipFsmConfig defaultMembershipFsmConfig() {
        return new MembershipFsmConfig(DEFAULT_JOIN_DEADLINE, DEFAULT_DRAIN_TIMEOUT);
    }

    public MembershipFsmConfig withDrainTimeout(TimeSpan drainTimeout) {
        return new MembershipFsmConfig(joinDeadline, drainTimeout);
    }
}
