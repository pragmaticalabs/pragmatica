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
package org.pragmatica.lang.utils;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// #714 — the idempotency cleanup task must TERMINATE when its map is collected, not merely no-op.
///
/// The task is armed by a factory and has no lifecycle owner to call a stop hook, so the map's
/// reachability is the only lifecycle it has. Before #714 the `ScheduledFuture` returned by
/// `SharedScheduler.scheduleAtFixedRate` was discarded outright — nothing could cancel it. The
/// WeakReference made the tick a no-op once the map was collected, exactly as its comment claimed,
/// and the task then ran for the life of the JVM, once per `Idempotency` instance ever created.
///
/// These tests drive `cleanupExpiredEntries` directly with a pre-cleared reference and a recording
/// future. Forcing a real GC and waiting for the weak reference to clear would be timing-dependent
/// and would pin nothing.
class IdempotencyCleanupCancelTest {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(IdempotencyCleanupCancelTest.class);

    /// Minimal recording stand-in — only `cancel` is exercised, and asserting on a real scheduled
    /// future would reintroduce the timing dependence this test exists to avoid.
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

    private static WeakReference<ConcurrentHashMap<String, Idempotency.CachedEntry<?>>> clearedRef() {
        var ref = new WeakReference<ConcurrentHashMap<String, Idempotency.CachedEntry<?>>>(new ConcurrentHashMap<>());

        ref.clear();

        return ref;
    }

    @Test
    void collectedMap_cancelsTheCleanupTask_ratherThanTickingForever() {
        var future = new RecordingFuture();
        var taskRef = new AtomicReference<ScheduledFuture<?>>(future);

        Idempotency.cleanupExpiredEntries(clearedRef(), TimeSource.system(), LOG, taskRef);

        assertThat(future.wasCancelled())
            .as("#714: once the owning map is collected the task must stop, not become a permanent no-op")
            .isTrue();
    }

    /// The task can fire once before the holder is populated. That must not throw — at that point
    /// the map is still strongly reachable anyway, so there is nothing to cancel.
    @Test
    void unarmedHolder_isTolerated_ratherThanThrowing() {
        var taskRef = new AtomicReference<ScheduledFuture<?>>();

        Idempotency.cleanupExpiredEntries(clearedRef(), TimeSource.system(), LOG, taskRef);

        // Cast to Object: AssertJ overloads assertThat for Future, and a ScheduledFuture argument
        // makes the call ambiguous.
        assertThat((Object) taskRef.get()).as("nothing to cancel, and no exception escapes").isNull();
    }

    /// A live map must never cancel the sweep — that would silently stop expiry for a cache still
    /// in use, turning a leak fix into a correctness bug.
    @Test
    void liveMap_leavesTheCleanupTaskArmed() {
        var entries = new ConcurrentHashMap<String, Idempotency.CachedEntry<?>>();
        var future = new RecordingFuture();
        var taskRef = new AtomicReference<ScheduledFuture<?>>(future);

        Idempotency.cleanupExpiredEntries(new WeakReference<>(entries), TimeSource.system(), LOG, taskRef);

        assertThat(future.wasCancelled())
            .as("a reachable map means the cache is still in use — the sweep must keep running")
            .isFalse();
    }
}
