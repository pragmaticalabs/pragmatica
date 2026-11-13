// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

/// The fixed time constants of the cluster FSM, in milliseconds (wall) unless named otherwise. Defaults are the
/// spec's; tests override single values.
///
/// - `postActivation` and `tick`: the two timers `Rebuild` schedules (C1, NT-07).
/// - `retryBase` / `retryMax`: R-3.3.7 `min(2^(n-1), 30)` seconds, scaled by the injected jitter.
/// - `awaitedTarget`: the bound of the awaited-target watch and of the target re-drive, 1 per 2 min (6.2).
/// - `departingReread`: one consensus timeout, the re-read period of an unresolved C17 / C17s (R-4.3.0).
/// - `rebalanceHysteresisTicks`: how many consecutive imbalanced ticks precede a rebalance move.
/// - `drainStep`: the pacing of the drain loops.
/// - `applyDeadline` / `applySchemaPauseMax` (ms, R-4.1.7, #970): the unpaused time an open apply may stay unresolved, and the most time
///   its open schema gate may pause that clock. A non-positive `applyDeadline` is an unreadable bound: the apply is then never
///   terminated, only re-driven (the failure direction is re-drive, never condemn).
public record Timing(long postActivation,
                     long tick,
                     long retryBase,
                     long retryMax,
                     int maxRetries,
                     long awaitedTarget,
                     long departingReread,
                     int rebalanceHysteresisTicks,
                     long drainStep,
                     long applyDeadline,
                     long applySchemaPauseMax) {
    public static final Timing DEFAULT = new Timing(2_000L,
                                                    30_000L,
                                                    1_000L,
                                                    30_000L,
                                                    5,
                                                    120_000L,
                                                    30_000L,
                                                    2,
                                                    2_000L,
                                                    900_000L,
                                                    7_200_000L);

    public static Timing timing() {
        return DEFAULT;
    }

    public Timing withTick(long value) {
        return new Timing(postActivation,
                          value,
                          retryBase,
                          retryMax,
                          maxRetries,
                          awaitedTarget,
                          departingReread,
                          rebalanceHysteresisTicks,
                          drainStep,
                          applyDeadline,
                          applySchemaPauseMax);
    }

    public Timing withAwaitedTarget(long value) {
        return new Timing(postActivation,
                          tick,
                          retryBase,
                          retryMax,
                          maxRetries,
                          value,
                          departingReread,
                          rebalanceHysteresisTicks,
                          drainStep,
                          applyDeadline,
                          applySchemaPauseMax);
    }

    public Timing withApplyDeadline(long value) {
        return new Timing(postActivation,
                          tick,
                          retryBase,
                          retryMax,
                          maxRetries,
                          awaitedTarget,
                          departingReread,
                          rebalanceHysteresisTicks,
                          drainStep,
                          value,
                          applySchemaPauseMax);
    }

    public Timing withApplySchemaPauseMax(long value) {
        return new Timing(postActivation,
                          tick,
                          retryBase,
                          retryMax,
                          maxRetries,
                          awaitedTarget,
                          departingReread,
                          rebalanceHysteresisTicks,
                          drainStep,
                          applyDeadline,
                          value);
    }
}
