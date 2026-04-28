// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record RollbackConfig(boolean enabled,
                             boolean triggerOnAllInstancesFailed,
                             TimeSpan cooldown,
                             int maxRollbacks) {
    public static Result<RollbackConfig> rollbackConfig(boolean enabled,
                                                        boolean triggerOnAllInstancesFailed,
                                                        TimeSpan cooldown,
                                                        int maxRollbacks) {
        return success(new RollbackConfig(enabled, triggerOnAllInstancesFailed, cooldown, maxRollbacks));
    }

    private static final RollbackConfig ENABLED = rollbackConfig(true, true, timeSpan(5).minutes(), 2).unwrap();

    private static final RollbackConfig DISABLED = rollbackConfig(false, false, timeSpan(0).millis(), 0).unwrap();

    public static RollbackConfig rollbackConfig() {
        return ENABLED;
    }

    public static RollbackConfig rollbackConfig(boolean enabled) {
        return enabled
              ? ENABLED
              : DISABLED;
    }
}
