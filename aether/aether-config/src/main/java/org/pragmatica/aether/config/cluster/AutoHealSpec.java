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
                           String provisioningTimeout,
                           String provisionStabilityWindow,
                           String decommissionedRetention) {
    public static final String DEFAULT_STALE_OBSERVATION_TTL = "30s";

    public static final int DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD = 10;

    public static final String DEFAULT_PROVISIONING_TIMEOUT = "60s";

    public static final String DEFAULT_PROVISION_STABILITY_WINDOW = "30s";

    /// Theme K #4: maximum age of `NodeLifecycleValue(state == DECOMMISSIONED)` atoms before
    /// the leader-side periodic GC removes them. Default 24h aligns with operator audit
    /// windows and keeps tombstones visible for at least one ops cycle while preventing
    /// unbounded accumulation over the cluster's lifetime.
    public static final String DEFAULT_DECOMMISSIONED_RETENTION = "24h";

    public static AutoHealSpec autoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                DEFAULT_PROVISIONING_TIMEOUT,
                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                DEFAULT_DECOMMISSIONED_RETENTION);
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
                                DEFAULT_PROVISIONING_TIMEOUT,
                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                DEFAULT_DECOMMISSIONED_RETENTION);
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
                                DEFAULT_PROVISIONING_TIMEOUT,
                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                DEFAULT_DECOMMISSIONED_RETENTION);
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
                                provisioningTimeout,
                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                DEFAULT_DECOMMISSIONED_RETENTION);
    }

    public static AutoHealSpec autoHealSpec(boolean enabled,
                                             String retryInterval,
                                             String startupCooldown,
                                             String staleObservationTtl,
                                             int quicMissPromotionThreshold,
                                             String provisioningTimeout,
                                             String provisionStabilityWindow) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                staleObservationTtl,
                                quicMissPromotionThreshold,
                                provisioningTimeout,
                                provisionStabilityWindow,
                                DEFAULT_DECOMMISSIONED_RETENTION);
    }

    public static AutoHealSpec autoHealSpec(boolean enabled,
                                             String retryInterval,
                                             String startupCooldown,
                                             String staleObservationTtl,
                                             int quicMissPromotionThreshold,
                                             String provisioningTimeout,
                                             String provisionStabilityWindow,
                                             String decommissionedRetention) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                staleObservationTtl,
                                quicMissPromotionThreshold,
                                provisioningTimeout,
                                provisionStabilityWindow,
                                decommissionedRetention);
    }

    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true,
                                "60s",
                                "15s",
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                DEFAULT_PROVISIONING_TIMEOUT,
                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                DEFAULT_DECOMMISSIONED_RETENTION);
    }
}
