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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Tests 1-10 of ruling 3754-H on a hand-driven fake seam (no test waits on a clock), tests 8 and 9 on the real
/// [SharedScheduler]. Each test names the one mutant it exists to turn red.
class OwnerLifetimeTest {
    private static final TimeSpan LONG = timeSpan(1).hours();
    private static final long CEILING_SECONDS = 5;

    private enum Form {
        ONE_SHOT,
        FIXED_RATE,
        DELAYED_FIXED_RATE;

        OwnerLifetime.Handle schedule(OwnerLifetime lifetime, Consumer<OwnerLifetime> body) {
            return schedule(lifetime, body, LONG, LONG);
        }

        // `first` is the one-shot delay, the fixed-rate interval or the initial delay; `second` is the interval
        // of the initial-delay form and ignored by the other two.
        OwnerLifetime.Handle schedule(OwnerLifetime lifetime, Consumer<OwnerLifetime> body, TimeSpan first, TimeSpan second) {
            return switch (this) {
                case ONE_SHOT -> lifetime.schedule(body, first);
                case FIXED_RATE -> lifetime.scheduleAtFixedRate(body, first);
                case DELAYED_FIXED_RATE -> lifetime.scheduleAtFixedRate(body, first, second);
            };
        }

        Option<TimeSpan> expectedPeriod(TimeSpan first, TimeSpan second) {
            return switch (this) {
                case ONE_SHOT -> Option.none();
                case FIXED_RATE -> Option.some(first);
                case DELAYED_FIXED_RATE -> Option.some(second);
            };
        }
    }

    // ---- the fake seam -------------------------------------------------------------------------------

    private static final class FakeFuture implements ScheduledFuture<Object> {
        private final FakeSeam seam;
        private final List<Boolean> cancels = new ArrayList<>();

        private FakeFuture(FakeSeam seam) {
            this.seam = seam;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancels.add(mayInterruptIfRunning);
            seam.totalCancels.incrementAndGet();
            seam.onCancel.accept(this);
            return true;
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
            return !cancels.isEmpty();
        }

        @Override
        public boolean isDone() {
            return false;
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

    private static final class FakeSeam implements OwnerLifetime.Seam {
        private final List<Runnable> wrappers = new ArrayList<>();
        private final List<FakeFuture> futures = new ArrayList<>();
        private final List<TimeSpan> initialDelays = new ArrayList<>();
        private final List<Option<TimeSpan>> periods = new ArrayList<>();
        private final AtomicInteger totalCancels = new AtomicInteger();
        private Consumer<Runnable> onEnqueue = _ -> {};
        private Consumer<FakeFuture> onCancel = _ -> {};
        private RuntimeException enqueueFailure;

        @Override
        public ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan initialDelay, Option<TimeSpan> period) {
            if (enqueueFailure != null) {
                throw enqueueFailure;
            }

            var future = new FakeFuture(this);

            wrappers.add(wrapper);
            futures.add(future);
            initialDelays.add(initialDelay);
            periods.add(period);
            onEnqueue.accept(wrapper);
            return future;
        }

        private int enqueues() {
            return wrappers.size();
        }
    }

    private static final class Counter implements Consumer<OwnerLifetime> {
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public void accept(OwnerLifetime lifetime) {
            runs.incrementAndGet();
        }
    }

    // ---- instrument controls: a live lifetime really does schedule and run -------------------------------

    @ParameterizedTest
    @EnumSource(Form.class)
    void schedule_liveLifetime_enqueuesOnceAndRunsBodyWithItsLifetime(Form form) {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var seen = new AtomicReference<OwnerLifetime>();

        var handle = form.schedule(lifetime, seen::set);

        seam.wrappers.getFirst().run();

        assertThat(seam.enqueues()).isEqualTo(1);
        assertThat(handle.isCancelled()).isFalse();
        assertThat(seen.get()).isSameAs(lifetime);
        assertThat(seam.periods.getFirst().isPresent()).isEqualTo(form != Form.ONE_SHOT);
    }

    // ---- the delays reach the seam as given. Mutant: initial delay and interval swapped ---------------------

