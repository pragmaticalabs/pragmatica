// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Sleeping as a `Result`, so back-off loops stay in one error channel.
sealed interface PortWait {
    static Result<Unit> sleep(long millis) {
        return Result.lift(_ -> PortError.General.INTERRUPTED, () -> Thread.sleep(millis));
    }

    /// Polls `step` until it yields a value, fails, or `deadline` (a `System.nanoTime()` reading) passes, sleeping `intervalMs`
    /// between polls (the last sleep is clamped to the time remaining). Iterative, so stack depth is constant however long the wait; a timeout yields `onTimeout`.
    static <T> Result<T> poll(long deadline,
                              long intervalMs,
                              Supplier<Result<Option<T>>> step,
                              Supplier<Cause> onTimeout) {
        var current = step.get();

        while (pending(current) && System.nanoTime() < deadline) {
            current = sleep(Math.min(intervalMs, remainingMs(deadline))).flatMap(_ -> step.get());
        }

        return current.flatMap(found -> found.map(Result::success)
                                             .or(() -> onTimeout.get()
                                                                .result()));
    }

    private static long remainingMs(long deadline) {
        return Math.max(0L, (deadline - System.nanoTime()) / 1_000_000L);
    }

    private static <T> boolean pending(Result<Option<T>> current) {
        return current.fold(_ -> false, Option::isEmpty);
    }

    record unused() implements PortWait {}
}
