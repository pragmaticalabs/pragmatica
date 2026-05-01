// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

public record HealthReconcilerConfig(long aggregationWindowMs,
                                     long cooldownMs,
                                     long stableWindowMs,
                                     long recoveryStableWindowMs) {
    public static final HealthReconcilerConfig DEFAULT = new HealthReconcilerConfig(5_000L, 10_000L, 5_000L, 30_000L);

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
