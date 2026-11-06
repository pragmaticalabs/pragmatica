// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import java.util.function.BooleanSupplier;


/// The drain clock handed to the departure push (#2034).
///
/// - `legsDeadlineNanos`: absolute [System#nanoTime] by which every departure leg must have resolved: the drain's start plus its
///   grace, less the halt slack. Quiesce time is already deducted, and it is never re-anchored.
/// - `eventFloorNanos`: the overall legs deadline, unchanged by [#withDeadline]. A leg whose own deadline was shortened for a later
///   leg may still wait for its events to land up to here when its own deadline leaves them no time (a late quiesce).
/// - `drainStartMillis`: wall clock at the start of the drain; names the drain episode in event subjects (`<node>@<millis>`).
/// - `halted`: true once the drain has exited (`EXITED`); in-JVM hosts keep running after their exit hook, so a leg checks it.
public record DrainWindow(long legsDeadlineNanos, long eventFloorNanos, long drainStartMillis, BooleanSupplier halted) {
    /// A window whose legs deadline is also its event floor.
    public static DrainWindow drainWindow(long legsDeadlineNanos, long drainStartMillis, BooleanSupplier halted) {
        return new DrainWindow(legsDeadlineNanos, legsDeadlineNanos, drainStartMillis, halted);
    }

    /// The same window with an earlier deadline for one leg, leaving the rest to a later leg; the event floor is kept.
    public DrainWindow withDeadline(long deadlineNanos) {
        return new DrainWindow(deadlineNanos, eventFloorNanos, drainStartMillis, halted);
    }
}
