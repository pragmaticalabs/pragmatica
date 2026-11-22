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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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
/// A task that throws is logged at ERROR with the task's identity and the drain continues with the next task, for
/// any [Throwable] (an [AssertionError] from a listener included): otherwise one failing task would strand the
/// queue (no drainer, queued tasks never run) — the confinement must not turn one failure into a total stall. A
/// [VirtualMachineError] (stack overflow, out of memory) is logged the same way and then propagates to the drainer's
/// caller, because the drainer's thread is not trusted to keep running after one. The drainer is released on EVERY
/// exit (a `finally` around the drain), so the tasks still queued after it run on the next submission.
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
    /// ran (strict, no escape), so it must not be called from inside a task (it would wait on itself) — that is a
    /// failure, [SwimError.General#CALL_FROM_INSIDE_TASK], a test-wiring defect reported as a value, not thrown. Production code never calls
    /// it: a production wait on another drainer is the lock-cycle risk [#execute] is built to avoid.
    <T> Result<T> call(Supplier<T> action) {
        return isConfined()
               ? SwimError.General.CALL_FROM_INSIDE_TASK.result()
               : callOutsideTask(action);
    }

    private <T> Result<T> callOutsideTask(Supplier<T> action) {
        var result = new AtomicReference<T>();
        var done = new AtomicBoolean();

        queue.add(() -> runAndMark(action, result, done));
        while (!done.get()) {
            drainWhileOwned();
            parkUnlessDone(done);
        }

        return Result.success(result.get());
    }

    /// TEST seams only: wait until the executor is quiet, meaning no task is queued and none is running, so every
    /// follow-up a task enqueued from inside (a listener re-entering the protocol) has run as well. [#call] returns
    /// after its own task only; when another thread is the drainer, tasks queued behind it can still be pending.
    /// Quiet is a moment, not a latch: a submission by another thread (the probe tick) after that moment is not
    /// waited for, and a timer-driven task that is not yet queued is not waited for. Same restriction as [#call]:
    /// from inside a task it would wait on itself, so it fails with [SwimError.General#CALL_FROM_INSIDE_TASK].
    /// Production code never calls it.
    Result<Unit> awaitQuiet() {
        return isConfined()
               ? SwimError.General.CALL_FROM_INSIDE_TASK.result()
               : awaitQuietOutsideTask();
    }

    private Result<Unit> awaitQuietOutsideTask() {
        while (!isQuiet()) {
            drainWhileOwned();
            parkUnlessQuiet();
        }

        return Result.unitResult();
    }

    private void parkUnlessQuiet() {
        if (!isQuiet()) {
            LockSupport.parkNanos(CALL_WAIT_NANOS);
        }
    }

    /// Queue first, drainer second: a task polled before the queue read is still running, so the drainer is still
    /// set when it is read (the reverse order could see a null drainer, then a task taken and running, and report quiet).
    private boolean isQuiet() {
        return queue.isEmpty() && drainer.get() == null;
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
            drainAndRelease();
        }
    }

    /// Drain while owning [#drainer], then release it on every exit, a propagating [VirtualMachineError] included:
    /// a drainer left pinned would make every later submission enqueue and return, and nothing would drain again.
    private void drainAndRelease() {
        try {
            drainQueue();
        } finally {
            drainer.set(null);
        }
    }

    private void drainQueue() {
        for (var task = queue.poll(); task != null; task = queue.poll()) {
            runLogged(task);
        }
    }

    /// Run one task: any [Throwable] is logged with the task's identity and absorbed so the drain continues; a
    /// [VirtualMachineError] is logged and rethrown; the caller's `finally` releases the drainer as it propagates.
    @SuppressWarnings("JBCT-EX-01")  // #3529: a VM error is never absorbed; it propagates after logging
    private static void runLogged(Runnable task) {
        try {
            task.run();
        } catch (VirtualMachineError e) {
            LOG.error("SWIM serial executor: task {} failed with a VM error; releasing the drainer and rethrowing",
                      task,
                      e);

            throw e;
        } catch (Throwable e) {
            LOG.error("SWIM serial executor: task {} failed; the drain continues with the next task", task, e);
        }
    }
}
