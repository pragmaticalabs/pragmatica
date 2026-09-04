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

import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Prototype virtual-thread scheduler that decouples *timekeeping* from *execution*.
///
/// One dedicated PLATFORM daemon thread ("vt-sched-timer") owns a deadline-ordered
/// [DelayQueue]: it `take()`s the next-due task and only ever *submits* its body to a
/// virtual-thread-per-task executor — it never runs a body inline. This keeps timing
/// authority immune to carrier-pool / task-body starvation, the failure mode that makes
/// a fixed-size [java.util.concurrent.ScheduledThreadPoolExecutor] miss deadlines once
/// all its worker threads are blocked.
///
/// Raw JDK concurrency types (`volatile`, [AtomicBoolean], [Future], [DelayQueue]) are
/// intentional here: this is low-level plumbing mirroring [SharedScheduler], not business
/// logic. Failures inside task bodies are logged (never swallowed silently) and, crucially,
/// never cancel a recurring task — fixing STPE's "a throwing periodic task is silently
/// cancelled forever" footgun.
///
/// **Not used by `Promise#timeout(TimeSpan)`.** #749/#750/#838 give that path its own dedicated,
/// fixed-size `ScheduledThreadPoolExecutor` (`Promise.AsyncExecutor#timeoutScheduler`, 4 platform
/// threads) that runs the timeout FIRE action *inline*, on its own thread — the opposite of this
/// class's "never run inline" principle. The reason is narrower than it looks: a timeout's fire
/// action must not itself depend on carrier availability, or the original starvation bug (a
/// timeout that cannot be dispatched because every virtual-thread carrier is pinned) simply
/// reappears one layer down, inside whatever executes the fire. Handing the fire to a
/// virtual-thread-per-task executor — this class's normal mode — reintroduces exactly that
/// dependency. The cost is bounded rather than eliminated: `.timeout()`'s continuation chain
/// (`.map()`/`.flatMap()`/`withResult()`) dispatches inline per `CompletionMap`/`CompletionFold`'s
/// resolve-time contract, so a blocking continuation on a fired timeout occupies one of only 4
/// dedicated threads; more than 4 concurrent blocking continuations starve every *other* pending
/// timeout process-wide. Small, bounded, and documented, versus the original unbounded
/// shared-pool risk — but not free, and not eliminable without changing `resolve()`'s inline-dispatch
/// contract everywhere it is used, which is out of scope for a timeout-specific fix.
public final class VirtualThreadScheduler {
    private static final Logger log = LoggerFactory.getLogger(VirtualThreadScheduler.class);

    private final DelayQueue<ScheduledTask> queue = new DelayQueue<>();
    private final ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread timer;
    private final AtomicLong dispatchCount = new AtomicLong();
    private final AtomicLong maxLagNanos = new AtomicLong();
    private volatile boolean stopped;

    public VirtualThreadScheduler() {
        timer = new Thread(this::timerLoop, "vt-sched-timer");
        timer.setDaemon(true);
        timer.start();
    }

    /// Schedule a one-shot invocation after `delay`.
    public ScheduledFuture<?> schedule(Runnable task, TimeSpan delay) {
        return enqueue(new ScheduledTask(task,
                                         System.nanoTime() + delay.nanos(),
                                         0L));
    }

