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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;


/// #3529: the serial executor's drain contract. Every exit of a drain releases the drainer (an [Error] from a task
/// included), tasks run in submission order, one failing task never stops the drain, and a drainer's exit never
/// strands a task submitted while it released.
class SerialExecutorTest {
    private static final long WAIT_SECONDS = 5;
    private static final int BATCH = 20;
    private static final long RACE_NANOS = TimeUnit.SECONDS.toNanos(15);

    @Test
    void call_fromOutsideATask_returnsTheActionValue() {
        var executor = SerialExecutor.serialExecutor();

        assertThat(executor.call(() -> 7)).isEqualTo(Result.success(7));
    }

    @Test
    void call_fromInsideATask_failsWithTypedCauseInsteadOfRunningInline() {
        var executor = SerialExecutor.serialExecutor();
        var innerRan = new AtomicReference<>(false);
        var inner = new AtomicReference<Result<Integer>>();

        executor.execute(() -> inner.set(executor.call(() -> markRan(innerRan))));
        assertThat(innerRan.get()).isFalse();
        assertThat(inner.get().isFailure()).isTrue();
        inner.get().onFailure(cause -> assertThat(cause).isSameAs(SwimError.General.CALL_FROM_INSIDE_TASK));
    }

    @Test
    void awaitQuiet_onAnIdleExecutor_returnsAtOnce() {
        var executor = SerialExecutor.serialExecutor();

        assertThat(executor.awaitQuiet().isSuccess()).isTrue();
    }

    @Test
    void awaitQuiet_fromInsideATask_failsWithTypedCauseInsteadOfWaiting() {
        var executor = SerialExecutor.serialExecutor();
        var inner = new AtomicReference<Result<Unit>>();

        executor.execute(() -> inner.set(executor.awaitQuiet()));
        assertThat(inner.get().isFailure()).isTrue();
        inner.get().onFailure(cause -> assertThat(cause).isSameAs(SwimError.General.CALL_FROM_INSIDE_TASK));
    }

