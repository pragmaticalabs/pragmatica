/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
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

package org.pragmatica.lang;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.io.CoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.Unit.unit;

/// The fatal-escape hook: every [Error] escaping a continuation reaches the installed [FatalEscapeHandler] exactly
/// once per continuation boundary crossed (a rethrown VME crossing two boundaries is delivered twice), a [RuntimeException] never does, escapes before the first install are replayed to it, and the slot,
/// the buffer and the handler are contained (the drain-and-lock rule).
///
/// Each test starts from a fresh-JVM state through [FatalEscapes#resetForTesting()], because the buffer closes
/// at the first install in a JVM and other test classes in this fork install nothing but do escape errors.
@Timeout(60)
class FatalEscapeHookTest {
    private static final String ORIGIN = "com.example.Origin.method(Origin.java:7)";

    @BeforeEach
    void freshJvmState() {
        FatalEscapes.resetForTesting();
    }

    @AfterEach
    void releaseSlot() {
        FatalEscapes.resetForTesting();
    }

    @Nested
    class Delivery {
        @ParameterizedTest
        @ValueSource(strings = {"assertion", "stackOverflow"})
        void onSuccessEventHandler_reachesHandlerOnce_forError(String kind) throws InterruptedException {
            var recorder = installRecorder();
            var error = error(kind);
            var promise = Promise.<Integer>promise();
            var batchDone = new CountDownLatch(1);

            promise.onSuccess(_ -> raise(error));
            promise.onSuccess(_ -> batchDone.countDown());
            promise.succeed(1);

            assertThat(batchDone.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(recorder.escapes()).hasSize(1);
            assertDelivered(recorder.escapes().getFirst(), error, PromiseImpl.ON_RESULT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"assertion", "stackOverflow"})
        void mapTransformation_reachesHandlerOnce_forError(String kind) {
            var recorder = installRecorder();
            var error = error(kind);
            var source = Promise.<Integer>promise();
            var mapped = source.map(_ -> raise(error));

            resolveExpecting(error, () -> source.succeed(1));

            assertFailedWith(mapped, error);
            assertThat(recorder.escapes()).hasSize(1);
            assertDelivered(recorder.escapes().getFirst(), error, PromiseImpl.REPLACE_RESULT);
            assertThat(recorder.escapes().getFirst().threadName()).isEqualTo(Thread.currentThread().getName());
        }

        @ParameterizedTest
        @ValueSource(strings = {"assertion", "stackOverflow"})
        void asyncConsumer_reachesHandlerOnce_forError(String kind) {
            var recorder = installRecorder();
            var error = error(kind);
            var promise = Promise.<Integer>promise(_ -> raise(error));

            assertFailedWith(promise, error);
            assertThat(recorder.escapes()).hasSize(1);
            assertDelivered(recorder.escapes().getFirst(), error, PromiseImpl.ASYNC);
        }

        @Test
        void rethrownVme_crossingTwoBoundaries_isDeliveredOncePerBoundary() {
            var recorder = installRecorder();
            var error = error("stackOverflow");
            var inner = Promise.<Integer>promise();

            inner.map(_ -> raise(error));

            assertThatThrownBy(() -> Promise.success(1).onResult(_ -> inner.succeed(1))).isSameAs(error);
            assertThat(recorder.escapes()).extracting(FatalEscape::error).containsExactly(error, error);
            assertThat(recorder.escapes()).extracting(FatalEscape::continuation)
                                          .containsExactly(PromiseImpl.REPLACE_RESULT, PromiseImpl.ON_RESULT);
        }

        @Test
        void runtimeException_neverReachesHandler_inAnyContinuation() throws InterruptedException {
            var recorder = installRecorder();
            var exception = new IllegalStateException("not an Error");
            var mapSource = Promise.<Integer>promise();
            var mapped = mapSource.map(_ -> raise(exception));
            var asyncPromise = Promise.<Integer>promise(_ -> raise(exception));
            var eventSource = Promise.<Integer>promise();
            var batchDone = new CountDownLatch(1);

            eventSource.onSuccess(_ -> raise(exception));
            eventSource.onSuccess(_ -> batchDone.countDown());
            mapSource.succeed(1);
            eventSource.succeed(1);

            assertThat(mapped.await().isFailure()).isTrue();
            assertThat(asyncPromise.await().isFailure()).isTrue();
            assertThat(batchDone.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(recorder.escapes()).isEmpty();
            assertThat(FatalEscapes.unowned()).isZero();
        }
    }

    @Nested
    class Buffer {
        @Test
        void errorBeforeInstall_isReplayedAtInstall_once() {
            var error = error("assertion");
            var mapped = Promise.success(1).map(_ -> raise(error));

            assertFailedWith(mapped, error);

            var recorder = installRecorder();

            assertThat(recorder.escapes()).hasSize(1);
            assertDelivered(recorder.escapes().getFirst(), error, PromiseImpl.REPLACE_RESULT);
            assertThat(FatalEscapes.unowned()).isZero();
        }

        @Test
        void errorsBeyondCapacity_areCountedNotReplayed() {
            IntStream.range(0, FatalEscapes.BUFFER_CAPACITY + 4)
                     .forEach(i -> FatalEscapes.escaped("test", new AssertionError("e" + i), ORIGIN));

            var recorder = installRecorder();

            assertThat(FatalEscapes.BUFFER_CAPACITY).isEqualTo(16);
            assertThat(recorder.escapes()).hasSize(16);
            assertThat(FatalEscapes.unowned()).isEqualTo(4);
        }

        @Test
        void secondInstallAfterUninstall_receivesNoReplay() {
            FatalEscapes.escaped("test", new AssertionError("buffered"), ORIGIN);

            var first = new Recorder();
            var registration = Promise.installFatalEscapeHandler(first).unwrap();

            assertThat(first.escapes()).hasSize(1);
            assertThat(registration.uninstall().isSuccess()).isTrue();

            var second = installRecorder();

            assertThat(second.escapes()).isEmpty();
        }

        @Test
        void errorAfterUninstall_isCountedUnowned_neverReplayedToNextHandler() {
            var registration = Promise.installFatalEscapeHandler(new Recorder()).unwrap();

            assertThat(registration.uninstall().isSuccess()).isTrue();

            FatalEscapes.escaped("test", new AssertionError("between two tests"), ORIGIN);

            assertThat(FatalEscapes.unowned()).isEqualTo(1);

            var next = installRecorder();

            assertThat(next.escapes()).isEmpty();
        }

        /// An escape issued while `install` is paused between setting the slot and taking the buffer sees the
        /// slot set and is delivered directly, without the lock that `install` holds: once, and not deadlocked.
        @Test
        void escapeDuringPausedInstall_isDeliveredOnce() throws InterruptedException {
            var buffered = new AssertionError("buffered before install");
            var during = new AssertionError("during the paused install");
            var escaper = Thread.ofPlatform().unstarted(() -> FatalEscapes.escaped("test", during, ORIGIN));
            var escaperFinished = new AtomicInteger();

            FatalEscapes.escaped("test", buffered, ORIGIN);
            FatalEscapes.installPause = () -> pauseWith(escaper, escaperFinished);

            var recorder = installRecorder();

            assertThat(escaperFinished.get()).as("the escape in the pause finished without the install's lock")
                                            .isEqualTo(1);
            assertThat(recorder.escapes()).extracting(FatalEscape::error)
                                          .containsExactlyInAnyOrder(buffered, during);
            assertThat(FatalEscapes.unowned()).isZero();
        }

        /// 1000 escapes on 8 threads, released by a spin barrier together with an install that lands part-way
        /// through; 200 rounds. Every escape is delivered exactly once or counted unowned (the pre-install escapes
        /// beyond the buffer), never both, never neither.
        @Test
        void concurrentEscapesAndInstall_eachDeliveredExactlyOnceOrCounted() throws InterruptedException {
            for (int round = 0; round < 200; round++) {
                FatalEscapes.resetForTesting();
                runRound(round);
            }
        }

        private static void runRound(int round) throws InterruptedException {
            var threads = 8;
            var perThread = 125;
            var total = threads * perThread;
            var error = new AssertionError("concurrent");
            var deliveries = new AtomicReferenceArray<Integer>(total);
            var duplicates = new AtomicInteger();
            var issued = new AtomicInteger();
            var barrier = new AtomicInteger();
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            FatalEscapeHandler counting = escape -> countDelivery(deliveries, duplicates, escape);
            var workers = IntStream.range(0, threads)
                                   .mapToObj(t -> Thread.ofPlatform()
                                                        .start(() -> escapeMany(t, perThread, error, issued, barrier,
                                                                                threads + 1, deadline)))
                                   .toList();

            spinUntil(barrier, threads + 1, deadline);
            spinUntilIssued(issued, round % 50, deadline);

            var registration = Promise.installFatalEscapeHandler(counting).unwrap();

            for (var worker : workers) {
                worker.join(TimeUnit.SECONDS.toMillis(20));
            }

            var delivered = IntStream.range(0, total)
                                     .filter(i -> deliveries.get(i) != null)
                                     .count();

            assertThat(registration.uninstall().isSuccess()).isTrue();
            assertThat(duplicates.get()).as("round %d: duplicated deliveries", round).isZero();
            assertThat(delivered + FatalEscapes.unowned()).as("round %d: delivered %d + unowned %d",
                                                              round, delivered, FatalEscapes.unowned())
                                                          .isEqualTo(total);
        }

        private static void escapeMany(int thread, int count, Error error, AtomicInteger issued, AtomicInteger barrier,
                                       int parties, long deadline) {
            spinUntil(barrier, parties, deadline);

            for (int i = 0; i < count; i++) {
                FatalEscapes.escaped("test", error, String.valueOf(thread * count + i));
                issued.incrementAndGet();
            }
        }

        private static Unit countDelivery(AtomicReferenceArray<Integer> deliveries, AtomicInteger duplicates,
                                          FatalEscape escape) {
            var index = Integer.parseInt(escape.origin());

            if (!deliveries.compareAndSet(index, null, index)) {
                duplicates.incrementAndGet();
            }

            return unit();
        }

        /// Spin barrier: arrive, then spin until every party has arrived. A thread barrier lets the parties
        /// drift apart by a scheduling quantum, which is wider than the race this test is after.
        private static void spinUntil(AtomicInteger barrier, int parties, long deadline) {
            barrier.incrementAndGet();

            while (barrier.get() < parties) {
                failPast(deadline);
                Thread.onSpinWait();
            }
        }

        private static void spinUntilIssued(AtomicInteger issued, int target, long deadline) {
            while (issued.get() < target) {
                failPast(deadline);
                Thread.onSpinWait();
            }
        }

        private static void failPast(long deadline) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("spin deadline passed");
            }
        }
    }

