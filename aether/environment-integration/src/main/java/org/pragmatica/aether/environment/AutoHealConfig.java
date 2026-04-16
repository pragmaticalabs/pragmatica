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
/// @param retryInterval    interval between provisioning attempts when cluster is below target size
/// @param startupCooldown  delay before first auto-heal check during initial cluster formation,
///                         allowing all nodes time to join before provisioning replacements
public record AutoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown) {
    public static final AutoHealConfig DEFAULT = autoHealConfig(timeSpan(10).seconds(), timeSpan(15).seconds()).unwrap();

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan retryInterval, TimeSpan startupCooldown) {
        return success(new AutoHealConfig(retryInterval, startupCooldown));
    }
}
