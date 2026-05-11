// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

public record HealthReconcilerConfig(long aggregationWindowMs,
                                     long cooldownMs,
                                     long stableWindowMs,
                                     long recoveryStableWindowMs) {
    public HealthReconcilerConfig {
        aggregationWindowMs = Math.max(1L, aggregationWindowMs);
        cooldownMs = Math.max(1L, cooldownMs);
        stableWindowMs = Math.max(1L, stableWindowMs);
        recoveryStableWindowMs = Math.max(1L, recoveryStableWindowMs);
    }

    /// Defaults (D.3, 2026-05-11):
    /// - `aggregationWindowMs=10_000L` matches SWIM `suspectTimeout` so per-target
    ///   sliding observation windows align with the failure detector.
    /// - `cooldownMs=5_000L` debounces back-to-back lifecycle writes for the same target.
    /// - `stableWindowMs=5_000L` is the COLD_BOOT → NORMAL dwell required after quorum.
    /// - `recoveryStableWindowMs=5_000L` is the RECOVERING → NORMAL dwell. Lowered from
    ///   the historical 30s to 5s per D.3 spec — operator-free recovery from sustained
    ///   chaos / compose-restart must complete on the same order as cold-boot stability;
    ///   the longer 30s window left cluster B integration tests waiting >180s for NORMAL
    ///   after destructive churn, defeating downstream NODE_FAILED detection.
    public static final HealthReconcilerConfig DEFAULT = new HealthReconcilerConfig(10_000L, 5_000L, 5_000L, 5_000L);

    public static HealthReconcilerConfig healthReconcilerConfig() {
        return DEFAULT;
    }

    public static HealthReconcilerConfig healthReconcilerConfig(long aggregationWindowMs,
                                                                long cooldownMs,
                                                                long stableWindowMs,
                                                                long recoveryStableWindowMs) {
        return new HealthReconcilerConfig(aggregationWindowMs, cooldownMs, stableWindowMs, recoveryStableWindowMs);
    }
}
