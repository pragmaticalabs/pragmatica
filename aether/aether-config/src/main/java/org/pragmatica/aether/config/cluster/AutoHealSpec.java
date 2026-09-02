// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.aether.config.ConfigKeyLive;


/// #675 tracks a duplicated-type gap: `Main.resolveAutoHeal` builds the runtime `AutoHealConfig` from
/// `AutoHealConfig.DEFAULT` plus a `max_nodes` cap, and never reads this parsed spec at all — so six of
/// this record's nine fields have no consumer anywhere outside `enabled()` (read by
/// `ClusterBootstrapConfigValidator`) and `retryInterval()`/`startupCooldown()` (forwarded, not
/// consumed, by `ClusterBootstrapConfigParser.autoHealFromShortcut`'s same-type defaulting chain).
/// `@ConfigKeyLive`-suppressed below rather than deleted: #675 owns the fix (wire these through, or
/// remove them), not #519's dead-surface guard — see #519 commissioning notes.
public record AutoHealSpec(boolean enabled,
                           String retryInterval,
                           String startupCooldown,
                           @ConfigKeyLive("#675: parsed but never read by Main.resolveAutoHeal") String staleObservationTtl,
                           @ConfigKeyLive("#675: parsed but never read by Main.resolveAutoHeal") int quicMissPromotionThreshold,
                           @ConfigKeyLive("#675: parsed but never read by Main.resolveAutoHeal") String provisioningTimeout,
                           @ConfigKeyLive("#675: parsed but never read by Main.resolveAutoHeal") String provisionStabilityWindow,
                           @ConfigKeyLive("#675: parsed but never read by Main.resolveAutoHeal") String decommissionedRetention,
                           @ConfigKeyLive("#675: parsed but never read by Main.resolveAutoHeal") String swimHintsTtl) {
    public static final String DEFAULT_STALE_OBSERVATION_TTL = "30s";
    public static final int DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD = 10;
    public static final String DEFAULT_PROVISIONING_TIMEOUT = "60s";
    public static final String DEFAULT_PROVISION_STABILITY_WINDOW = "30s";
    public static final String DEFAULT_DECOMMISSIONED_RETENTION = "24h";
    // Aligned with the SWIM/NTT detection window: SwimConfig.suspectTimeout (10s) and
    // MembershipConfig.nttDepartureTimeout (15s). Set to 15s so the hint is a short-lived
    // backstop, not a 60s stall: a still-degraded node is re-stamped by SWIM probe rounds
    // (period = 1s) well before expiry, while a recovered node's hint is cleared promptly
    // (now also actively cleared on PeerConnected — see SwimHealthState.promoteKnownMember).
    public static final String DEFAULT_SWIM_HINTS_TTL = "15s";

    public static AutoHealSpec autoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                DEFAULT_PROVISIONING_TIMEOUT,
                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                DEFAULT_DECOMMISSIONED_RETENTION,
                                DEFAULT_SWIM_HINTS_TTL);
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
                                DEFAULT_DECOMMISSIONED_RETENTION,
                                DEFAULT_SWIM_HINTS_TTL);
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
                                DEFAULT_DECOMMISSIONED_RETENTION,
                                DEFAULT_SWIM_HINTS_TTL);
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
                                DEFAULT_DECOMMISSIONED_RETENTION,
                                DEFAULT_SWIM_HINTS_TTL);
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
                                DEFAULT_DECOMMISSIONED_RETENTION,
                                DEFAULT_SWIM_HINTS_TTL);
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
                                decommissionedRetention,
                                DEFAULT_SWIM_HINTS_TTL);
    }

    public static AutoHealSpec autoHealSpec(boolean enabled,
                                            String retryInterval,
                                            String startupCooldown,
                                            String staleObservationTtl,
                                            int quicMissPromotionThreshold,
                                            String provisioningTimeout,
                                            String provisionStabilityWindow,
                                            String decommissionedRetention,
                                            String swimHintsTtl) {
        return new AutoHealSpec(enabled,
                                retryInterval,
                                startupCooldown,
                                staleObservationTtl,
                                quicMissPromotionThreshold,
                                provisioningTimeout,
                                provisionStabilityWindow,
                                decommissionedRetention,
                                swimHintsTtl);
    }

    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true,
                                "60s",
                                "15s",
                                DEFAULT_STALE_OBSERVATION_TTL,
                                DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                DEFAULT_PROVISIONING_TIMEOUT,
                                DEFAULT_PROVISION_STABILITY_WINDOW,
                                DEFAULT_DECOMMISSIONED_RETENTION,
                                DEFAULT_SWIM_HINTS_TTL);
    }
}