    @Nested
    class Slot {
        @Test
        void secondInstall_isRefused_andUninstallFreesTheSlot() {
            var first = Promise.installFatalEscapeHandler(new Recorder()).unwrap();
            var refused = Promise.installFatalEscapeHandler(new Recorder());

            assertThat(refused.isFailure()).isTrue();
            refused.onFailure(cause -> assertThat(cause).isSameAs(FatalEscapeError.ALREADY_INSTALLED));

            var holder = new Recorder();

            FatalEscapes.escaped("test", new AssertionError("to the first"), ORIGIN);
            assertThat(holder.escapes()).isEmpty();
            assertThat(first.uninstall().isSuccess()).isTrue();

            var second = Promise.installFatalEscapeHandler(holder).unwrap();

            FatalEscapes.escaped("test", new AssertionError("to the second"), ORIGIN);
            assertThat(holder.escapes()).hasSize(1);
            assertThat(second.uninstall().isSuccess()).isTrue();
        }

        @Test
        void staleRegistration_cannotUninstallTheNextHolder() {
            var handler = new Recorder();
            var stale = Promise.installFatalEscapeHandler(handler).unwrap();

            assertThat(stale.uninstall().isSuccess()).isTrue();

            var current = Promise.installFatalEscapeHandler(handler).unwrap();
            var refused = stale.uninstall();

            assertThat(refused.isFailure()).isTrue();
            refused.onFailure(cause -> assertThat(cause).isSameAs(FatalEscapeError.NOT_INSTALLED));
            assertThat(Promise.installFatalEscapeHandler(new Recorder()).isFailure()).as("slot still held").isTrue();
            assertThat(current.uninstall().isSuccess()).isTrue();
        }
    }

