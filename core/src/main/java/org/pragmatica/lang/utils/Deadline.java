/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
package org.pragmatica.lang.utils;

import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Ambient per-request deadline budget, shared across layers of one client-visible operation.
///
/// Each layer that waits (a forward hop, a correlation timeout, a remote read) caps its own
/// default by [#bounded(TimeSpan)] instead of stacking its full constant on top of every layer
/// above it — stacked constants are how a 30s client budget turns into minutes of server-side
/// work that nobody is waiting for anymore.
///
/// Durations are [TimeSpan] throughout; raw milliseconds appear only at the wire boundary
/// ([#toWireMillis()] / [#fromWireMillis(long)]), because the forwarded message carries the
/// remaining budget as a primitive codec field.
///
/// The deadline travels two ways:
/// - Within a thread, via a [ScopedValue] bound by [#runWith]. The binding survives only
///   synchronous call chains; code that hops threads must capture with [#current()] on the
///   caller side and re-bind inside the task. An unbound scope reads as [#unbounded()], so
///   callers outside any request (background jobs, schedulers) keep their full defaults.
/// - Across nodes, as remaining time via the wire boundary methods. Remaining time is used on
///   the wire, never an absolute point in time — clocks are not shared between nodes, elapsed
///   budget is.
public sealed interface Deadline {
    /// Wire value meaning "the sender had no budget"; [#fromWireMillis(long)] maps any negative
    /// value back to [#unbounded()].
    long NO_BUDGET = -1L;

    TimeSpan remaining();

    boolean isBounded();

    /// Cap a layer's own default wait by what is left of the request budget.
    TimeSpan bounded(TimeSpan defaultSpan);

    /// An equal share of the remaining budget across `parts` sequential attempts (at least 1ns,
    /// so a zero share cannot arm an instant timer storm; `parts` below 1 counts as 1).
    TimeSpan remainingShare(int parts);

    /// True when the budget is bounded and has `floor` or less remaining — the point where
    /// starting more work is waste, because the client (or the forwarding sender) is gone before
    /// the work can answer.
    boolean expired(TimeSpan floor);

    /// True when the budget is bounded and nothing remains.
    default boolean expired() {
        return expired(timeSpan(0).nanos());
    }

    /// Remaining budget in wire form: remaining milliseconds, or [#NO_BUDGET] when unbounded.
    long toWireMillis();

    record Bounded(long deadlineNanos, TimeSource clock) implements Deadline {
        @Override
        public TimeSpan remaining() {
            return timeSpan(Math.max(0L, deadlineNanos - clock.nanoTime())).nanos();
        }

        @Override
        public boolean isBounded() {
            return true;
        }

        @Override
        public TimeSpan bounded(TimeSpan defaultSpan) {
            var left = remaining();

            return left.compareTo(defaultSpan) < 0
                   ? left
                   : defaultSpan;
        }

        @Override
        public TimeSpan remainingShare(int parts) {
            return timeSpan(Math.max(1L, remaining().nanos() / Math.max(1, parts))).nanos();
        }

        @Override
        public boolean expired(TimeSpan floor) {
            return remaining().compareTo(floor) <= 0;
        }

        @Override
        public long toWireMillis() {
            return remaining().millis();
        }
    }

    /// Singleton by construction (enum), so "no budget" never allocates and every unbounded
    /// value is the same value.
    enum Unbounded implements Deadline {
        INSTANCE;

        private static final TimeSpan FOREVER = timeSpan(Long.MAX_VALUE).nanos();

        @Override
        public TimeSpan remaining() {
            return FOREVER;
        }

        @Override
        public boolean isBounded() {
            return false;
        }

        @Override
        public TimeSpan bounded(TimeSpan defaultSpan) {
            return defaultSpan;
        }

        @Override
        public TimeSpan remainingShare(int parts) {
            return FOREVER;
        }

        @Override
        public boolean expired(TimeSpan floor) {
            return false;
        }

        @Override
        public long toWireMillis() {
            return NO_BUDGET;
        }
    }

    static Deadline unbounded() {
        return Unbounded.INSTANCE;
    }

    static Deadline startingNow(TimeSpan budget) {
        return startingNow(budget, Scope.SYSTEM_CLOCK);
    }

    static Deadline startingNow(TimeSpan budget, TimeSource clock) {
        return new Bounded(clock.nanoTime() + budget.nanos(), clock);
    }

    static Deadline fromWireMillis(long remainingMillis) {
        return fromWireMillis(remainingMillis, Scope.SYSTEM_CLOCK);
    }

    static Deadline fromWireMillis(long remainingMillis, TimeSource clock) {
        return remainingMillis < 0
               ? unbounded()
               : startingNow(timeSpan(remainingMillis).millis(), clock);
    }

    /// Holder keeps the raw [ScopedValue] and the shared system clock off the public surface —
    /// the API is capture ([#current()]) and bind ([#runWith]), nothing else.
    final class Scope {
        private static final ScopedValue<Deadline> CURRENT = ScopedValue.newInstance();
        private static final TimeSource SYSTEM_CLOCK = TimeSource.system();

        private Scope() {}
    }

    /// The ambient deadline of the current scope; [#unbounded()] when none is bound.
    static Deadline current() {
        return Scope.CURRENT.isBound()
               ? Scope.CURRENT.get()
               : unbounded();
    }

    static <T> T runWith(Deadline deadline, Supplier<T> supplier) {
        return ScopedValue.where(Scope.CURRENT, deadline).call(supplier::get);
    }

    @Contract
    static void runWith(Deadline deadline, Runnable runnable) {
        ScopedValue.where(Scope.CURRENT, deadline).run(runnable);
    }
}
