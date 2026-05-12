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
public record MembershipFsmConfig(TimeSpan joinDeadline,
                                  TimeSpan drainTimeout,
                                  TimeSpan decommissionedRevivalTtl,
                                  TimeSpan decommissionedSwimRefractory) {
    public static final TimeSpan DEFAULT_JOIN_DEADLINE = TimeSpan.timeSpan(60).seconds();

    /// Drain hard-deadline (spec §8). The leader's FSM calls
    /// `DrainCoordinator.awaitDrainAck(peer, drainTimeout)` after entering DRAINING; if the
    /// coordinator does not resolve in this window, the FSM feeds back
    /// `DrainOutcome(peer, success=false)` which drives the `(DRAINING, DrainOutcome(false))
    /// → FAILED_DRAIN` transition.
    public static final TimeSpan DEFAULT_DRAIN_TIMEOUT = TimeSpan.timeSpan(60).seconds();

    /// TTL window during which a peer in `DECOMMISSIONED` may be revived to `ON_DUTY` by
    /// a `SwimHealthy` observation (Bootstrap-correction 2026-05-12; spec §5.1 note 4).
    /// A `DECOMMISSIONED` entry younger than this TTL is treated as a recently-decommissioned
    /// peer that has returned (e.g. elastic-test-fixture restart, short transient
    /// decommission); the FSM admits it back. An older entry stays zombie and requires an
    /// operator to explicitly clear it. Default: 60 seconds.
    public static final TimeSpan DEFAULT_DECOMMISSIONED_REVIVAL_TTL = TimeSpan.timeSpan(60).seconds();

    /// Refractory window blocking SWIM-Healthy revival of a peer decommissioned via SWIM
    /// failure detection (`SwimFaulty`/`SwimDeparted`). Applies only when
    /// `Decommissioned.swimDriven == true`. Counters the chaos-test revival storm where
    /// stale SWIM gossip / QUIC reconnect for a just-killed peer would otherwise re-fire
    /// `(DECOMMISSIONED, SwimHealthy) → ON_DUTY` within the TTL — leader-storm self-bootstrap
    /// during chaos can synthesize Healthy observations for peers SWIM hasn't fully purged.
    /// Operator-driven decommissions (`force=true`, drain success) ignore this refractory and
    /// remain eligible for fast-restart revival within the TTL. Default: 30 seconds —
    /// comfortably exceeds the SWIM detection latency (10-15s) plus a buffer for gossip
    /// drainage.
    public static final TimeSpan DEFAULT_DECOMMISSIONED_SWIM_REFRACTORY = TimeSpan.timeSpan(30).seconds();

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline,
                                                          TimeSpan drainTimeout,
                                                          TimeSpan decommissionedRevivalTtl) {
        return new MembershipFsmConfig(joinDeadline,
                                       drainTimeout,
                                       decommissionedRevivalTtl,
                                       DEFAULT_DECOMMISSIONED_SWIM_REFRACTORY);
    }

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline, TimeSpan drainTimeout) {
        return new MembershipFsmConfig(joinDeadline,
                                       drainTimeout,
                                       DEFAULT_DECOMMISSIONED_REVIVAL_TTL,
                                       DEFAULT_DECOMMISSIONED_SWIM_REFRACTORY);
    }

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline) {
        return new MembershipFsmConfig(joinDeadline,
                                       DEFAULT_DRAIN_TIMEOUT,
                                       DEFAULT_DECOMMISSIONED_REVIVAL_TTL,
                                       DEFAULT_DECOMMISSIONED_SWIM_REFRACTORY);
    }

    public static MembershipFsmConfig defaultMembershipFsmConfig() {
        return new MembershipFsmConfig(DEFAULT_JOIN_DEADLINE,
                                       DEFAULT_DRAIN_TIMEOUT,
                                       DEFAULT_DECOMMISSIONED_REVIVAL_TTL,
                                       DEFAULT_DECOMMISSIONED_SWIM_REFRACTORY);
    }

    public MembershipFsmConfig withDrainTimeout(TimeSpan drainTimeout) {
        return new MembershipFsmConfig(joinDeadline,
                                       drainTimeout,
                                       decommissionedRevivalTtl,
                                       decommissionedSwimRefractory);
    }

    public MembershipFsmConfig withDecommissionedRevivalTtl(TimeSpan decommissionedRevivalTtl) {
        return new MembershipFsmConfig(joinDeadline,
                                       drainTimeout,
                                       decommissionedRevivalTtl,
                                       decommissionedSwimRefractory);
    }

    public MembershipFsmConfig withDecommissionedSwimRefractory(TimeSpan decommissionedSwimRefractory) {
        return new MembershipFsmConfig(joinDeadline,
                                       drainTimeout,
                                       decommissionedRevivalTtl,
                                       decommissionedSwimRefractory);
    }
}
