// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// When [SegmentSealer] seals a partition's run of queued evictions, and how large one block may grow (spec 4.2, #2201
/// C-02). A run is sealed once its queued bytes reach `runBytes`, OR its queued events reach `runEvents`, OR its oldest
/// entry has waited `runAge`; a backlog that queued behind an in-flight seal is merged into one block of at most
/// `mergeBytes` (a single entry larger than that still seals alone). The node keys are `[streaming] seal_run_bytes`,
/// `seal_run_events`, `seal_run_age` and `seal_merge_bytes`.
///
/// The defaults mirror `StreamingConfig`'s, duplicated as literals so this module keeps no dependency on
/// `aether-config`. ALL FOUR ARE GUESSES (spec 4.3): what would settle them is the seal rate and WAL size trend of the
/// workload-1 baseline run.
public record SealerThresholds(long runBytes, int runEvents, TimeSpan runAge, long mergeBytes) {
    public static final long DEFAULT_RUN_BYTES = 1024 * 1024L;
    public static final int DEFAULT_RUN_EVENTS = 4096;
    public static final TimeSpan DEFAULT_RUN_AGE = timeSpan(5).seconds();
    public static final long DEFAULT_MERGE_BYTES = 4 * 1024 * 1024L;

    public static SealerThresholds sealerThresholds(long runBytes, int runEvents, TimeSpan runAge, long mergeBytes) {
        return new SealerThresholds(runBytes, runEvents, runAge, mergeBytes);
    }

    public static SealerThresholds defaults() {
        return sealerThresholds(DEFAULT_RUN_BYTES, DEFAULT_RUN_EVENTS, DEFAULT_RUN_AGE, DEFAULT_MERGE_BYTES);
    }

    /// Every eviction batch is ready at once and nothing is merged: one sealed segment per batch, the sealer's behaviour
    /// before runs existed. For tests that pin that granularity.
    public static SealerThresholds immediate() {
        return sealerThresholds(0, 0, timeSpan(0).nanos(), 0);
    }
}
