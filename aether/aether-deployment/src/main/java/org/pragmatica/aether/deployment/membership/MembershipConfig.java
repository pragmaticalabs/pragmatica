// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for the membership v2 stack (spec §14 — `membership-architecture-v2-spec.md`).
///
/// `nttDepartureTimeout` — NTT timer duration from SWIM `DepartedObserved` until
/// `TopologyUnhealthy` is emitted. Long enough to absorb SWIM convergence (~5s) and
/// brief network glitches; short enough that auto-heal feels prompt. Default: 15s.
///
/// `quorumLossDrainThreshold` — `localQuorumCount` must stay below `N/2+1`
/// continuously for at least this long before quorum-loss-triggered self-drain
/// commits. Preserves the S19 chaos-suite row. Default: 8s.
///
/// E2 Phase 2a (2026-05-28): the `nttObservation` migration-ramp feature flag is
/// removed. NTT/LocalQuorumWatcher/LeaderReconciler now wire unconditionally — the
/// observation-only ramp completed in Phase 1.6.
public record MembershipConfig(TimeSpan nttDepartureTimeout, TimeSpan quorumLossDrainThreshold) {
    public static MembershipConfig membershipConfig() {
        return new MembershipConfig(timeSpan(15).seconds(), timeSpan(8).seconds());
    }
}
