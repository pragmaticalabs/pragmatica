// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.lang.io.TimeSpan;


/// Every bound the node FSM applies, in milliseconds. The defaults derive from the live `SliceState` timeouts where one
/// exists, so a change to a shipped timeout moves the FSM with it; the rest are the spec's own numbers.
public record NodeFsmTimings(long loadMs,
                             long activateMs,
                             long deactivateMs,
                             long unloadMs,
                             long chainBoundMs,
                             long activatingCheckMs,
                             long tombstoneGraceMs,
                             long parkedBoundMs,
                             long writeAttemptMs,
                             int writeAttempts,
                             long failedRetryMs,
                             int failedCycles) {
    private static final long ACTIVATE_MS = 60_000L;
    private static final long CHAIN_BOUND_MS = 120_000L;
    private static final long TOMBSTONE_GRACE_MS = 120_000L;
    private static final long WRITE_ATTEMPT_MS = 30_000L;
    private static final int WRITE_ATTEMPTS = 3;
    private static final long FAILED_RETRY_MS = 2_000L;
    private static final int FAILED_CYCLES = 6;

    /// Spec defaults: load 2 min, activate 60 s, chain 120 s, deactivate 30 s, unload 2 min, ACTIVATING check 90 s,
    /// tombstone grace 120 s, parked-start escalation at the LOADING bound, 3 attempts of 30 s per write, 6 FAILED
    /// cycles 2 s apart.
    public static NodeFsmTimings nodeFsmTimings() {
        var load = of(SliceState.LOADING);

        return new NodeFsmTimings(load,
                                  ACTIVATE_MS,
                                  of(SliceState.DEACTIVATING),
                                  of(SliceState.UNLOADING),
                                  CHAIN_BOUND_MS,
                                  of(SliceState.ACTIVATING),
                                  TOMBSTONE_GRACE_MS,
                                  load,
                                  WRITE_ATTEMPT_MS,
                                  WRITE_ATTEMPTS,
                                  FAILED_RETRY_MS,
                                  FAILED_CYCLES);
    }

    /// The bound of a local step.
    public long boundOf(LocalStep step) {
        return switch (step) {
            case LOAD -> loadMs;
            case ACTIVATE -> activateMs;
            case DEACTIVATE -> deactivateMs;
            case UNLOAD, FORCED_CLEANUP -> unloadMs;
            default -> chainBoundMs;
        };
    }

    private static long of(SliceState state) {
        return state.timeout()
                    .map(TimeSpan::millis)
                    .or(CHAIN_BOUND_MS);
    }
}
