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
package org.pragmatica.consensus.leader.fsm;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The one serial executor of a node's leader election (#3587): every leader-election event of the node, whatever thread
/// delivers it (KV notification, transport, SWIM, quorum notification, timers, proposal completion, stop), runs as a task
/// here, so a handler's check-then-act is atomic against every other event of the node.
///
/// GUARANTEE: every task runs on one drainer at a time, in submission order (FIFO across all submitters), never two at once,
/// and no task runs after [#close]. MECHANISM: a submitter enqueues, then tries to become the drainer by a compare-and-set on
/// [#drainer]; the winner runs queued tasks on its own thread until the queue is empty, a loser returns at once. A drainer
/// that releases re-checks the queue and takes the drain back if a task arrived during the release, so no task is stranded.
///
/// Consequences the callers rely on:
/// - Run-to-completion: a task submitted from inside a task runs AFTER the current one, never nested inside it.
/// - A follow-up event an FSM hook decides is not a task here: the FSM dispatches it inside the running task, when the
///   dispatch whose transition ran the hook returns ([LeaderElectionContext#followUp], `Fsm#followUp`), so it runs
///   after that transition and before any other event of the node, queued or not.
/// - Never waits: [#execute] does not block on another drainer, so it cannot close a lock cycle with a caller that holds a
///   lock while a task's listener (a leader-change subscriber) waits on that lock. A submitter that finds another drainer
///   returns before its task ran; the running drainer runs it. No production caller reads its own write back.
/// - No owned thread. [#close] is called from inside the stop task, after the stop event was handled: every task submitted
///   before the stop task ran in order before it, and every task after it (already queued, or submitted later) is dropped,
///   logged at DEBUG, never run. Shutdown therefore drains up to the stop and rejects the rest, deterministically.
///
/// A task that throws is logged at ERROR and the drain continues with the next task, for any [Throwable] (FER, degrade
/// forward: the failed event is lost, every later event still runs): otherwise one failing task would strand the queue
/// (no drainer, queued tasks never run). Pinned by `LeaderSerialDispatchCensusTest#failingTask_doesNotStallTheQueue`. A [VirtualMachineError] is logged and then
/// propagates to the drainer's caller; the drainer is released on EVERY exit (a `finally` around the drain).
///
/// The same mechanism as SWIM's `org.pragmatica.swim.SerialExecutor` (#3529), without its test-only quiescing mode and with
/// the stop rejection: the two modules share no dependency, and a library-wide executor is a separate design (#3587 ruling).
final class SerialExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(SerialExecutor.class);

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Thread> drainer = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private SerialExecutor() {}

    static SerialExecutor serialExecutor() {
        return new SerialExecutor();
    }

    /// Run `task` serially with every other task of this executor, without ever waiting. From inside a task: queued, runs
    /// after the current one. From outside: queued, then this thread drains the queue to empty if no other thread is
    /// draining; otherwise it returns at once and the running drainer runs the task. After [#close]: dropped.
    @Contract
    void execute(Runnable task) {
        if (closed.get()) {
            LOG.debug("Leader election serial executor: stopped, dropping task {}", task);

            return;
        }

        queue.add(task);
        if (isConfined()) {
            return;
        }

        drainWhileOwned();
    }

    /// `true` when the calling thread is inside a task of this executor.
    boolean isConfined() {
        return drainer.get() == Thread.currentThread();
    }

    /// Reject every task from now on: those still queued are dropped as the drain reaches them, later ones at submission.
    @Contract
    void close() {
        closed.set(true);
    }

    private void drainWhileOwned() {
        var self = Thread.currentThread();

        while (!queue.isEmpty() && drainer.compareAndSet(null, self)) {
            drainAndRelease();
        }
    }

    /// Drain while owning [#drainer], then release it on every exit, a propagating [VirtualMachineError] included: a
    /// drainer left pinned would make every later submission enqueue and return, and nothing would drain again.
    private void drainAndRelease() {
        try {
            drainQueue();
        } finally {
            drainer.set(null);
        }
    }

    private void drainQueue() {
        for (var task = queue.poll(); task != null; task = queue.poll()) {
            runUnlessClosed(task);
        }
    }

    private void runUnlessClosed(Runnable task) {
        if (closed.get()) {
            LOG.debug("Leader election serial executor: stopped, dropping queued task {}", task);

            return;
        }

        runLogged(task);
    }

    /// Run one task: any [Throwable] is logged and absorbed so the drain continues; a [VirtualMachineError] is logged and
    /// rethrown; the caller's `finally` releases the drainer as it propagates.
    @SuppressWarnings("JBCT-EX-01")  // #3587: a VM error is never absorbed; it propagates after logging (as SWIM's, #3529)
    private static void runLogged(Runnable task) {
        try {
            task.run();
        } catch (VirtualMachineError e) {
            LOG.error("Leader election serial executor: task {} failed with a VM error; releasing the drainer and rethrowing",
                      task,
                      e);

            throw e;
        } catch (Throwable e) {
            LOG.error("Leader election serial executor: task {} failed; the drain continues with the next task", task, e);
        }
    }
}
