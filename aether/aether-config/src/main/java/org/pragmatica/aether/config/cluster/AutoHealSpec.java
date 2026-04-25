// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record AutoHealSpec(boolean enabled,
                           String retryInterval,
                           String startupCooldown,
                           String staleObservationTtl,
                           int quicMissPromotionThreshold) {
    public static final String DEFAULT_STALE_OBSERVATION_TTL = "30s";

    public static final int DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD = 10;

    public static AutoHealSpec autoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD);
    }

    public static AutoHealSpec autoHealSpec(boolean enabled,
                                             String retryInterval,
                                             String startupCooldown,
                                             String staleObservationTtl) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                staleObservationTtl,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD);
    }

    public static AutoHealSpec autoHealSpec(boolean enabled,
                                             String retryInterval,
                                             String startupCooldown,
                                             String staleObservationTtl,
                                             int quicMissPromotionThreshold) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                staleObservationTtl,
                                quicMissPromotionThreshold);
    }

    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true,
                                "60s",
                                "15s",
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD);
    }
}
