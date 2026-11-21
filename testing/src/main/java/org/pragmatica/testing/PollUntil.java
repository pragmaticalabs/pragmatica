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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
/// The wait bounds the failure even when the supplier blocks, and fails at once on an interrupt; the contract is on the
/// injectable overload below.
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

    /// How long a single poll may take when the deadline has (almost) run out: the first poll must not be starved by
    /// thread start-up on a loaded host, and the final poll after the last pause is a real sample, not a formality. A
    /// supplier that blocks is therefore cut off at the later of the time left and this grace.
    static final Duration POLL_GRACE = Duration.ofMillis(200);

    /// The wait with its clock and pause injected, so the deadline arithmetic is testable without real time.
    ///
    /// Contract, per outcome:
    ///  - a pause is `min(interval, time left)`, so the whole wait ends at the deadline plus scheduling slack (and plus
    ///    [#POLL_GRACE] only when a supplier blocks in the last poll);
    ///  - every supplier call runs on its own virtual thread and is bounded by `get(time left)`; a supplier that blocks past
    ///    that is cancelled and the wait fails with a report saying "supplier blocked past the deadline";
    ///  - an interrupt of the waiting thread restores the flag and fails the wait at once, with the report; it never keeps polling;
    ///  - an exception thrown by the supplier propagates raw on the first throw, by design: no retry and no last-failure
    ///    in the report, a supplier that can fail is the caller's to make total;
    ///  - a zero or negative `interval` is rejected up front.
    ///
    /// Both failures are an [AssertionError] carrying [Report#describe]: a test is the only caller, and JUnit has no Result channel.
    static <T> Report<T> pollUntil(Supplier<T> supplier,
                                   Predicate<T> predicate,
                                   Duration deadline,
                                   Duration interval,
                                   LongSupplier clock,
                                   LongConsumer pause) {
        requirePositive(interval);

        var start = clock.getAsLong();
        var limit = deadline.toNanos();
        var progress = Progress.<T>start();
        var remaining = limit;

        while (true) {
            var polled = poll(supplier, Math.max(remaining, POLL_GRACE.toNanos()));

            if (polled.failure() != null) {
                return fail(progress.report(elapsed(clock, start), deadline), polled.failure());
            }
            progress = progress.observed(polled.value(), predicate);
            remaining = limit - (clock.getAsLong() - start);
            if (progress.met() || remaining <= 0) {
                return progress.report(elapsed(clock, start), deadline);
            }
            pause.accept(Math.min(interval.toNanos(), remaining));
            remaining = limit - (clock.getAsLong() - start);
        }
    }

    private static Duration elapsed(LongSupplier clock, long start) {
        return Duration.ofNanos(clock.getAsLong() - start);
    }

    /// One supplier call: its value, or the reason the wait cannot go on.
    private record Polled<T>(T value, String failure) {
        static <T> Polled<T> value(T value) {
            return new Polled<>(value, null);
        }

        static <T> Polled<T> failed(String why) {
            return new Polled<>(null, why);
        }
    }

    private static <T> Polled<T> poll(Supplier<T> supplier, long budgetNanos) {
        var task = new FutureTask<>(supplier::get);

        Thread.ofVirtual().start(task);
        try {
            return Polled.value(task.get(budgetNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            task.cancel(true);
            return Polled.failed("supplier blocked past the deadline");
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return Polled.failed("interrupted while waiting");
        } catch (ExecutionException e) {
            return propagate(e.getCause());
        }
    }

    /// The supplier's own exception, rethrown as it was thrown (a checked one wrapped): see the contract on the injectable overload.
    @Contract
    private static <T> Polled<T> propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(cause);
    }

    @Contract
    private static void requirePositive(Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("poll interval must be positive, was " + interval);
        }
    }

    /// A failed wait is an assertion failure carrying the report, like [Report#assertMet].
    @Contract
    private static <T> Report<T> fail(Report<T> report, String why) {
        throw new AssertionError(report.describe(why));
    }

    /// What the loop has seen so far; each observation yields a new value.
    private record Progress<T>(T first, T current, int polls, boolean met) {
        static <T> Progress<T> start() {
            return new Progress<>(null, null, 0, false);
        }

        Progress<T> observed(T value, Predicate<T> predicate) {
            return new Progress<>(polls == 0 ? value : first, value, polls + 1, predicate.test(value));
        }

        Report<T> report(Duration elapsed, Duration deadline) {
            return Report.report(met, first, current, polls, elapsed, deadline);
        }
    }
}