    /// #3568 A1: `call` returns after its own task, but when another thread is the drainer the follow-up that task
    /// queued is still pending. Control: the call has returned while the follow-up is still running. `awaitQuiet` is
    /// then seen waiting on it (a no-op wait returns at once), and returns only after the follow-up finished.
    @Test
    void awaitQuiet_whileAFollowUpOfACompletedCallIsStillRunning_waitsForTheFollowUp() throws InterruptedException {
        var executor = SerialExecutor.serialExecutor();
        var release = new CountDownLatch(1);
        var followUpStarted = new CountDownLatch(1);
        var followUpGate = new CountDownLatch(1);
        var followUpDone = new AtomicBoolean();
        var callReturned = new AtomicBoolean();
        var holder = holdDrainer(executor, release);
        var caller = Thread.ofPlatform().start(() -> callQueueingAFollowUp(executor, followUpStarted, followUpGate, followUpDone, callReturned));

        awaitTimedWaitingOrDone(caller);
        assertThat(caller.isAlive()).as("control: the call is queued behind the holder, which is the drainer").isTrue();
        release.countDown();
        assertThat(followUpStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the holder drained the call's task and started the follow-up").isTrue();
        caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(callReturned.get()).as("control: the call returned after its own task").isTrue();
        assertThat(followUpDone.get()).as("control: the follow-up it queued is still running").isFalse();

        var quietWaiter = Thread.ofPlatform().start(() -> executor.awaitQuiet());

        awaitTimedWaitingOrDone(quietWaiter);
        assertThat(quietWaiter.isAlive()).as("awaitQuiet waits for the running follow-up").isTrue();
        followUpGate.countDown();
        quietWaiter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(quietWaiter.isAlive()).as("awaitQuiet returned once the follow-up finished").isFalse();
        assertThat(followUpDone.get()).isTrue();
    }

    private static void callQueueingAFollowUp(SerialExecutor executor,
                                              CountDownLatch followUpStarted,
                                              CountDownLatch followUpGate,
                                              AtomicBoolean followUpDone,
                                              AtomicBoolean callReturned) {
        executor.call(() -> queueFollowUp(executor, followUpStarted, followUpGate, followUpDone));
        callReturned.set(true);
    }

    private static int queueFollowUp(SerialExecutor executor,
                                     CountDownLatch followUpStarted,
                                     CountDownLatch followUpGate,
                                     AtomicBoolean followUpDone) {
        executor.execute(() -> runFollowUp(followUpStarted, followUpGate, followUpDone));

        return 1;
    }

    private static void runFollowUp(CountDownLatch followUpStarted, CountDownLatch followUpGate, AtomicBoolean followUpDone) {
        followUpStarted.countDown();
        hold(new CountDownLatch(1), followUpGate);
        followUpDone.set(true);
    }

    private static void awaitTimedWaitingOrDone(Thread thread) {
        await().atMost(Duration.ofSeconds(WAIT_SECONDS))
               .until(() -> thread.getState() == Thread.State.TIMED_WAITING || !thread.isAlive());
    }

    /// F1: an [AssertionError] from a task (a listener's assertion) used to escape the drain before the drainer was
    /// cleared, pinning it to the thrower's thread: every later submission enqueued and returned, nothing drained.
    @Test
    void execute_afterATaskThrewAnError_stillRunsALaterSubmittersTask() throws InterruptedException {
        var executor = SerialExecutor.serialExecutor();
        var ran = new AtomicBoolean();

        runOnOwnThread(() -> executor.execute(SerialExecutorTest::failAssertion));
        runOnOwnThread(() -> executor.execute(() -> ran.set(true)));
        assertThat(ran.get()).as("a task submitted after another task threw an Error still runs").isTrue();
    }

    /// A [VirtualMachineError] is not absorbed: it reaches the drainer's caller, but only after the drainer is
    /// released, so the next submission drains again, the task queued behind the failed one first.
    @Test
    void execute_whenATaskThrowsAVirtualMachineError_propagatesItAndTheNextSubmitDrains() {
        var executor = SerialExecutor.serialExecutor();
        var order = new CopyOnWriteArrayList<String>();

        assertThatThrownBy(() -> executor.execute(() -> enqueueThenOverflow(executor, order))).as("the VM error propagates to the drainer's caller")
                          .isInstanceOf(StackOverflowError.class);
        assertThat(order).as("the task queued behind the failed one has not run yet").isEmpty();
        executor.execute(() -> order.add("later"));
        assertThat(order).as("the next submission drained: the queued task, then its own")
                  .containsExactly("queued", "later");
        assertThat(executor.isConfined()).as("the drainer was released").isFalse();
    }

    /// Lost wakeup: two submitters released by a spin barrier race the drainer's exit, one task each per
    /// round on a fresh executor, so every round races the release; time-bound rather than counted (a counted run of
    /// 2000 x 200 rounds let the `while` -> `if` re-check mutant survive).
    @Test
    void execute_twoSubmittersRacingTheDrainerExit_neverStrandATask() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), SerialExecutorTest::raceTheDrainerExit);
    }

    @Test
    void execute_tasksQueuedBehindAHeldDrainer_runInSubmissionOrder() throws InterruptedException {
        var executor = SerialExecutor.serialExecutor();
        var order = new CopyOnWriteArrayList<Integer>();
        var release = new CountDownLatch(1);
        var holder = holdDrainer(executor, release);

        IntStream.rangeClosed(1, BATCH).forEach(index -> executor.execute(() -> order.add(index)));
        assertThat(order).as("control: the held drainer runs them, not the submitter").isEmpty();
        release.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(order).as("FIFO: submission order").containsExactlyElementsOf(oneTo(BATCH));
    }

    @Test
    void execute_aTaskOfAQueuedBatchThrows_theRestOfTheBatchRunsInOrder() throws InterruptedException {
        var executor = SerialExecutor.serialExecutor();
        var order = new CopyOnWriteArrayList<Integer>();
        var release = new CountDownLatch(1);
        var holder = holdDrainer(executor, release);

        executor.execute(SerialExecutorTest::failAtRuntime);
        IntStream.rangeClosed(2, BATCH).forEach(index -> executor.execute(() -> order.add(index)));
        release.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(order).as("tasks 2..n ran after task 1 threw, in order")
                  .containsExactlyElementsOf(oneTo(BATCH).subList(1, BATCH));
    }

    private static void raceTheDrainerExit() throws InterruptedException {
        var race = new Race();
        var first = Thread.ofPlatform().start(() -> submitEachRound(race));
        var second = Thread.ofPlatform().start(() -> submitEachRound(race));
        var deadline = System.nanoTime() + RACE_NANOS;
        var rounds = 0;
        var stranded = 0;

        while (System.nanoTime() < deadline) {
            stranded += race.runRound();
            rounds++;
        }

        race.stop.set(true);
        first.join();
        second.join();
        assertThat(stranded).as("rounds with a stranded task, of " + rounds).isZero();
    }

    /// One submitter of [#raceTheDrainerExit]: waits on the spin barrier for each round, then submits one task.
    private static void submitEachRound(Race race) {
        for (var round = 1; !race.stop.get(); round++) {
            race.awaitRound(round);
            race.submitUnlessStopped();
        }
    }

    /// Shared state of [#raceTheDrainerExit]; a fresh executor per round.
    private static final class Race {
        private final AtomicReference<SerialExecutor> executor = new AtomicReference<>();
        private final AtomicInteger ran = new AtomicInteger();
        private final AtomicInteger arrived = new AtomicInteger();
        private final AtomicInteger round = new AtomicInteger();
        private final AtomicBoolean stop = new AtomicBoolean();

        /// Release both submitters into one round and wait for both submissions; 1 if a task was stranded.
        int runRound() {
            executor.set(SerialExecutor.serialExecutor());
            ran.set(0);
            arrived.set(0);
            round.incrementAndGet();
            while (arrived.get() < 2) {
                Thread.onSpinWait();
            }

            return ran.get() == 2
                   ? 0
                   : 1;
        }

        void awaitRound(int expected) {
            while (round.get() < expected && !stop.get()) {
                Thread.onSpinWait();
            }
        }

        void submitUnlessStopped() {
            if (stop.get()) {
                return;
            }

            executor.get().execute(ran::incrementAndGet);
            arrived.incrementAndGet();
        }
    }

    /// Start a thread that becomes the drainer and holds it inside a task until `release`; returns once it holds.
    private static Thread holdDrainer(SerialExecutor executor, CountDownLatch release) throws InterruptedException {
        var held = new CountDownLatch(1);
        var holder = Thread.ofPlatform().start(() -> executor.execute(() -> hold(held, release)));

        assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("control: the holder owns the drainer").isTrue();

        return holder;
    }

    private static void hold(CountDownLatch held, CountDownLatch release) {
        held.countDown();
        try {
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runOnOwnThread(Runnable action) throws InterruptedException {
        Thread.ofPlatform().start(action).join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    }

    private static void enqueueThenOverflow(SerialExecutor executor, List<String> order) {
        executor.execute(() -> order.add("queued"));

        throw new StackOverflowError("test: a VM error from a task");
    }

    private static void failAssertion() {
        throw new AssertionError("test: a listener assertion");
    }

    private static void failAtRuntime() {
        throw new IllegalStateException("test: task 1 fails");
    }

    private static List<Integer> oneTo(int last) {
        return IntStream.rangeClosed(1, last)
                        .boxed()
                        .toList();
    }

    private static int markRan(AtomicReference<Boolean> flag) {
        flag.set(true);

        return 1;
    }
}
