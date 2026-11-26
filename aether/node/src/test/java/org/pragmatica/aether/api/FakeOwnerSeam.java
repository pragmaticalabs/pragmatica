// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.OwnerLifetime;

/// Test double for [OwnerLifetime.Seam] (#3754): records every enqueue and runs a wrapper only when the test says so, so a
/// task armed before a stop can be fired after it, in the production order, without a clock or a thread.
final class FakeOwnerSeam implements OwnerLifetime.Seam {
    private final List<Task> tasks = new ArrayList<>();

    @Override
    public ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan initialDelay, Option<TimeSpan> period) {
        var task = new Task(wrapper, initialDelay);

        tasks.add(task);

        return task;
    }

    /// Every enqueue so far, cancelled or not.
    int enqueued() {
        return tasks.size();
    }

    List<TimeSpan> delays() {
        return tasks.stream().map(task -> task.delay).toList();
    }

    /// The first task that is neither cancelled nor run, if any.
    Option<Task> nextLive() {
        return Option.from(tasks.stream().filter(task -> !task.isDone()).findFirst());
    }

    /// Runs the wrapper of the task at `index` whether or not it was cancelled: this pins the wrapper's own read of the flag,
    /// not only the sweep's cancel.
    @Contract
    void fire(int index) {
        tasks.get(index).runWrapper();
    }

    static final class Task implements ScheduledFuture<Object> {
        private final Runnable wrapper;
        private final TimeSpan delay;
        private boolean cancelled;
        private boolean done;

        private Task(Runnable wrapper, TimeSpan delay) {
            this.wrapper = wrapper;
            this.delay = delay;
        }

        TimeSpan delay() {
            return delay;
        }

        @Contract
        void runWrapper() {
            done = true;
            wrapper.run();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
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

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
