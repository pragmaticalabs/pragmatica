// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record HealthReconcilerConfig(TimeSpan aggregationWindow,
                                     TimeSpan cooldown,
                                     TimeSpan stableWindow,
                                     TimeSpan recoveryStableWindow,
                                     TimeSpan phaseEvaluationInterval) {
    /// Defaults (D.3, 2026-05-11):
    /// - `aggregationWindow=10s` matches SWIM `suspectTimeout` so per-target
    ///   sliding observation windows align with the failure detector.
    /// - `cooldown=5s` debounces back-to-back lifecycle writes for the same target.
    /// - `stableWindow=5s` is the COLD_BOOT → NORMAL dwell required after quorum.
    /// - `recoveryStableWindow=5s` is the RECOVERING → NORMAL dwell. Lowered from
    ///   the historical 30s to 5s per D.3 spec — operator-free recovery from sustained
    ///   chaos / compose-restart must complete on the same order as cold-boot stability;
    ///   the longer 30s window left cluster B integration tests waiting >180s for NORMAL
    ///   after destructive churn, defeating downstream NODE_FAILED detection.
    /// - `phaseEvaluationInterval=1s` periodic tick for re-evaluating phase
    ///   transitions on the leader. Pre-tick, `evaluatePhaseTransition` only fired on
    ///   SWIM observation events; after the cluster settles to all-Healthy steady state
    ///   no observations arrive (SWIM only emits on state changes), so even though
    ///   lifecycle KV had quorum ON_DUTY entries, COLD_BOOT → NORMAL never triggered.
    ///   The tick makes the transition deterministic ~`stableWindow + tick` after
    ///   quorum is reached. Set to 0ms (`timeSpan(0).millis()`) to disable (used in
    ///   tests with synchronous `immediateRetryScheduler` to avoid scheduling recursion).
    public static final HealthReconcilerConfig DEFAULT =
        new HealthReconcilerConfig(timeSpan(10).seconds(),
                                   timeSpan(5).seconds(),
                                   timeSpan(5).seconds(),
                                   timeSpan(5).seconds(),
                                   timeSpan(1).seconds());

    public static HealthReconcilerConfig healthReconcilerConfig() {
        return DEFAULT;
    }

    public static HealthReconcilerConfig healthReconcilerConfig(TimeSpan aggregationWindow,
                                                                TimeSpan cooldown,
                                                                TimeSpan stableWindow,
                                                                TimeSpan recoveryStableWindow,
                                                                TimeSpan phaseEvaluationInterval) {
        return new HealthReconcilerConfig(aggregationWindow,
                                          cooldown,
                                          stableWindow,
                                          recoveryStableWindow,
                                          phaseEvaluationInterval);
    }
}
