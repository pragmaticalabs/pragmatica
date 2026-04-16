// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ExecutionMode;


/// Configuration for a scheduled task, loaded from slice TOML.
///
/// Example configuration:
/// ```toml
/// [scheduling.cleanup]
/// interval = "5m"
/// executionMode = "SINGLE"
/// ```
///
/// @param interval fixed-rate interval (TimeSpan format); null if cron mode
/// @param cron standard 5-field cron expression; null if interval mode
/// @param executionMode how the task fires across the cluster (default: SINGLE)
public record ScheduleConfig(String interval, String cron, ExecutionMode executionMode) {
    public ScheduleConfig {
        if (interval == null) interval = "";
        if (cron == null) cron = "";
        if (executionMode == null) executionMode = ExecutionMode.SINGLE;
    }
}
