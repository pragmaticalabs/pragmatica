// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ExecutionMode;


/// A `[scheduling.<name>]` section. A schedule sets `interval` OR `cron`, so the binder must be able to
/// supply whichever key is absent: it does that only from a `DEFAULT` field (#1438). Without one, the
/// documented interval-only and cron-only shapes failed to bind, and the task was never published. An
/// empty section still fails loudly, at activation, where the empty schedule string is refused.
public record ScheduleConfig(String interval, String cron, ExecutionMode executionMode) {
    public static final ScheduleConfig DEFAULT = new ScheduleConfig("", "", ExecutionMode.SINGLE);

    public ScheduleConfig {
        if (interval == null) interval = "";

        if (cron == null) cron = "";

        if (executionMode == null) executionMode = ExecutionMode.SINGLE;
    }
}
