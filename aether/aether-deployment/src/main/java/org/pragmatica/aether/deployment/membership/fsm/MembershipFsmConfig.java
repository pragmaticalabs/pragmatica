// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.lang.io.TimeSpan;


/// Configuration values consumed by the cluster-membership FSM reducer and shadow wiring.
///
/// All durations are `TimeSpan` per the C.7 migration. The reducer reads only those values
/// it needs to populate side-effect descriptors (e.g., the `ScheduleTimer.delay` field for
/// the join-deadline timer). Wiring-time concerns (drain timeout, scheduler instance) live
/// on the wiring layer, not here.
///
/// `shadowEnabled` is the E.3 migration gate (spec §9, E.3). When `false` (default), the
/// shadow `MembershipFsm` does not run and `HealthReconciler` operates exactly as today —
/// zero behaviour change. When `true`, the shadow starts on node boot, reconstructs per-peer
/// state from KV, observes events, and logs the writes/effects it would have proposed. The
/// shadow never writes KV in E.3; that path lands in E.4.
public record MembershipFsmConfig(TimeSpan joinDeadline, boolean shadowEnabled) {
    public static final TimeSpan DEFAULT_JOIN_DEADLINE = TimeSpan.timeSpan(60).seconds();

    public static final boolean DEFAULT_SHADOW_ENABLED = false;

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline, boolean shadowEnabled) {
        return new MembershipFsmConfig(joinDeadline, shadowEnabled);
    }

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline) {
        return new MembershipFsmConfig(joinDeadline, DEFAULT_SHADOW_ENABLED);
    }

    public static MembershipFsmConfig defaultMembershipFsmConfig() {
        return new MembershipFsmConfig(DEFAULT_JOIN_DEADLINE, DEFAULT_SHADOW_ENABLED);
    }

    public MembershipFsmConfig withShadowEnabled(boolean shadowEnabled) {
        return new MembershipFsmConfig(joinDeadline, shadowEnabled);
    }
}
