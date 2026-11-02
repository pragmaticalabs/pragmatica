// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;

/// The single virtual clock of the G3 model: a discrete-event queue that is the reconciler's [TimeSource] and [NttTimerScheduler], the
/// lifecycle's epoch-millisecond clock and the fake provider's event loop at once. Nothing runs unless [#advance] runs it, in due-time
/// order (ties in scheduling order), on the calling thread - so a schedule is a pure function of its seed.
final class VirtualTime implements TimeSource, NttTimerScheduler, LongSupplier {
    /// An arbitrary non-zero start, so "epoch milliseconds" arithmetic never meets a zero.
    private static final long START_MS = 1_000_000L;

    private final PriorityQueue<Event> queue = new PriorityQueue<>(Comparator.<Event> comparingLong(event -> event.dueMs)
                                                                             .thenComparingLong(event -> event.sequence));
    private long nowMs = START_MS;
    private long sequence;
    private long globalSeq;

    /// A strictly increasing stamp shared by every recorder, so two facts at the same millisecond still have an order.
    long seq() {
        return globalSeq++;
    }

    long nowMs() {
        return nowMs;
    }

    @Override
    public long nanoTime() {
        return TimeUnit.MILLISECONDS.toNanos(nowMs);
    }

    @Override
    public long getAsLong() {
        return nowMs;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
        return after(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(delay.nanos())), runnable);
    }

    Event after(long delayMs, Runnable runnable) {
        var event = new Event(nowMs + delayMs, sequence++, runnable);

        queue.add(event);

        return event;
    }

    /// Runs every event due within `millis` of virtual time, in order, and leaves the clock `millis` later.
    void advance(long millis) {
        var target = nowMs + millis;

        while (!queue.isEmpty() && queue.peek().dueMs <= target) {
            var event = queue.poll();

            nowMs = Math.max(nowMs, event.dueMs);
            event.run();
        }

        nowMs = target;
    }

    int pending() {
        return (int) queue.stream().filter(event -> !event.cancelled).count();
    }

    static final class Event implements ScheduledFuture<Object> {
        private final long dueMs;
        private final long sequence;
        private final Runnable runnable;
        private boolean cancelled;
        private boolean done;

        private Event(long dueMs, long sequence, Runnable runnable) {
            this.dueMs = dueMs;
            this.sequence = sequence;
            this.runnable = runnable;
        }

        private void run() {
            if (cancelled || done) {
                return;
            }

            done = true;
            runnable.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(dueMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }

            cancelled = true;

            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
