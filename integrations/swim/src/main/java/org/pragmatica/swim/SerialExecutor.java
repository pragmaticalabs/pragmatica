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
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The one serial executor that confines every mutation of SWIM member and suspicion state (#3529).
///
/// GUARANTEE: tasks run one at a time, in submission order (FIFO across all submitters), never two at once.
/// MECHANISM: a submitter enqueues, then tries to become the drainer by a compare-and-set on [#drainer]. The
/// winner runs queued tasks on its own thread until the queue is empty; every other submitter returns at once.
/// A drainer that releases re-checks the queue and takes the drain back if a task arrived during the release,
/// so no task is stranded. The drainer field also names the thread that is inside a task, which is what
/// [#isConfined] reads.
///
/// Consequences the callers rely on:
/// - Run-to-completion: a task submitted from inside a task (a listener re-entering the protocol) runs AFTER
///   the current task, never nested inside it, so a read-decide-write sequence cannot be interleaved.
/// - Uncontended submission runs the task on the caller's thread before `execute` returns: no thread hop, and
///   a single-threaded caller (a test) sees the effect at once. A contended submission returns before the task
///   runs; the current drainer runs it.
/// - No owned thread: nothing to shut down. The queue is empty whenever no drainer is active, so the protocol's
///   stop has nothing to drain or reject; a task submitted after stop runs like any other (as onMessage did).
///
/// A task that throws is logged and the drain continues: otherwise one failing task would strand the queue
/// (no drainer, queued tasks never run) — the confinement must not turn one failure into a total stall.
final class SerialExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(SerialExecutor.class);

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Thread> drainer = new AtomicReference<>();

    private SerialExecutor() {}

    static SerialExecutor serialExecutor() {
        return new SerialExecutor();
    }

    /// Run `task` serially with every other task of this executor: on this thread at once if no task is running,
    /// otherwise after the queued ones, on the running drainer's thread.
    @Contract
    void execute(Runnable task) {
        queue.add(task);
        drainWhileOwned();
    }

    /// `true` when the calling thread is inside a task of this executor.
    boolean isConfined() {
        return drainer.get() == Thread.currentThread();
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
            runOne(task);
        }
    }

    private static void runOne(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.error("SWIM serial executor: task failed; the drain continues with the next task", e);
        }
    }
}
