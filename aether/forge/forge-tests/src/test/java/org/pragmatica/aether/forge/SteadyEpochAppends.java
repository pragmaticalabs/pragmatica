// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Functions.Fn2;

/// #1406 — a timed append loop that measures a STEADY ownership epoch, and says so when it did not.
///
/// The baseline test used to start a 2,000-write loop straight after materialising the stream, so initial
/// owner promotion overlapped what it called a within-epoch measurement, and the loop kept only a success
/// count: one refused append surfaced as `1999 != 2000` with no index and no cause. This runs the loop only
/// while the caller's readiness holds, refuses a run whose epoch moved, and keeps EVERY refusal with its
/// index and typed cause. It never retries a refusal away — the success requirement stays the caller's.
public sealed interface SteadyEpochAppends {
    /// The caller's readiness did not hold at the gate, so no append was issued.
    enum NotReady implements Cause {
        OWNERSHIP_NOT_READY("Ownership readiness did not hold when the measurement started; no append was issued");

        private final String message;

        NotReady(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    /// The epoch observed after the loop differs from the one observed before it: the measurement was not
    /// within a single epoch.
    record EpochMoved(Object before, Object after, String message) implements Cause {
        static final Fn2<EpochMoved, Object, Object> FACTORY = Causes.forTwoValues("Epoch moved during the measurement: %s -> %s",
                                                                              EpochMoved::new);
    }

    /// One refused append: its position in the loop and the typed cause the write path returned.
    record Refusal(int index, Cause cause) {}

    /// What the loop did: how many appends it issued and every refusal, in order.
    record Outcome(int attempts, List<Refusal> refusals) {
        public int successes() {
            return attempts - refusals.size();
        }

        public String report() {
            return refusals.isEmpty()
                   ? "no refusals in " + attempts + " appends"
                   : refusals.size() + " refused of " + attempts + ": " + refusals.stream()
                                                                                  .map(Outcome::describe)
                                                                                  .collect(Collectors.joining("; "));
        }

        private static String describe(Refusal refusal) {
            return "append #" + refusal.index() + " -> " + refusal.cause().getClass().getSimpleName() + ": " + refusal.cause().message();
        }
    }

    /// Run `attempts` appends when `ready` holds, comparing the epoch read before and after. Refusals are
    /// recorded, not retried.
    static <E> Result<Outcome> measureWhenReady(BooleanSupplier ready,
                                                Supplier<E> epoch,
                                                int attempts,
                                                IntFunction<Result<Long>> append) {
        return ready.getAsBoolean()
               ? runWithEpochCheck(epoch, attempts, append)
               : NotReady.OWNERSHIP_NOT_READY.result();
    }

    private static <E> Result<Outcome> runWithEpochCheck(Supplier<E> epoch, int attempts, IntFunction<Result<Long>> append) {
        var before = epoch.get();
        var outcome = run(attempts, append);
        var after = epoch.get();

        return Objects.equals(before, after)
               ? Result.success(outcome)
               : EpochMoved.FACTORY.apply(before, after).result();
    }

    private static Outcome run(int attempts, IntFunction<Result<Long>> append) {
        var refusals = IntStream.range(0, attempts)
                                .mapToObj(index -> refusalOf(index, append.apply(index)))
                                .flatMap(Option::stream)
                                .toList();

        return new Outcome(attempts, refusals);
    }

    private static Option<Refusal> refusalOf(int index, Result<Long> appended) {
        return appended.fold(cause -> Option.some(new Refusal(index, cause)), _ -> Option.none());
    }

    record unused() implements SteadyEpochAppends {}
}
