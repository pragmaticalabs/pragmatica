// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Objects;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// The absence bounds of the departure backstop (spec R-2.14, R-2.14b, R-2.14c) and the core suspicion window (R-2.11 (b)), in
/// nanoseconds of the leader-local monotonic clock. They are derived from the live configuration, never hard-coded:
///
/// - `coreRemoval` (`B_rm`) = `suspectTimeout x lhmMaxScore x MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER + splitTimeout`;
/// - `workerRemoval` (`B_w`) = `workerSuspectTimeout x lhmMaxScore x MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER
///   + reannounceInterval + communityAbsence`;
/// - `announcementFreshness` (`S_ann`) = `2 x reannounceInterval`;
/// - `coreSuspicion` = `suspectTimeout x lhmMaxScore x MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER + suspicionLag`: the upper bound of one SWIM suspicion
///   window (R-2.14) plus the lag between SWIM's verdict and the leader's view of it, i.e. how long a core must stay listed in this leader's core
///   view to count as alive. SWIM arms `suspectTimeout x min(score + 1, lhmMaxScore) x min(max(1, ln(N + 1)), MAX_CLUSTER_SIZE_WINDOW_MULTIPLIER)`,
///   so the product is its maximum, reached from about 20 live members; with the product alone the margin there is 0, and the sampling lag
///   (the core view is read every `SeamPoller.INTERVAL_MS`, the verdict itself takes up to one probe period to be reached) would sit outside both
///   sides. `suspicionLag` is that lag, from its real sources (v-2204 F2, ruled s37).
///
/// W5 on every path (v-2190 N3, CTO ruling 2026-10-14): a final class with a private constructor, so the `Result` factory below is the only
/// way to build one and a non-positive window can never exist.
public final class AbsenceBounds {
    private static final Fn1<Cause, Long> NON_POSITIVE_WINDOW = Causes.forOneValue("The core suspicion window must be positive, was %s ns");

    private final long coreRemoval;
    private final long workerRemoval;
    private final long announcementFreshness;
    private final long coreSuspicion;

    private AbsenceBounds(long coreRemoval, long workerRemoval, long announcementFreshness, long coreSuspicion) {
        this.coreRemoval = coreRemoval;
        this.workerRemoval = workerRemoval;
        this.announcementFreshness = announcementFreshness;
        this.coreSuspicion = coreSuspicion;
    }

    /// A bounds record is valid only with a positive suspicion window: with a window of 0 a core counted as alive the moment it is first listed,
    /// so a `Rebuild` snapshot alone would promote it at C1 (R-2.11, D-4 (ii)).
    public static Result<AbsenceBounds> absenceBounds(long coreRemovalNs,
                                                      long workerRemovalNs,
                                                      long announcementFreshnessNs,
                                                      long coreSuspicionNs) {
        return Verify.ensure(coreSuspicionNs, Verify.Is::positive, NON_POSITIVE_WINDOW).map(window -> new AbsenceBounds(coreRemovalNs,
                                                                                                                        workerRemovalNs,
                                                                                                                        announcementFreshnessNs,
                                                                                                                        window));
    }

    /// R-2.14 / R-2.14b / R-2.14c from the configured terms.
    public static Result<AbsenceBounds> derive(TimeSpan suspectTimeout,
                                               TimeSpan workerSuspectTimeout,
                                               int lhmMaxScore,
                                               double clusterSizeWindowMultiplier,
                                               TimeSpan splitTimeout,
                                               TimeSpan reannounceInterval,
                                               TimeSpan communityAbsence,
                                               TimeSpan suspicionLag) {
        var window = lhmMaxScore * clusterSizeWindowMultiplier;
        var coreSuspicion = (long)(suspectTimeout.nanos() * window) + suspicionLag.nanos();
        var coreRemoval = coreSuspicion + splitTimeout.nanos();
        var workerRemoval = (long)(workerSuspectTimeout.nanos() * window) + reannounceInterval.nanos() + communityAbsence.nanos();

        return absenceBounds(coreRemoval, workerRemoval, 2 * reannounceInterval.nanos(), coreSuspicion);
    }

    public long coreRemoval() {
        return coreRemoval;
    }

    public long workerRemoval() {
        return workerRemoval;
    }

    public long announcementFreshness() {
        return announcementFreshness;
    }

    public long coreSuspicion() {
        return coreSuspicion;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AbsenceBounds that
               && coreRemoval == that.coreRemoval
               && workerRemoval == that.workerRemoval
               && announcementFreshness == that.announcementFreshness
               && coreSuspicion == that.coreSuspicion;
    }

    @Override
    public int hashCode() {
        return Objects.hash(coreRemoval, workerRemoval, announcementFreshness, coreSuspicion);
    }

    @Override
    public String toString() {
        return "AbsenceBounds[coreRemoval=" + coreRemoval
             + ", workerRemoval=" + workerRemoval
             + ", announcementFreshness=" + announcementFreshness
             + ", coreSuspicion=" + coreSuspicion
             + "]";
    }
}
