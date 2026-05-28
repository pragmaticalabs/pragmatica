// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;


/// Emitted by [`LocalQuorumWatcher`] when `localQuorumCount` has been below the quorum
/// threshold continuously for at least `quorumLossDrainThreshold` (spec §8.1, third bullet).
///
/// Local in-process event; the consumer triggers the §8 drain procedure. At E1, observation
/// only — the wiring layer's consumer just logs.
///
/// @param observedAtNanos         `TimeSource`-derived monotonic nanos at the moment the
///                                threshold-elapsed condition was confirmed
/// @param observedLocalQuorumCount the local quorum count at the moment of emission
///                                 (self + currently-connected peers)
/// @param requiredThreshold       the simple-majority threshold `coreCount / 2 + 1` against
///                                which the observation was made
public record QuorumLossIntent(long observedAtNanos,
                               int observedLocalQuorumCount,
                               int requiredThreshold) {
    public static QuorumLossIntent quorumLossIntent(long observedAtNanos,
                                                    int observedLocalQuorumCount,
                                                    int requiredThreshold) {
        return new QuorumLossIntent(observedAtNanos, observedLocalQuorumCount, requiredThreshold);
    }
}