    /// Schedule a periodic invocation with `interval` used as both initial delay and period.
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, TimeSpan interval) {
        return scheduleAtFixedRate(task, interval, interval);
    }

    /// Schedule a periodic invocation with a custom initial delay.
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, TimeSpan initialDelay, TimeSpan interval) {
        return enqueue(new ScheduledTask(task,
                                         System.nanoTime() + initialDelay.nanos(),
                                         interval.nanos()));
    }

    /// Stop the timer thread and the virtual-thread executor.
    @Contract
    public void shutdown() {
        stopped = true;
        timer.interrupt();
        vtExec.shutdownNow();
    }

    /// Maximum dispatch lag (nanoseconds) observed so far: `dispatchTime - expectedDeadline`.
    public long maxLagNanos() {
        return maxLagNanos.get();
    }

    /// Total number of dispatches performed by the timer thread.
    public long dispatchCount() {
        return dispatchCount.get();
    }

    private ScheduledTask enqueue(ScheduledTask task) {
        queue.put(task);

        return task;
    }

    // Runs on the dedicated platform timer thread until shutdown.
    @Contract
    private void timerLoop() {
        while (!stopped) {
            ScheduledTask t;

            try {
                t = queue.take();
            } catch (InterruptedException e) {
                if (stopped) {
                    return;
                }

                continue;
            }

            try {
                dispatch(t);
            } catch (Throwable e) {
                // The single timer thread must be unkillable: a dispatch failure (e.g. a
                // rejected submit racing shutdown) must never stop scheduling for the whole JVM.
                log.warn("Scheduler dispatch failed; timer thread continues", e);
            }
        }
    }

    // Dispatches one due task, then reschedules it. Timer thread only.
    @Contract
    private void dispatch(ScheduledTask t) {
        if (t.cancelled) {
            return;
        }

        if (!t.running.compareAndSet(false, true)) {
            // Previous occurrence still in flight: skip this one but keep cadence.
            reschedule(t);

            return;
        }

        recordLag(t);
        t.inFlight = vtExec.submit(() -> {
            try {
                if (!t.cancelled) {
                    t.runGuarded();
                }
            } finally {
                t.running.set(false);
                t.inFlight = null;
                if (t.periodNanos == 0L) {
                    // One-shot: completion is terminal — let isDone() report true.
                    t.done = true;
                }
            }
        });
        reschedule(t);
    }

    @Contract
    private void recordLag(ScheduledTask t) {
        dispatchCount.incrementAndGet();
        var lag = System.nanoTime() - t.deadlineNanos;

        maxLagNanos.accumulateAndGet(lag, Math::max);
    }

    // Advances the task deadline and re-enqueues if still periodic. Timer thread only.
    @Contract
    private void reschedule(ScheduledTask t) {
        if (!t.cancelled && t.periodNanos > 0) {
            t.deadlineNanos += t.periodNanos;
            var now = System.nanoTime();

            if (t.deadlineNanos < now) {
                // Skip catch-up burst: if we fell behind, re-anchor to now.
                t.deadlineNanos = now;
            }

            queue.put(t);
        }
    }

    /// Queue element AND returned handle: a single object is both the [DelayQueue]
    /// entry and the [ScheduledFuture] given back to the caller.
    static final class ScheduledTask implements ScheduledFuture<Void> {
        private final Runnable body;
        private final long periodNanos;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private volatile boolean cancelled;
        private volatile boolean done;
        private volatile Future<?> inFlight;
        // INVARIANT: mutated ONLY by the timer thread while dequeued (between take and re-put).
        // cancel() MUST NOT touch it.
        private volatile long deadlineNanos;

        ScheduledTask(Runnable body, long deadlineNanos, long periodNanos) {
            this.body = body;
            this.deadlineNanos = deadlineNanos;
            this.periodNanos = periodNanos;
        }

        // Runs the body on a virtual thread; logs and continues on Throwable
        // so a periodic task is never silently cancelled by a thrown exception.
        @Contract
        void runGuarded() {
            try {
                body.run();
            } catch (Throwable e) {
                log.warn("Scheduled task body threw; task recurrence preserved", e);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof ScheduledTask t) {
                return Long.compare(deadlineNanos, t.deadlineNanos);
            }

            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancelled) {
                return false;
            }

            cancelled = true;
            if (mayInterruptIfRunning) {
                var f = inFlight;

                if (f != null) {
                    f.cancel(true);
                }
            }

            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled || done;
        }

        @Contract
        @Override
        public Void get() {
            throw new UnsupportedOperationException("Periodic scheduled handle has no single result value");
        }

        @Contract
        @Override
        public Void get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("Periodic scheduled handle has no single result value");
        }
    }
}
