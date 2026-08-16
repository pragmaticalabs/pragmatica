// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.isolation;

import java.util.concurrent.TimeUnit;


/// A node's own core-absence view, as an operator sees it (#590).
///
/// The community-tier twin of `QuorumLossSnapshot`, and read the same way: PER NODE, never
/// leader-forwarded. That is not a stylistic choice — a node approaching its core-absence fence is by
/// definition one the core is losing contact with, so a leader-forwarded answer is the one answer
/// nobody can obtain during the incident it describes. An operator polls the suspect node directly.
///
/// Times are milliseconds because this crosses a JSON boundary; the detector keeps nanos internally.
///
/// @param armed             a core ping has been accepted at least once, so the countdown is live. False
///                          means this node has never heard the core and is cold-starting, NOT isolated
/// @param fenced            the local dissolve has already fired; this node has stopped serving
/// @param sinceLastPingMs   age of the last accepted `ClusterSyncPing`, or `-1` when none has arrived
/// @param remainingMs       time left before this node fences itself, or `-1` when no countdown is
///                          running (unarmed or already fenced). The field to watch during a suspected
///                          partition
/// @param thresholdMs       the configured `timeouts.cluster.core_absence`
public record CoreAbsenceSnapshot(boolean armed,
                                  boolean fenced,
                                  long sinceLastPingMs,
                                  long remainingMs,
                                  long thresholdMs) {
    private static final long ABSENT = -1L;

    public static CoreAbsenceSnapshot from(CoreAbsenceDetector detector) {
        return new CoreAbsenceSnapshot(detector.isArmed(),
                                       detector.isFenced(),
                                       detector.sinceLastCorePingNanos().map(CoreAbsenceSnapshot::toMillis).or(ABSENT),
                                       detector.remainingBeforeFenceNanos()
                                               .map(CoreAbsenceSnapshot::toMillis)
                                               .or(ABSENT),
                                       toMillis(detector.coreAbsenceWindow().nanos()));
    }

    private static long toMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
