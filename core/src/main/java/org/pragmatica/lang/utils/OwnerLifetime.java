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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// Scheduling bound to the lifetime of its owner: once the owner calls [#end()], nothing scheduled
/// through this lifetime starts a body, and nothing scheduled afterwards is enqueued.
///
/// The owner no longer keeps one cancel handle per task; it creates a lifetime, schedules through it,
/// and ends it once when it stops. Scheduling mirrors [SharedScheduler] (one-shot, fixed-rate, and
/// initial-delay-plus-fixed-rate), and the work still runs on [SharedScheduler].
///
/// ## The invariant: publish, then read the other side
/// - [#end()] writes the flag, then reads the set of live entries and cancels each one that has a
///   scheduler future.
/// - A schedule call writes its entry into the set, enqueues, publishes the scheduler future into the
///   entry, then reads the flag; if the flag is ended, it cancels its own entry.
/// - Whichever of the two runs second sees the other's write, so a task enqueued across the flag write is
///   cancelled by the sweep or by the re-read, unless it has already completed. Cancelling twice is harmless.
///
/// The flag write in [#end()] is the lifetime's single linearization point.
///
/// ## What is claimed
/// - No body runs whose wrapper read of the flag follows the flag write.
/// - No schedule call whose first read of the flag follows the write enqueues.
/// - Every entry registered before the sweep reads the set, and published by then, is cancelled by the
///   sweep; an entry the sweep finds unpublished is cancelled by that entry's own re-read.
/// - On completion of a one-shot and on cancel through the handle, the entry leaves the set.
/// - [#end()] is idempotent and never waits for a task, a body or another [#end()]. Every caller of
///   [#end()] sweeps, so a concurrent second [#end()] has itself cancelled every entry it can see when it returns.
///
/// ## What is not claimed
/// - A body whose wrapper read preceded the flag write may run, in whole, after [#end()] returns. A side effect
///   that must not happen after stop needs a second [#isEnded()] check on its own path (this only narrows the
///   window) or an enforcer at the receiver: a fence, a term or generation, or a stop that awaits in-flight bodies.
/// - [#end()] never interrupts a running body, including the body that called it.
/// - The type does not enforce that an owner calls [#end()], or calls it first.
///
/// ## Re-activation
/// An owner that activates and deactivates repeatedly creates a FRESH lifetime per activation, swaps its field
/// atomically, and ends the old lifetime it gets back. Every body receives the lifetime it was scheduled on, so a
/// re-arm from inside a body cannot reach the next activation's lifetime.
///
/// A cancelled queue entry stays in the scheduler's queue until its deadline; only its body reference is released.
public final class OwnerLifetime {
    /// The handle of one scheduled task. It is the entry itself: cancelling it removes the entry from the
    /// lifetime and cancels the scheduler's future, so a debounce that cancels and re-schedules does not
    /// accumulate entries.
    public interface Handle {
        /// Cancel the task. Idempotent; never interrupts a running body.
        ///
        /// @return `true` if this call changed the handle from live to cancelled
        boolean cancel();

        /// @return `true` once the handle has been cancelled, by its owner, by [OwnerLifetime#end()] or
        /// because the lifetime had already ended when it was scheduled
        boolean isCancelled();
    }

