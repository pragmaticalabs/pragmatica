// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.isolation;

/// What [CoreAbsenceDetector] hands its listener when the local dissolve fires.
///
/// Carries the observation and the threshold it crossed rather than just the fact of firing, so the
/// operator-visible record answers "how far past the line was it" — the question that separates a
/// genuine partition from a window tuned too tightly against leader-election gaps.
///
/// @param sinceLastPingNanos age of the last ACCEPTED core ping at the moment of firing
/// @param thresholdNanos     the configured `timeouts.cluster.core_absence` it exceeded
public record CoreAbsenceIntent(long sinceLastPingNanos, long thresholdNanos) {
    public static CoreAbsenceIntent coreAbsenceIntent(long sinceLastPingNanos, long thresholdNanos) {
        return new CoreAbsenceIntent(sinceLastPingNanos, thresholdNanos);
    }
}
