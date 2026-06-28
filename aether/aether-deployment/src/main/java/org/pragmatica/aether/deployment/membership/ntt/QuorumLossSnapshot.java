// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// Observability snapshot of a node's local [`QuorumLossDetector`] state — the four
/// drain-readiness facts an operator (or LLM-ops agent) needs to answer "is THIS survivor's
/// quorum-loss self-drain window armed, and is it currently below the simple-majority
/// threshold?". A pure, point-in-time read assembled by [`#from`]; carries no behaviour.
///
/// - `strictMemberCount`: the detector's current strict member count (sourced from the SWIM-fed
///   membership tracker; already includes self).
/// - `requiredThreshold`: the simple-majority threshold `coreCount / 2 + 1`; `0` while the core
///   count is unknown (firing suppressed during bootstrap).
/// - `belowThreshold`: whether the strict count is currently below the threshold.
/// - `armed`: whether the detector has ever observed a quorate count — the safety-critical
///   cold-start latch. A never-quorate node is unarmed and never self-drains.
public record QuorumLossSnapshot(int strictMemberCount, int requiredThreshold, boolean belowThreshold, boolean armed) {
    /// Read the four observability accessors off the live detector into an immutable snapshot.
    public static QuorumLossSnapshot from(QuorumLossDetector detector) {
        return new QuorumLossSnapshot(detector.currentMemberCount(),
                                      detector.currentRequiredThreshold(),
                                      detector.isBelowThreshold(),
                                      detector.isArmed());
    }
}
