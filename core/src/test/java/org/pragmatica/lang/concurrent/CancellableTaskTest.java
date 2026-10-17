/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
 */

package org.pragmatica.lang.concurrent;

import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1356: `cancelIfPresent` moved from a raw null check to `Option`; these pin the holder's contract
/// so that hunk reddens something. The holder's slot is a VarHandle over a nullable field, and the
/// empty holder is the case the null check existed for.
class CancellableTaskTest {

    @Test
    void set_cancelsThePreviousTask_andKeepsTheNewOneScheduled() {
        var first = new RecordingFuture();
        var second = new RecordingFuture();
        var task = CancellableTask.cancellableTask(first);

        task.set(second);

        assertThat(first.wasCancelled()).as("the replaced task is cancelled").isTrue();
        assertThat(second.wasCancelled()).as("the new task is left running").isFalse();
        assertThat(task.isScheduled()).isTrue();
    }

    @Test
    void cancel_cancelsTheCurrentTask_andClearsTheSlot() {
        var future = new RecordingFuture();
        var task = CancellableTask.cancellableTask(future);

        task.cancel();

        assertThat(future.wasCancelled()).isTrue();
        assertThat(task.isScheduled()).isFalse();
    }

    @Test
    void cancel_onAnEmptyHolder_isANoOp() {
        var task = CancellableTask.cancellableTask();

        task.cancel();
        task.set(new RecordingFuture());
        task.cancel();

        assertThat(task.isScheduled()).isFalse();
    }

    private static final class RecordingFuture implements ScheduledFuture<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        boolean wasCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
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
