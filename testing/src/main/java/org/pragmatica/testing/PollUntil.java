/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.testing;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;


/// The shared bounded wait for tests: poll a supplier until a predicate holds or a deadline passes.
///
/// Every outcome, met or missed, carries what a flake triage needs: the first-poll value, the final value, the poll count,
/// the elapsed time and the margin to the deadline (deadline minus elapsed; negative or zero on a miss). A test that
/// passes with a margin of a few milliseconds is a flake waiting for a loaded host, and the margin says so before it fails.
///
/// Use it instead of a fixed sleep (the flake-pattern scan, `FlakeScanTest`, points here): a sleep guesses how long the
/// work takes, this waits for the condition and bounds only the failure.
public final class PollUntil {
    /// Pause between polls when the caller gives none.
    public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(10);

    private PollUntil() {}

    /// What one wait observed. `margin` is the deadline minus `elapsed`: how close a pass came, or by how much a miss overran.
    public record Report<T>(boolean met, T firstValue, T finalValue, int polls, Duration elapsed, Duration margin) {
        /// Factory: derives the margin from the deadline so no caller computes it differently.
        public static <T> Report<T> report(boolean met,
                                           T firstValue,
                                           T finalValue,
                                           int polls,
                                           Duration elapsed,
                                           Duration deadline) {
            return new Report<>(met, firstValue, finalValue, polls, elapsed, deadline.minus(elapsed));
        }

        /// One line naming `what` was awaited and everything the wait observed, for assertion messages and logs.
        public String describe(String what) {
            return (met
                    ? "met: "
                    : "MISSED: ") + what
                 + " (first=" + firstValue
                 + ", final=" + finalValue
                 + ", polls=" + polls
                 + ", elapsed=" + elapsed.toMillis()
                 + " ms, margin=" + margin.toMillis()
                 + " ms)";
        }

        /// Returns this report when the predicate held; otherwise fails with [#describe]. The throw is the signature
        /// JUnit dictates: a missed wait in a test is an assertion failure, there is no Result channel there.
        @Contract
        public Report<T> assertMet(String what) {
            if (!met) {
                throw new AssertionError(describe(what));
            }

            return this;
        }
    }

    /// Polls every [#DEFAULT_INTERVAL] until `predicate` holds for the supplied value or `deadline` passes.
    public static <T> Report<T> pollUntil(Supplier<T> supplier, Predicate<T> predicate, Duration deadline) {
        return pollUntil(supplier, predicate, deadline, DEFAULT_INTERVAL);
    }

    /// Polls every `interval` until `predicate` holds for the supplied value or `deadline` passes.
    public static <T> Report<T> pollUntil(Supplier<T> supplier,
                                          Predicate<T> predicate,
                                          Duration deadline,
                                          Duration interval) {
        return pollUntil(supplier, predicate, deadline, interval, System::nanoTime, LockSupport::parkNanos);
    }

    /// The wait with its clock and pause injected, so the deadline arithmetic is testable without real time.
    static <T> Report<T> pollUntil(Supplier<T> supplier,
                                   Predicate<T> predicate,
                                   Duration deadline,
                                   Duration interval,
                                   LongSupplier clock,
                                   LongConsumer pause) {
        var start = clock.getAsLong();
        var limit = deadline.toNanos();
        var first = supplier.get();
        var current = first;
        var polls = 1;
        var met = predicate.test(current);

        while (!met && clock.getAsLong() - start < limit) {
            pause.accept(interval.toNanos());
            current = supplier.get();
            polls++;
            met = predicate.test(current);
        }

        return Report.report(met,
                             first,
                             current,
                             polls,
                             Duration.ofNanos(clock.getAsLong() - start),
                             deadline);
    }
}
