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
/// @param retryInterval     interval between provisioning attempts when cluster is below target size
/// @param startupCooldown   delay before first auto-heal check during initial cluster formation,
///                          allowing all nodes time to join before provisioning replacements
/// @param deficitHysteresis delay between detecting a deficit and actually calling
///                          `ComputeProvider.provision`. Absorbs transient QUIC flaps where a peer
///                          briefly disconnects and reconnects — if healthy count recovers within
///                          this window, provisioning is skipped entirely. Too-long lets tests
///                          observe a "degraded" interim state; too-short churns the cluster on
///                          every transient network blip.
public record AutoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown, TimeSpan deficitHysteresis) {
    public static final AutoHealConfig DEFAULT = autoHealConfig(timeSpan(10).seconds(),
                                                                timeSpan(15).seconds(),
                                                                timeSpan(3).seconds()).unwrap();

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval,
                                                         TimeSpan startupCooldown,
                                                         TimeSpan deficitHysteresis) {
        return success(new AutoHealConfig(retryInterval, startupCooldown, deficitHysteresis));
    }
}