    @ParameterizedTest
    @EnumSource(Form.class)
    void schedule_anyForm_handsSeamTheCallersInitialDelayAndPeriod(Form form) {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var first = timeSpan(2).hours();
        var second = timeSpan(3).hours();

        form.schedule(lifetime, new Counter(), first, second);

        assertThat(seam.initialDelays).containsExactly(first);
        assertThat(seam.periods).containsExactly(form.expectedPeriod(first, second));
    }

    // ---- the seam gets a plain wrapper, never the handle. Mutant: the entry itself is handed to the seam ------

    @Test
    void schedule_seamWrapper_isNotTheHandle() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);

        lifetime.schedule(new Counter(), LONG);

        assertThat(seam.wrappers.getFirst()).isNotInstanceOf(OwnerLifetime.Handle.class);
    }

    @Test
    void run_periodicEntry_staysInSetAfterRun() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);

        lifetime.scheduleAtFixedRate(new Counter(), LONG);
        seam.wrappers.getFirst().run();

        assertThat(lifetime.size()).isEqualTo(1);
    }

    // ---- test 1: end(), then schedule. Mutant: drop the first read ------------------------------------

    @ParameterizedTest
    @EnumSource(Form.class)
    void schedule_afterEnd_neverEnqueuesAndHandleIsCancelled(Form form) {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);

        lifetime.end();

        var handle = form.schedule(lifetime, new Counter());

        assertThat(seam.enqueues()).isZero();
        assertThat(handle.isCancelled()).isTrue();
        assertThat(lifetime.size()).isZero();
    }

    // ---- test 2: the wrapper read. Mutant: drop the wrapper read -----------------------------------------

    @ParameterizedTest
    @EnumSource(Form.class)
    void wrapper_afterEndWithCancelIneffective_bodyDoesNotRun(Form form) {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var counter = new Counter();

        form.schedule(lifetime, counter);

        var wrapper = seam.wrappers.getFirst();

        lifetime.end();
        wrapper.run();

        assertThat(counter.runs.get()).isZero();
    }

    // ---- test 3: the re-read. Mutants: drop the step-5 re-read; swap steps 4 and 5 -----------------------

    @ParameterizedTest
    @EnumSource(Form.class)
    void schedule_endDuringEnqueue_futureCancelledOnceWithoutInterrupt(Form form) {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);

        seam.onEnqueue = _ -> lifetime.end();

        var handle = form.schedule(lifetime, new Counter());

        assertThat(handle.isCancelled()).isTrue();
        assertThat(seam.enqueues()).isEqualTo(1);
        assertThat(seam.futures.getFirst().cancels).containsExactly(false);
        assertThat(lifetime.size()).isZero();
    }

    // ---- test 4: the sweep. Mutants: (a) drop the sweep; (b) cancel(true) ---------------------------------

    @Test
    void end_threeEntries_sweepCancelsEachWithoutInterruptAndEmptiesSet() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);

        lifetime.schedule(new Counter(), LONG);
        lifetime.scheduleAtFixedRate(new Counter(), LONG);
        lifetime.scheduleAtFixedRate(new Counter(), LONG, LONG);

        lifetime.end();

        assertThat(seam.futures).hasSize(3);
        assertThat(seam.totalCancels.get()).isEqualTo(3);
        seam.futures.forEach(future -> assertThat(future.cancels).containsExactly(false));
        assertThat(lifetime.size()).isZero();
    }

    // ---- test 5: removal on completion. Mutants: (a) drop the removal; (b) no finally ---------------------

    @Test
    void run_oneShots_leaveSetEvenWhenBodyThrows() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var boom = new IllegalStateException("boom");

        lifetime.schedule(new Counter(), LONG);
        lifetime.schedule(_ -> { throw boom; }, LONG);
        lifetime.schedule(new Counter(), LONG);

        assertThat(lifetime.size()).isEqualTo(3);

        seam.wrappers.get(0).run();
        assertThatThrownBy(() -> seam.wrappers.get(1).run()).isSameAs(boom);
        seam.wrappers.get(2).run();

        assertThat(lifetime.size()).isZero();
    }

    // ---- test 6: removal on cancel. Mutant: drop the removal in the handle's cancel ------------------------

    @Test
    void cancel_throughHandle_removesEntryAndCancelsFutureWithoutInterrupt() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var handles = new ArrayList<OwnerLifetime.Handle>();

        for (var i = 0; i < 5; i++) {
            handles.add(lifetime.schedule(new Counter(), LONG));
        }

        assertThat(lifetime.size()).isEqualTo(5);

        handles.forEach(handle -> assertThat(handle.cancel()).isTrue());

        assertThat(lifetime.size()).isZero();
        assertThat(seam.totalCancels.get()).isEqualTo(5);
        seam.futures.forEach(future -> assertThat(future.cancels).containsExactly(false));
        handles.forEach(handle -> assertThat(handle.isCancelled()).isTrue());
        handles.forEach(handle -> assertThat(handle.cancel()).isFalse());
    }

    // ---- cancel after completion. Mutant: a completed one-shot is not marked completed ---------------------

    @Test
    void cancel_afterOneShotCompleted_returnsFalseAndLeavesHandleNotCancelled() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var handle = lifetime.schedule(new Counter(), LONG);

        seam.wrappers.getFirst().run();

        assertThat(handle.cancel()).isFalse();
        assertThat(handle.isCancelled()).isFalse();
    }

    // ---- cancel from inside the running body. Mutant: completion stores COMPLETED instead of compare-and-set -----

    @Test
    void cancel_calledFromInsideRunningOneShotBody_staysCancelledAndLeavesSet() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var handleRef = new AtomicReference<OwnerLifetime.Handle>();
        var cancelResult = new AtomicReference<Boolean>();

        handleRef.set(lifetime.schedule(_ -> cancelResult.set(handleRef.get().cancel()), LONG));

        seam.wrappers.getFirst().run();

        assertThat(cancelResult.get()).isTrue();
        assertThat(handleRef.get().isCancelled()).isTrue();
        assertThat(lifetime.size()).isZero();
    }

    // ---- test 7: register before enqueue. Mutant: register after enqueue ------------------------------------

    @Test
    void schedule_wrapperCompletesInsideEnqueue_setIsEmptyAfterwards() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var counter = new Counter();

        seam.onEnqueue = Runnable::run;

        lifetime.schedule(counter, LONG);

        assertThat(counter.runs.get()).isEqualTo(1);
        assertThat(lifetime.size()).isZero();
    }

    // ---- test 10: every end() sweeps. Mutant: sweep only when the flag write wins its compare-and-set -------

    @Test
    void end_reentrantFromCancel_innerCallReturnsAfterCancellingTheOthers() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var cancelsWhenInnerReturned = new AtomicInteger(-1);
        var reentered = new AtomicInteger();

        lifetime.schedule(new Counter(), LONG);
        lifetime.schedule(new Counter(), LONG);
        lifetime.schedule(new Counter(), LONG);

        seam.onCancel = _ -> reenterOnce(lifetime, seam, reentered, cancelsWhenInnerReturned);

        lifetime.end();

        assertThat(reentered.get()).isEqualTo(1);
        assertThat(cancelsWhenInnerReturned.get()).isEqualTo(3);
        assertThat(lifetime.size()).isZero();
    }

    private static void reenterOnce(OwnerLifetime lifetime, FakeSeam seam, AtomicInteger reentered, AtomicInteger observed) {
        if (reentered.compareAndSet(0, 1)) {
            lifetime.end();
            observed.set(seam.totalCancels.get());
        }
    }

    // ---- seam failure: step 3 throws -------------------------------------------------------------------------

    @Test
    void schedule_seamThrows_entryLeavesSetAndFailureEscapes() {
        var seam = new FakeSeam();
        var lifetime = OwnerLifetime.ownerLifetime(seam);
        var failure = new IllegalStateException("seam refused");

        seam.enqueueFailure = failure;

        assertThatThrownBy(() -> lifetime.schedule(new Counter(), LONG)).isSameAs(failure);
        assertThat(lifetime.size()).isZero();
    }

    // ---- lifetime flag ---------------------------------------------------------------------------------------

    @Test
    void end_calledTwice_isIdempotent() {
        var lifetime = OwnerLifetime.ownerLifetime(new FakeSeam());

        assertThat(lifetime.isEnded()).isFalse();

        lifetime.end();
        lifetime.end();

        assertThat(lifetime.isEnded()).isTrue();
    }

    // ---- test 8 (real scheduler): the Nth body calls end() ----------------------------------------------------

    @Test
    void end_calledFromFixedRateBody_handleIsCancelled() throws InterruptedException {
        var lifetime = OwnerLifetime.ownerLifetime();
        var runs = new AtomicInteger();
        var endReturned = new CountDownLatch(1);

        var handle = lifetime.scheduleAtFixedRate(self -> endOnThirdRun(self, runs, endReturned),
                                                  timeSpan(5).millis());

        assertThat(endReturned.await(CEILING_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(handle.isCancelled()).isTrue();
        assertThat(lifetime.size()).isZero();
    }

    private static void endOnThirdRun(OwnerLifetime self, AtomicInteger runs, CountDownLatch endReturned) {
        if (runs.incrementAndGet() == 3) {
            self.end();
            endReturned.countDown();
        }
    }

    // ---- real scheduler: initial delay and interval reach SharedScheduler in order. Mutant: the two swapped in
    // enqueueOnShared. Swapped, the first run comes after 10 ms; in order, after 10 s. The wait can only false-pass
    // (a swapped run delayed past 300 ms), never false-fail.

    @Test
    void scheduleAtFixedRate_onSharedScheduler_waitsInitialDelayNotInterval() throws InterruptedException {
        var lifetime = OwnerLifetime.ownerLifetime();
        var ran = new CountDownLatch(1);

        var handle = lifetime.scheduleAtFixedRate(_ -> ran.countDown(), timeSpan(10).seconds(), timeSpan(10).millis());

        var ranWithin300Millis = ran.await(300, TimeUnit.MILLISECONDS);

        handle.cancel();

        assertThat(ranWithin300Millis).isFalse();
    }

    // ---- a24 (real scheduler): a one-shot runs once and leaves the set. Mutant: the one-shot branch made periodic ----

    @Test
    void schedule_oneShotOnSharedScheduler_runsOnceAndLeavesSet() throws InterruptedException {
        var lifetime = OwnerLifetime.ownerLifetime();
        var runs = new AtomicInteger();
        var ran = new CountDownLatch(1);
        var laterOneShotRan = new CountDownLatch(1);

        lifetime.schedule(_ -> countRun(runs, ran), timeSpan(5).millis());
        lifetime.schedule(_ -> laterOneShotRan.countDown(), timeSpan(150).millis());

        assertThat(ran.await(CEILING_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(laterOneShotRan.await(CEILING_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitEmpty(lifetime)).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    private static void countRun(AtomicInteger runs, CountDownLatch ran) {
        runs.incrementAndGet();
        ran.countDown();
    }

    // A one-shot leaves the set right after its body returns, on the scheduler thread: spin (core tests cannot use
    // the testing module's PollUntil) with the same ceiling as the latches.
    private static boolean awaitEmpty(OwnerLifetime lifetime) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CEILING_SECONDS);

        while (lifetime.size() != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        return lifetime.size() == 0;
    }

    // ---- test 9 (real scheduler): a schedule call racing end() -------------------------------------------------

    @Test
    void schedule_racingEnd_entryAlwaysCancelledAndSetEmpty() throws Exception {
        var rounds = 1500;
        var executor = Executors.newFixedThreadPool(2);

        try {
            var failures = new ArrayList<String>();

            for (var round = 0; round < rounds; round++) {
                raceOnce(executor, round, failures);
            }

            assertThat(failures).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void raceOnce(ExecutorService executor, int round, List<String> failures)
    throws InterruptedException, ExecutionException {
        var lifetime = OwnerLifetime.ownerLifetime();
        var barrier = new CyclicBarrier(2);
        var handle = new AtomicReference<OwnerLifetime.Handle>();

        var scheduling = executor.submit(() -> {
            barrier.await();
            handle.set(lifetime.schedule(new Counter(), LONG));
            return null;
        });
        var ending = executor.submit(() -> {
            barrier.await();
            lifetime.end();
            return null;
        });

        scheduling.get();
        ending.get();

        if (!handle.get().isCancelled() || lifetime.size() != 0) {
            failures.add("round " + round + ": cancelled=" + handle.get().isCancelled() + " size=" + lifetime.size());
        }
    }
}