    @Nested
    class Containment {
        @ParameterizedTest
        @ValueSource(strings = {"assertion", "stackOverflow"})
        void throwingHandler_isContained_andEscapeProceeds(String kind) {
            var calls = new AtomicInteger();
            FatalEscapeHandler throwing = _ -> calls.incrementAndGet() > 0
                                               ? raise(new AssertionError("handler failed"))
                                               : unit();
            var error = error(kind);
            var source = Promise.<Integer>promise();
            var mapped = source.map(_ -> raise(error));

            Promise.installFatalEscapeHandler(throwing).unwrap();
            resolveExpecting(error, () -> source.succeed(1));

            assertThat(calls.get()).isEqualTo(1);
            assertFailedWith(mapped, error);
        }

        /// A handler that itself runs a continuation from which an Error escapes: the inner escape does not reach
        /// the handler again (no recursion), and the outer escape still fails its dependent / rethrows its VME.
        @ParameterizedTest
        @ValueSource(strings = {"assertion", "stackOverflow"})
        void handlerEscapingAnError_doesNotRecurse(String kind) {
            var calls = new AtomicInteger();
            var inner = new AtomicInteger();
            FatalEscapeHandler escaping = _ -> escapeInside(calls, inner);
            var error = error(kind);
            var source = Promise.<Integer>promise();
            var mapped = source.map(_ -> raise(error));

            Promise.installFatalEscapeHandler(escaping).unwrap();
            resolveExpecting(error, () -> source.succeed(1));

            assertThat(calls.get()).isEqualTo(1);
            assertThat(inner.get()).as("the handler's own continuation failed").isEqualTo(1);
            assertFailedWith(mapped, error);
        }

