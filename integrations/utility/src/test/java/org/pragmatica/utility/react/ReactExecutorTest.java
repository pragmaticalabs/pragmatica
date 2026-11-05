// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.utility.react;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Unit;
import org.pragmatica.testlogging.LogCapture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReactExecutorTest {
    private final AtomicBoolean engineAlive = new AtomicBoolean(true);
    private final AtomicBoolean nodeStopping = new AtomicBoolean(false);
    private final List<Throwable> fatal = new CopyOnWriteArrayList<>();
    private final ReactExecutor executor = ReactExecutor.reactExecutor("test",
                                                                       ReactGate.reactGate(engineAlive::get,
                                                                                           nodeStopping::get),
                                                                       this::recordFatal);

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void react_runsInSubmissionOrder_onOneThread() throws InterruptedException {
        var order = new CopyOnWriteArrayList<Integer>();
        var threads = new CopyOnWriteArrayList<String>();

        for (int i = 0; i < 50; i++) {
            var index = i;
            executor.react("r" + i, () -> record(order, threads, index));
        }
        drain();

        assertThat(order).hasSize(50).isSorted();
        assertThat(threads).containsOnly("react-test");
    }

    @Test
    void react_gateReadWhenReactionRuns_queuedBeforeStopIsDropped() throws InterruptedException {
        var release = hold();
        var calls = new CopyOnWriteArrayList<String>();

        executor.react("side-effect", () -> calls.add("call"));
        assertThat(executor.queueDepth()).isEqualTo(1);
        engineAlive.set(false);
        release.countDown();
        drain();

        assertThat(calls).isEmpty();
        assertThat(executor.droppedEngineStopped()).isEqualTo(1);
    }

    @Test
    void react_nodeStopping_dropsReaction() throws InterruptedException {
        var calls = new CopyOnWriteArrayList<String>();

        nodeStopping.set(true);
        executor.react("side-effect", () -> calls.add("call"));
        drain();

        assertThat(calls).isEmpty();
        assertThat(executor.droppedEngineStopped()).isEqualTo(1);
    }

    @Test
    void react_engineAlive_runsAndCountsNothing() throws InterruptedException {
        var calls = new CopyOnWriteArrayList<String>();

        executor.react("side-effect", () -> calls.add("call"));
        drain();

        assertThat(calls).containsExactly("call");
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    @Test
    void undo_isNeverGated() throws InterruptedException {
        var calls = new CopyOnWriteArrayList<String>();

        engineAlive.set(false);
        executor.undo("cancel", () -> calls.add("undo"));
        drain();

        assertThat(calls).containsExactly("undo");
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    @Test
    void queueDepth_andHighWater_trackQueuedReactions() throws InterruptedException {
        var release = hold();

        executor.react("a", () -> {});
        executor.react("b", () -> {});
        executor.react("c", () -> {});
        assertThat(executor.queueDepth()).isEqualTo(3);
        assertThat(executor.highWater()).isEqualTo(3);
        release.countDown();
        drain();

        assertThat(executor.queueDepth()).isZero();
        assertThat(executor.highWater()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void runtimeException_isIsolated_nextReactionRuns() throws InterruptedException {
        var calls = new CopyOnWriteArrayList<String>();

        executor.react("throws", ReactExecutorTest::throwRuntime);
        executor.react("next", () -> calls.add("next"));
        drain();

        assertThat(calls).containsExactly("next");
        assertThat(fatal).isEmpty();
    }

    @Test
    void error_goesToFailureSink() throws InterruptedException {
        executor.react("errors", ReactExecutorTest::throwError);
        drain();

        assertThat(fatal).hasSize(1).first().isInstanceOf(AssertionError.class);
    }

    @Test
    void closed_reactIsCountedAsDropped_undoRunsOnCaller() {
        var calls = new CopyOnWriteArrayList<String>();

        executor.close();
        executor.react("late", () -> calls.add("react"));
        executor.undo("late-undo", () -> calls.add(Thread.currentThread().getName()));

        assertThat(calls).containsExactly(Thread.currentThread().getName());
        assertThat(executor.droppedEngineStopped()).isEqualTo(1);
    }

    @Test
    void inline_runsOnCaller_ungated() {
        var calls = new CopyOnWriteArrayList<String>();
        var inline = ReactExecutor.inline("inline");

        inline.react("r", () -> calls.add(Thread.currentThread().getName()));

        assertThat(calls).containsExactly(Thread.currentThread().getName());
    }

    private CountDownLatch hold() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        executor.undo("hold", () -> awaitRelease(started, release));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        return release;
    }

    /// The drop WARN is once per closed run: two drops in one closed run log once; after the gate opens and a reaction
    /// runs, the next closed run logs again. Mutations: the flag is never reset (second run logs nothing, rx04) /
    /// every drop logs (first run logs twice, rx05).
    @Test
    void dropWarning_isLoggedOncePerClosedRun() throws InterruptedException {
        try (var capture = LogCapture.capture(ReactExecutor.class, Level.TRACE, false)) {
            engineAlive.set(false);
            executor.react("first", () -> {});
            executor.react("second", () -> {});
            drain();
            assertThat(dropWarnings(capture)).as("two drops in one closed run").hasSize(1);

            engineAlive.set(true);
            executor.react("runs", () -> {});
            drain();
            engineAlive.set(false);
            executor.react("third", () -> {});
            drain();

            assertThat(dropWarnings(capture)).as("a new closed run logs again").hasSize(2);
            assertThat(executor.droppedEngineStopped()).isEqualTo(3);
        }
    }

    private static List<LogCapture.Entry> dropWarnings(LogCapture capture) {
        return capture.entries()
                      .stream()
                      .filter(entry -> entry.level() == Level.WARN && entry.message().contains("drops side-effect reactions"))
                      .toList();
    }

    private void drain() throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private static void awaitRelease(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void record(List<Integer> order, List<String> threads, int index) {
        order.add(index);
        threads.add(Thread.currentThread().getName());
    }

    private static void throwRuntime() {
        throw new IllegalStateException("isolated");
    }

    private static void throwError() {
        throw new AssertionError("fatal");
    }

    private Unit recordFatal(String component, String reaction, Throwable error) {
        fatal.add(error);

        return Unit.unit();
    }
}
