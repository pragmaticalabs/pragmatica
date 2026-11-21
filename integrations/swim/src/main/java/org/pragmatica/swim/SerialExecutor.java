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
package org.pragmatica.swim;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The one serial executor that confines every mutation of SWIM member and suspicion state (#3529).
///
/// GUARANTEE: every task runs on one drainer at a time, in submission order (FIFO across all submitters), never
/// two at once. A submitter outside a task sees its own write on return when it drained that task itself;
/// otherwise (another thread was draining) the write becomes visible later, once that drainer reaches it.
/// MECHANISM: a submitter enqueues, then tries to become the drainer by a compare-and-set on [#drainer]. The
/// winner runs queued tasks on its own thread until the queue is empty; a loser returns at once. A drainer that
/// releases re-checks the queue and takes the drain back if a task arrived during the release, so no task is
/// stranded. The drainer field also names the thread that is inside a task, which is what [#isConfined] reads.
///
/// Consequences the callers rely on:
/// - Run-to-completion: a task submitted from inside a task (a listener re-entering the protocol) runs AFTER
///   the current task, never nested inside it, so a read-decide-write sequence cannot be interleaved.
/// - Never waits: [#execute] does not block on another drainer, so it cannot close a lock cycle with a caller
///   that holds a lock (the SWIM announce runs under the node's assignment gate) while a task's listener waits
///   on that lock. No production caller reads its own write back after submitting.
/// - No owned thread: nothing to shut down. The queue is empty whenever no drainer is active, so the protocol's
///   stop has nothing to drain or reject; a task submitted after stop runs like any other (as onMessage did).
///
/// A task that throws is logged and the drain continues: otherwise one failing task would strand the queue
/// (no drainer, queued tasks never run) — the confinement must not turn one failure into a total stall.
final class SerialExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(SerialExecutor.class);
    private static final long CALL_WAIT_NANOS = 50_000L;

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Thread> drainer = new AtomicReference<>();

    private SerialExecutor() {}

    static SerialExecutor serialExecutor() {
        return new SerialExecutor();
    }

    /// Run `task` serially with every other task of this executor, without ever waiting. From inside a task:
    /// queued, runs after the current one. From outside: queued, then this thread drains the queue to empty if no
    /// other thread is draining; otherwise it returns at once and the running drainer runs the task.
    @Contract
    void execute(Runnable task) {
        queue.add(task);
        if (isConfined()) {
            return;
        }

        drainWhileOwned();
    }

    /// TEST seams only: run `action` serially and return its value. Unlike [#execute] it waits until the action
    /// ran (strict, no escape), so it must not be called from inside a task (it would wait on itself) — that is an
    /// [IllegalStateException], a test-wiring defect rather than a runtime condition. Production code never calls
    /// it: a production wait on another drainer is the lock-cycle risk [#execute] is built to avoid.
    <T> T call(Supplier<T> action) {
        if (isConfined()) {
            throw new IllegalStateException("SerialExecutor.call from inside a task would wait on itself");
        }

        var result = new AtomicReference<T>();
        var done = new AtomicBoolean();

        queue.add(() -> runAndMark(action, result, done));
        while (!done.get()) {
            drainWhileOwned();
            parkUnlessDone(done);
        }

        return result.get();
    }

    /// `true` when the calling thread is inside a task of this executor.
    boolean isConfined() {
        return drainer.get() == Thread.currentThread();
    }

    private static <T> void runAndMark(Supplier<T> action, AtomicReference<T> result, AtomicBoolean done) {
        try {
            result.set(action.get());
        } finally {
            done.set(true);
        }
    }

    private static void parkUnlessDone(AtomicBoolean done) {
        if (!done.get()) {
            LockSupport.parkNanos(CALL_WAIT_NANOS);
        }
    }

    private void drainWhileOwned() {
        var self = Thread.currentThread();

        while (!queue.isEmpty() && drainer.compareAndSet(null, self)) {
            drainQueue();
            drainer.set(null);
        }
    }

    private void drainQueue() {
        for (var task = queue.poll(); task != null; task = queue.poll()) {
            runLogged(task);
        }
    }

    private static void runLogged(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.error("SWIM serial executor: task failed; the drain continues with the next task", e);
        }
    }
}
