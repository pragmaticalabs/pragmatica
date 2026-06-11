// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for the membership v2 stack (spec §14 — `membership-architecture-v2-spec.md`).
///
/// **One split timeout `T` (cluster-topology-overhaul Wave 9, item 2).** The former separate
/// `nttDepartureTimeout` (majority departure-verdict / re-provision debounce) and
/// `quorumLossDrainThreshold` (minority quorum-loss self-drain) knobs are collapsed into a
/// single shared knob `splitTimeout` (`T`). Both the minority's quorum-loss self-drain AND the
/// majority's departure-verdict / re-provision are now governed by this one value.
///
/// **No-double-active ordering requirement (preserved by wiring, not by a second knob).** The
/// minority MUST stop before the majority re-provisions (stateful slices must never be doubly
/// active). The ordering margin is the natural detection lag: each side measures `T` from its
/// OWN observation point — the minority observes its quorum loss LOCALLY and immediately, while
/// the majority only confirms the minority's departure AFTER SWIM convergence (~5s) + the
/// co-confirmation gate. So `T_minority_self_drain` (timed from local-quorum-loss) elapses
/// before `T_majority_reprovision` (timed from the departure-verdict) even with the SAME `T`.
/// `QuorumLossDetector` is fed `splitTimeout()` and arms from local-quorum-loss; the
/// `LeaderReconciler` deficit/quiesce/drain windows derive from `splitTimeout()` and arm from
/// the departure-verdict. `MembershipConfigSplitTimeoutOrderingTest` asserts the minority deadline
/// precedes the majority re-provision deadline for the same `T`.
///
/// `splitTimeout` default: 15s — long enough to absorb SWIM convergence (~5s) and brief
/// network glitches; short enough that auto-heal feels prompt. The #131 terminal-eviction
/// backstop window is sourced from the same `T`.
public record MembershipConfig(TimeSpan splitTimeout) {
    public static final TimeSpan DEFAULT_SPLIT_TIMEOUT = timeSpan(15).seconds();

    public static MembershipConfig membershipConfig() {
        return new MembershipConfig(DEFAULT_SPLIT_TIMEOUT);
    }
}
