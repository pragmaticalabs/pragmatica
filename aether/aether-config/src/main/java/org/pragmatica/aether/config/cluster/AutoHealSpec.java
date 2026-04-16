// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record AutoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
    public static AutoHealSpec autoHealSpec(boolean enabled, String retryInterval, String startupCooldown) {
        return new AutoHealSpec(enabled, retryInterval, startupCooldown);
    }

    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true, "60s", "15s");
    }
}
