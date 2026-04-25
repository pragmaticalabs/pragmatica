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
/// @param retryInterval         interval between snapshot-driven safety-net polls when the
///                              cluster is below target size (CTM also reacts immediately to
///                              snapshot delta and topology change events)
/// @param startupCooldown       delay before first auto-heal check during initial cluster
///                              formation, allowing all nodes time to join before provisioning
///                              replacements
/// @param staleObservationTtl   maximum age of follower-produced peer observations
///                              (`PeerHealthObservation` / `PeerConnectivityObservation`) accepted
///                              by the leader's `HealthReconciler`. Observations whose
///                              `producedAtMs` is older than `now - staleObservationTtl` at drain
///                              time are dropped without affecting the snapshot. Defaults to 30s.
public record AutoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown, TimeSpan staleObservationTtl) {
    public static final TimeSpan DEFAULT_STALE_OBSERVATION_TTL = timeSpan(30).seconds();

    public static final AutoHealConfig DEFAULT = autoHealConfig(timeSpan(10).seconds(),
                                                                 timeSpan(15).seconds(),
                                                                 DEFAULT_STALE_OBSERVATION_TTL).unwrap();

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown) {
        return autoHealConfig(retryInterval, startupCooldown, DEFAULT_STALE_OBSERVATION_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                         TimeSpan startupCooldown,
                                                         TimeSpan staleObservationTtl) {
        return success(new AutoHealConfig(retryInterval, startupCooldown, staleObservationTtl));
    }
}
