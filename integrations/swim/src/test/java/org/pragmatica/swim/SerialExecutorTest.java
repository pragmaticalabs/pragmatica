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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;


/// #3529, #3568: the serial executor's drain contract and its quiescing mode. Every exit of a drain releases the drainer (an [Error] from a task
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

    /// #3568: a quiescing executor's `call` returns after its own task AND the follow-up that task queued, even when
    /// another thread is the drainer. Control, on the default executor: `call` returns while the follow-up is running.
    @Test
    void call_onADefaultExecutorWhileAnotherThreadDrains_returnsBeforeTheFollowUpFinished() throws InterruptedException {
        var executor = SerialExecutor.serialExecutor();
        var run = followUpRun(executor);

        run.caller().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(run.callReturned().get()).as("the call returned after its own task").isTrue();
        assertThat(run.followUpDone().get()).as("the follow-up it queued is still running").isFalse();
        run.followUpGate().countDown();
        run.holder().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(run.followUpDone().get()).isTrue();
    }

    @Test
    void call_onAQuiescingExecutorWhileAnotherThreadDrains_waitsForTheFollowUp() throws InterruptedException {
        var executor = SerialExecutor.quiescingSerialExecutor();
        var run = followUpRun(executor);

        awaitTimedWaitingOrDone(run.caller());
        assertThat(run.caller().isAlive()).as("the call waits for the running follow-up").isTrue();
        assertThat(run.callReturned().get()).isFalse();
        run.followUpGate().countDown();
        run.caller().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        run.holder().join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(run.caller().isAlive()).as("the call returned once the follow-up finished").isFalse();
        assertThat(run.followUpDone().get()).as("the follow-up had finished when the call returned").isTrue();
    }

    @Test
    void execute_onAQuiescingExecutorWhileAnotherThreadDrains_waitsUntilItsTaskRan() throws InterruptedException {
        var executor = SerialExecutor.quiescingSerialExecutor();
        var release = new CountDownLatch(1);
        var ran = new AtomicBoolean();
        var holder = holdDrainer(executor, release);
        var submitter = Thread.ofPlatform().start(() -> executor.execute(() -> ran.set(true)));

        awaitTimedWaitingOrDone(submitter);
        assertThat(submitter.isAlive()).as("execute waits behind the holder").isTrue();
        assertThat(ran.get()).isFalse();
        release.countDown();
        submitter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(submitter.isAlive()).isFalse();
        assertThat(ran.get()).as("its task ran before execute returned").isTrue();
    }

    @Test
    void execute_fromInsideAQuiescingTask_returnsAtOnceAndRunsAfterTheCurrentTask() {
        var executor = SerialExecutor.quiescingSerialExecutor();
        var order = new CopyOnWriteArrayList<String>();

        assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS), () -> executor.execute(() -> nestedSubmission(executor, order)));
        assertThat(order).as("run-to-completion: nested runs after the outer task ended").containsExactly("outer", "outer-end", "nested");
    }

    @Test
    void call_fromInsideAQuiescingTask_failsWithTypedCause() {
        var executor = SerialExecutor.quiescingSerialExecutor();
        var inner = new AtomicReference<Result<Integer>>();

        executor.execute(() -> inner.set(executor.call(() -> 1)));
        assertThat(inner.get().isFailure()).isTrue();
        inner.get().onFailure(cause -> assertThat(cause).isSameAs(SwimError.General.CALL_FROM_INSIDE_TASK));
    }

    /// #3568 condition (b): the wait is skipped inside a quiescing task of ANY executor. Protocol A's task hops into B
    /// while B's drainer, inside a B task, hops back into A: each waits on the drainer the other holds unless the skip
    /// is thread-wide. Must return (bounded), not deadlock; the mutant "skip only for the same executor" deadlocks here.
    @Test
    void execute_aTaskOfOneQuiescingExecutorHoppingIntoAnotherWhileTheOtherHopsBack_returnsInsteadOfDeadlocking() {
        var a = SerialExecutor.quiescingSerialExecutor();
        var b = SerialExecutor.quiescingSerialExecutor();
        var aInside = new CountDownLatch(1);
        var bInside = new CountDownLatch(1);
        var hops = new AtomicInteger();
        var first = daemon(() -> a.execute(() -> hop(aInside, bInside, b, hops)));
        var second = daemon(() -> b.execute(() -> hop(bInside, aInside, a, hops)));

        assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                                  () -> {
                                      first.join();
                                      second.join();
                                  });
        assertThat(hops.get()).as("both hops ran").isEqualTo(2);
    }

    private static void hop(CountDownLatch inside, CountDownLatch otherInside, SerialExecutor other, AtomicInteger hops) {
        inside.countDown();
        awaitUnchecked(otherInside);
        other.execute(hops::incrementAndGet);
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread daemon(Runnable action) {
        return Thread.ofPlatform().daemon(true).start(action);
    }

    private static void nestedSubmission(SerialExecutor executor, List<String> order) {
        order.add("outer");
        executor.execute(() -> order.add("nested"));
        order.add("outer-end");
    }

    private record FollowUpRun(Thread holder,
                               Thread caller,
                               CountDownLatch followUpGate,
                               AtomicBoolean followUpDone,
                               AtomicBoolean callReturned) {}

    /// A holder drains; a caller's `call` queues behind it and queues a follow-up; the holder is released, runs the
    /// call's task, and is then inside the follow-up (gated). Returns with the follow-up running.
    private static FollowUpRun followUpRun(SerialExecutor executor) throws InterruptedException {
        var release = new CountDownLatch(1);
        var followUpStarted = new CountDownLatch(1);
        var followUpGate = new CountDownLatch(1);
        var followUpDone = new AtomicBoolean();
        var callReturned = new AtomicBoolean();
        var holder = holdDrainer(executor, release);
        var caller = Thread.ofPlatform().daemon(true).start(() -> callQueueingAFollowUp(executor, followUpStarted, followUpGate, followUpDone, callReturned));

        awaitTimedWaitingOrDone(caller);
        assertThat(caller.isAlive()).as("control: the call is queued behind the holder, which is the drainer").isTrue();
        release.countDown();
        assertThat(followUpStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the holder drained the call's task and started the follow-up").isTrue();

        return new FollowUpRun(holder, caller, followUpGate, followUpDone, callReturned);
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
