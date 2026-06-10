// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record AutoHealConfig(TimeSpan retryInterval,
                             TimeSpan startupCooldown,
                             TimeSpan staleObservationTtl,
                             int quicMissPromotionThreshold,
                             TimeSpan provisioningTimeout,
                             TimeSpan provisionStabilityWindow,
                             TimeSpan decommissionedRetention,
                             TimeSpan swimHintsTtl) {
    public static final TimeSpan DEFAULT_STALE_OBSERVATION_TTL = timeSpan(30).seconds();
    public static final int DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD = 10;
    public static final TimeSpan DEFAULT_PROVISIONING_TIMEOUT = timeSpan(60).seconds();
    public static final TimeSpan DEFAULT_PROVISION_STABILITY_WINDOW = timeSpan(30).seconds();
    public static final TimeSpan DEFAULT_DECOMMISSIONED_RETENTION = timeSpan(60).seconds();
    // Aligned with the SWIM/NTT detection window: SwimConfig.suspectTimeout (10s) and
    // MembershipConfig.nttDepartureTimeout (15s). This is the value that actually reaches the
    // running SwimHintsRegistry (AetherNode wires config.autoHeal().swimHintsTtl()); lowered
    // from 60s so the hint backstop is short-lived. Kept in sync with AutoHealSpec.
    public static final TimeSpan DEFAULT_SWIM_HINTS_TTL = timeSpan(15).seconds();

    public static final AutoHealConfig DEFAULT = autoHealConfig(timeSpan(10).seconds(),
                                                                timeSpan(15).seconds(),
                                                                DEFAULT_STALE_OBSERVATION_TTL,
                                                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                                DEFAULT_PROVISIONING_TIMEOUT,
                                                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                                                DEFAULT_DECOMMISSIONED_RETENTION,
                                                                DEFAULT_SWIM_HINTS_TTL).unwrap();

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              DEFAULT_STALE_OBSERVATION_TTL,
                              DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                              DEFAULT_PROVISIONING_TIMEOUT,
                              DEFAULT_PROVISION_STABILITY_WINDOW,
                              DEFAULT_DECOMMISSIONED_RETENTION,
                              DEFAULT_SWIM_HINTS_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                        TimeSpan startupCooldown,
                                                        TimeSpan staleObservationTtl) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              staleObservationTtl,
                              DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                              DEFAULT_PROVISIONING_TIMEOUT,
                              DEFAULT_PROVISION_STABILITY_WINDOW,
                              DEFAULT_DECOMMISSIONED_RETENTION,
                              DEFAULT_SWIM_HINTS_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                        TimeSpan startupCooldown,
                                                        TimeSpan staleObservationTtl,
                                                        int quicMissPromotionThreshold) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              staleObservationTtl,
                              quicMissPromotionThreshold,
                              DEFAULT_PROVISIONING_TIMEOUT,
                              DEFAULT_PROVISION_STABILITY_WINDOW,
                              DEFAULT_DECOMMISSIONED_RETENTION,
                              DEFAULT_SWIM_HINTS_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                        TimeSpan startupCooldown,
                                                        TimeSpan staleObservationTtl,
                                                        int quicMissPromotionThreshold,
                                                        TimeSpan provisioningTimeout) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              staleObservationTtl,
                              quicMissPromotionThreshold,
                              provisioningTimeout,
                              DEFAULT_PROVISION_STABILITY_WINDOW,
                              DEFAULT_DECOMMISSIONED_RETENTION,
                              DEFAULT_SWIM_HINTS_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                        TimeSpan startupCooldown,
                                                        TimeSpan staleObservationTtl,
                                                        int quicMissPromotionThreshold,
                                                        TimeSpan provisioningTimeout,
                                                        TimeSpan provisionStabilityWindow) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              staleObservationTtl,
                              quicMissPromotionThreshold,
                              provisioningTimeout,
                              provisionStabilityWindow,
                              DEFAULT_DECOMMISSIONED_RETENTION,
                              DEFAULT_SWIM_HINTS_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                        TimeSpan startupCooldown,
                                                        TimeSpan staleObservationTtl,
                                                        int quicMissPromotionThreshold,
                                                        TimeSpan provisioningTimeout,
                                                        TimeSpan provisionStabilityWindow,
                                                        TimeSpan decommissionedRetention) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              staleObservationTtl,
                              quicMissPromotionThreshold,
                              provisioningTimeout,
                              provisionStabilityWindow,
                              decommissionedRetention,
                              DEFAULT_SWIM_HINTS_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                        TimeSpan startupCooldown,
                                                        TimeSpan staleObservationTtl,
                                                        int quicMissPromotionThreshold,
                                                        TimeSpan provisioningTimeout,
                                                        TimeSpan provisionStabilityWindow,
                                                        TimeSpan decommissionedRetention,
                                                        TimeSpan swimHintsTtl) {
        return success(new AutoHealConfig(retryInterval,
                                          startupCooldown,
                                          staleObservationTtl,
                                          quicMissPromotionThreshold,
                                          provisioningTimeout,
                                          provisionStabilityWindow,
                                          decommissionedRetention,
                                          swimHintsTtl));
    }
}
