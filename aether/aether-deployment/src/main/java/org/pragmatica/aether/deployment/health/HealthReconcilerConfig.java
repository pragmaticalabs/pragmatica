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

    /// Defaults: 5s aggregation, 5s cooldown, 5s stable, 30s recovery-stable.
    /// `cooldownMs` lowered from 10s to 5s to remove the dominant suppression hop
    /// in the post-SWIM detection chain — at 10s the cooldown collided with leader
    /// phase-transition writes and silently deferred FAULTY commits past the 60s
    /// integration-test SLO. 5s preserves flap-protection (slower than the SWIM
    /// suspect window itself, so revival races stale observations safely).
    public static final HealthReconcilerConfig DEFAULT = new HealthReconcilerConfig(5_000L, 5_000L, 5_000L, 30_000L);

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
