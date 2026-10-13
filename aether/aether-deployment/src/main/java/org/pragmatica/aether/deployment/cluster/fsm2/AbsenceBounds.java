// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.lang.io.TimeSpan;


/// The absence bounds of the departure backstop (spec R-2.14, R-2.14b, R-2.14c) and the core suspicion window (R-2.11 (b)), in
/// nanoseconds of the leader-local monotonic clock. They are derived from the live configuration, never hard-coded:
///
/// - `coreRemoval` (`B_rm`) = `suspectTimeout x lhmMaxScore x MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER + splitTimeout`;
/// - `workerRemoval` (`B_w`) = `workerSuspectTimeout x lhmMaxScore x MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER
///   + reannounceInterval + communityAbsence`;
/// - `announcementFreshness` (`S_ann`) = `2 x reannounceInterval`;
/// - `coreSuspicion` = `suspectTimeout x lhmMaxScore x MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER`, the upper bound of one SWIM suspicion
///   window (R-2.14): how long a core must stay listed in this leader's core view to count as alive `[guess: one window, per the D-4 (ii) ruling]`.
public record AbsenceBounds(long coreRemoval, long workerRemoval, long announcementFreshness, long coreSuspicion) {
    public static AbsenceBounds absenceBounds(long coreRemovalNs,
                                              long workerRemovalNs,
                                              long announcementFreshnessNs,
                                              long coreSuspicionNs) {
        return new AbsenceBounds(coreRemovalNs, workerRemovalNs, announcementFreshnessNs, coreSuspicionNs);
    }

    /// R-2.14 / R-2.14b / R-2.14c from the configured terms.
    public static AbsenceBounds derive(TimeSpan suspectTimeout,
                                       TimeSpan workerSuspectTimeout,
                                       int lhmMaxScore,
                                       double clusterSizeWindowMultiplier,
                                       TimeSpan splitTimeout,
                                       TimeSpan reannounceInterval,
                                       TimeSpan communityAbsence) {
        var window = lhmMaxScore * clusterSizeWindowMultiplier;
        var coreSuspicion = (long)(suspectTimeout.nanos() * window);
        var coreRemoval = coreSuspicion + splitTimeout.nanos();
        var workerRemoval = (long)(workerSuspectTimeout.nanos() * window) + reannounceInterval.nanos() + communityAbsence.nanos();

        return new AbsenceBounds(coreRemoval, workerRemoval, 2 * reannounceInterval.nanos(), coreSuspicion);
    }
}
