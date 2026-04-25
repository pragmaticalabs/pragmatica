// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for cluster auto-healing behavior.
///
/// @param retryInterval               interval between snapshot-driven safety-net polls when the
///                                    cluster is below target size (CTM also reacts immediately to
///                                    snapshot delta and topology change events)
/// @param startupCooldown             delay before first auto-heal check during initial cluster
///                                    formation, allowing all nodes time to join before provisioning
///                                    replacements
/// @param staleObservationTtl         maximum age of follower-produced peer observations
///                                    (`PeerHealthObservation` / `PeerConnectivityObservation`) accepted
///                                    by the leader's `HealthReconciler`. Observations whose
///                                    `producedAtMs` is older than `now - staleObservationTtl` at drain
///                                    time are dropped without affecting the snapshot. Defaults to 30s.
/// @param quicMissPromotionThreshold  number of consecutive QUIC ping-misses (recorded via
///                                    `peerObservationStore.recordPingMiss`) at which the leader's
///                                    `HealthReconciler` promotes a peer's `swimHints` entry to
///                                    `FAULTY` even if SWIM has not fired. Defense-in-depth: keeps
///                                    auto-heal viable when SWIM is delayed or wedged. Idempotent —
///                                    repeat promotions on subsequent misses are no-ops. Defaults to 10.
/// @param provisioningTimeout         per-slot deadline for in-flight CTM provisioning attempts.
///                                    When `Reconciling` dispatches a wave of N replacements it tracks
///                                    one slot per provision with `deadlineMs = now + provisioningTimeout`.
///                                    On every reconcile tick, expired slots are dropped and the deficit
///                                    is recomputed against `realActual + nonExpiredSlots`; if a stall
///                                    leaves the cluster below desired the next tick dispatches a top-up.
///                                    Sized to cover docker spawn + container start + consensus catch-up
///                                    + SWIM stabilize (tens of seconds). Defaults to 60s.
public record AutoHealConfig(TimeSpan retryInterval,
                              TimeSpan startupCooldown,
                              TimeSpan staleObservationTtl,
                              int quicMissPromotionThreshold,
                              TimeSpan provisioningTimeout) {
    public static final TimeSpan DEFAULT_STALE_OBSERVATION_TTL = timeSpan(30).seconds();

    public static final int DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD = 10;

    public static final TimeSpan DEFAULT_PROVISIONING_TIMEOUT = timeSpan(60).seconds();

    public static final AutoHealConfig DEFAULT = autoHealConfig(timeSpan(10).seconds(),
                                                                 timeSpan(15).seconds(),
                                                                 DEFAULT_STALE_OBSERVATION_TTL,
                                                                 DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                                 DEFAULT_PROVISIONING_TIMEOUT).unwrap();

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              DEFAULT_STALE_OBSERVATION_TTL,
                              DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                              DEFAULT_PROVISIONING_TIMEOUT);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                         TimeSpan startupCooldown,
                                                         TimeSpan staleObservationTtl) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              staleObservationTtl,
                              DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                              DEFAULT_PROVISIONING_TIMEOUT);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                         TimeSpan startupCooldown,
                                                         TimeSpan staleObservationTtl,
                                                         int quicMissPromotionThreshold) {
        return autoHealConfig(retryInterval,
                              startupCooldown,
                              staleObservationTtl,
                              quicMissPromotionThreshold,
                              DEFAULT_PROVISIONING_TIMEOUT);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                         TimeSpan startupCooldown,
                                                         TimeSpan staleObservationTtl,
                                                         int quicMissPromotionThreshold,
                                                         TimeSpan provisioningTimeout) {
        return success(new AutoHealConfig(retryInterval,
                                          startupCooldown,
                                          staleObservationTtl,
                                          quicMissPromotionThreshold,
                                          provisioningTimeout));
    }
}