    /// The scheduling seam. One method carries all three scheduling forms; a present `period` means periodic.
    interface Seam {
        ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan initialDelay, Option<TimeSpan> period);
    }

    private static final Seam SHARED = OwnerLifetime::enqueueOnShared;

    private final Seam seam;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final Set<Entry> entries = ConcurrentHashMap.newKeySet();

    private OwnerLifetime(Seam seam) {
        this.seam = seam;
    }

    /// Create a live lifetime that schedules on [SharedScheduler]. An owner that never starts creates its
    /// lifetime and ends it at once.
    public static OwnerLifetime ownerLifetime() {
        return new OwnerLifetime(SHARED);
    }

    static OwnerLifetime ownerLifetime(Seam seam) {
        return new OwnerLifetime(seam);
    }

    /// Schedule one-time invocation. On an ended lifetime nothing is enqueued and the returned handle is
    /// already cancelled; this never throws for that reason.
    ///
    /// @param body receives this lifetime, so a re-arm from inside the body stays on it
    ///
    /// @return handle that can be used to cancel the scheduled task
    public Handle schedule(Consumer<OwnerLifetime> body, TimeSpan delay) {
        return schedule(body, delay, Option.none());
    }

    /// Schedule periodic invocation, with `interval` used as both initial delay and period.
    ///
    /// @param body receives this lifetime, so a re-arm from inside the body stays on it
    ///
    /// @return handle that can be used to cancel the scheduled task
    public Handle scheduleAtFixedRate(Consumer<OwnerLifetime> body, TimeSpan interval) {
        return scheduleAtFixedRate(body, interval, interval);
    }

    /// Schedule periodic invocation with custom initial delay.
    ///
    /// @param body receives this lifetime, so a re-arm from inside the body stays on it
    ///
    /// @return handle that can be used to cancel the scheduled task
    public Handle scheduleAtFixedRate(Consumer<OwnerLifetime> body, TimeSpan initialDelay, TimeSpan interval) {
        return schedule(body, initialDelay, Option.some(interval));
    }

    /// End the lifetime: write the flag, then cancel every entry that has a scheduler future. Idempotent, never
    /// blocks, and never interrupts a running body.
    public Unit end() {
        ended.set(true);
        entries.forEach(Entry::sweep);

        return Unit.unit();
    }

    /// @return `true` once [#end()] has written the flag
    public boolean isEnded() {
        return ended.get();
    }

    /// Number of live entries; for tests.
    int size() {
        return entries.size();
    }

    // Steps 1-5 of the schedule call live here and nowhere else.
    private Handle schedule(Consumer<OwnerLifetime> body, TimeSpan initialDelay, Option<TimeSpan> period) {
        return isEnded()
               ? new Entry(body, period.isPresent(), true)
               : arm(new Entry(body, period.isPresent(), false), initialDelay, period);
    }

    private Handle arm(Entry entry, TimeSpan initialDelay, Option<TimeSpan> period) {
        entries.add(entry);
        var future = enqueueOrDetach(entry, initialDelay, period);

        entry.publish(future);

        return rereadFlag(entry);
    }

    // If the seam throws, the entry never reached the scheduler: take it out of the set and let the failure out.
    private ScheduledFuture<?> enqueueOrDetach(Entry entry, TimeSpan initialDelay, Option<TimeSpan> period) {
        var enqueued = false;

        try {
            var scheduled = seam.enqueue(entry, initialDelay, period);

            enqueued = true;

            return scheduled;
        } finally {
            if (!enqueued) {
                entries.remove(entry);
            }
        }
    }

    private Handle rereadFlag(Entry entry) {
        if (isEnded()) {
            entry.cancel();
        }

        return entry;
    }

    private static ScheduledFuture<?> enqueueOnShared(Runnable wrapper,
                                                      TimeSpan initialDelay,
                                                      Option<TimeSpan> period) {
        return period.fold(() -> SharedScheduler.schedule(wrapper, initialDelay),
                           interval -> SharedScheduler.scheduleAtFixedRate(wrapper, initialDelay, interval));
    }

    /// Queue element AND returned handle. Identity equality: two entries are never equal.
    private final class Entry implements Runnable, Handle {
        private final Consumer<OwnerLifetime> body;
        private final boolean periodic;
        private final AtomicBoolean cancelled;
        private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

        private Entry(Consumer<OwnerLifetime> body, boolean periodic, boolean cancelled) {
            this.body = body;
            this.periodic = periodic;
            this.cancelled = new AtomicBoolean(cancelled);
        }

        // The wrapper consults the lifetime flag and nothing else. A one-shot leaves the set even when the body throws.
        @Override
        @Contract
        public void run() {
            try {
                runUnlessEnded();
            } finally {
                completeOneShot();
            }
        }

        @Contract
        private void runUnlessEnded() {
            if (!isEnded()) {
                body.accept(OwnerLifetime.this);
            }
        }

        @Contract
        private void completeOneShot() {
            if (!periodic) {
                entries.remove(this);
            }
        }

        @Contract
        private void publish(ScheduledFuture<?> scheduled) {
            future.set(scheduled);
        }

        // The sweep's view of the entry: an unpublished entry is cancelled by its own schedule call's re-read.
        @Contract
        private void sweep() {
            Option.option(future.get()).onPresent(_ -> cancel());
        }

        // The one cancel path: the handle and the sweep both come here.
        @Override
        public boolean cancel() {
            entries.remove(this);
            var changed = cancelled.compareAndSet(false, true);

            cancelScheduled();

            return changed;
        }

        @Contract
        private void cancelScheduled() {
            Option.option(future.get()).onPresent(scheduled -> scheduled.cancel(false));
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
