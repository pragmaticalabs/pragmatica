// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.lang.io.TimeSpan;


/// Configuration values consumed by the cluster-membership FSM reducer.
///
/// All durations are `TimeSpan` per the C.7 migration. The reducer reads only those values
/// it needs to populate side-effect descriptors (e.g., the `ScheduleTimer.delay` field for
/// the join-deadline timer). Wiring-time concerns (drain timeout, scheduler instance) live
/// on the wiring layer, not here.
public record MembershipFsmConfig(TimeSpan joinDeadline) {
    public static final TimeSpan DEFAULT_JOIN_DEADLINE = TimeSpan.timeSpan(60).seconds();

    public static MembershipFsmConfig membershipFsmConfig(TimeSpan joinDeadline) {
        return new MembershipFsmConfig(joinDeadline);
    }

    public static MembershipFsmConfig defaultMembershipFsmConfig() {
        return new MembershipFsmConfig(DEFAULT_JOIN_DEADLINE);
    }
}
