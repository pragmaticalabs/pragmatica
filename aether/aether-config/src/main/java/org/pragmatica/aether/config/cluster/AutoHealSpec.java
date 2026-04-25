// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record AutoHealSpec(boolean enabled,
                           String retryInterval,
                           String startupCooldown,
                           String staleObservationTtl,
                           int quicMissPromotionThreshold,
                           String provisioningTimeout) {
    public static final String DEFAULT_STALE_OBSERVATION_TTL = "30s";

    public static final int DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD = 10;

    public static final String DEFAULT_PROVISIONING_TIMEOUT = "60s";

    public static AutoHealSpec autoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                DEFAULT_PROVISIONING_TIMEOUT);
    }

    public static AutoHealSpec autoHealSpec(boolean enabled,
                                             String retryInterval,
                                             String startupCooldown,
                                             String staleObservationTtl) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                staleObservationTtl,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                DEFAULT_PROVISIONING_TIMEOUT);
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
                                quicMissPromotionThreshold,
                                DEFAULT_PROVISIONING_TIMEOUT);
    }

    public static AutoHealSpec autoHealSpec(boolean enabled,
                                             String retryInterval,
                                             String startupCooldown,
                                             String staleObservationTtl,
                                             int quicMissPromotionThreshold,
                                             String provisioningTimeout) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                staleObservationTtl,
                                quicMissPromotionThreshold,
                                provisioningTimeout);
    }

    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true,
                                "60s",
                                "15s",
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                DEFAULT_PROVISIONING_TIMEOUT);
    }
}