        private static Unit escapeInside(AtomicInteger calls, AtomicInteger inner) {
            calls.incrementAndGet();
            Promise.success(1)
                   .map(_ -> raise(new AssertionError("inside the handler")))
                   .onFailure(_ -> inner.incrementAndGet());

            return unit();
        }
    }

    private record Recorder(List<FatalEscape> escapes) implements FatalEscapeHandler {
        Recorder() {
            this(new CopyOnWriteArrayList<>());
        }

        @Override
        public Unit onFatalEscape(FatalEscape escape) {
            escapes.add(escape);

            return unit();
        }
    }

    private static Recorder installRecorder() {
        var recorder = new Recorder();

        Promise.installFatalEscapeHandler(recorder).unwrap();

        return recorder;
    }

    private static Error error(String kind) {
        var error = "assertion".equals(kind)
                    ? (Error) new AssertionError("escaped assertion")
                    : new StackOverflowError("escaped stack overflow");

        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.example.Origin", "method", "Origin.java", 7)});

        return error;
    }

    private static <T> T raise(Throwable throwable) {
        if (throwable instanceof Error error) {
            throw error;
        }

        throw (RuntimeException) throwable;
    }

    /// A VirtualMachineError is rethrown by the continuation boundary on the resolving thread; any other Error
    /// is not.
    private static void resolveExpecting(Error error, Runnable resolve) {
        if (error instanceof VirtualMachineError) {
            assertThatThrownBy(resolve::run).isSameAs(error);
        } else {
            resolve.run();
        }
    }

    private static void assertFailedWith(Promise<?> promise, Error error) {
        var result = promise.await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(CoreError.Exception.class,
                                                                           exception -> assertThat(exception.cause()).isSameAs(error)));
    }

    private static void assertDelivered(FatalEscape escape, Error error, String continuation) {
        assertThat(escape.error()).isSameAs(error);
        assertThat(escape.continuation()).isEqualTo(continuation);
        assertThat(escape.origin()).isEqualTo(ORIGIN);
        // Executor threads are virtual and unnamed, so only the map arm, run on this thread, pins the name.
        assertThat(escape.threadName()).isNotNull();
    }

    private static Unit pauseWith(Thread escaper, AtomicInteger finished) {
        finished.set(runAndJoin(escaper));

        return unit();
    }

    private static int runAndJoin(Thread thread) {
        thread.start();

        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return thread.isAlive()
               ? 0
               : 1;
    }
}
