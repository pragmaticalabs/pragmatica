// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

/// Counts the iterations of the maps of ONE model lineage (spec R-7.3.1, NT-78). It is owned by the model that created it and shared
/// by that model's copies, never by the process: two FSM instances, or two tests in one JVM, cannot see each other's scans. `step`
/// reads it before and after a handler and reports the difference as the `aether_deployment_fsm_scans_total` metric effect, so the
/// count leaves the FSM as data.
public final class ScanMeter {
    /// The meter of maps that belong to no model: it records nothing.
    public static final ScanMeter NONE = new ScanMeter(false);

    private final boolean enabled;
    private long count;

    private ScanMeter(boolean enabled) {
        this.enabled = enabled;
    }

    public static ScanMeter scanMeter() {
        return new ScanMeter(true);
    }

    long count() {
        return count;
    }

    ScanMeter record() {
        if (enabled) {
            count = count + 1;
        }

        return this;
    }
}
