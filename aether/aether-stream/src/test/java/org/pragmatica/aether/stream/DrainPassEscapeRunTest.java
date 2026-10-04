// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;

/// #1934: a consumer whose delivery pass THROWS on every attempt backs off exponentially to a cap instead of retrying
/// about 17 times a second, logs the thrown frames once per run, raises `stream-consumer-drain-failing` once after
/// [ConsumerRuntimeState#ESCAPES_BEFORE_WARNING] escapes, and `stream-consumer-drain-restored` once when a pass reads
/// again. The clock and the backoff scheduler are the runtime's seams: nothing here waits for a backoff to elapse, and
/// a pass after a backoff runs only when the test runs the scheduled task.
class DrainPassEscapeRunTest {
    private static final String NOT_LOCAL = "not-local";
    private static final String GROUP = "group-1";

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Long> delays = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final AtomicBoolean throwing = new AtomicBoolean(true);
    private final AtomicInteger reads = new AtomicInteger();

    private StreamPartitionManager manager;
    private ConsumerRuntimeState runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        runtime = new ConsumerRuntimeState(manager,
                                           DeadLetterHandler.deadLetterHandler(),
                                           none(),
                                           none(),
                                           (_, _, _, _) -> read(),
                                           none(),
                                           ConsumerRuntimeState.DEAD_LETTER_APPEND_TIMEOUT,
                                           OperatorWarningSink.handingOffTo(warnings::add),
                                           clock::get,
                                           (task, delay) -> {
                                               delays.add(delay.millis());
                                               scheduled.add(task);
                                           });
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> read() {
        reads.incrementAndGet();

        if (throwing.get()) {
            runawayRecursion(0);
        }

        return Promise.success(List.of());
    }

    private static int runawayRecursion(int depth) {
        return runawayRecursion(depth + 1) + 1;
    }

    @Test
    void escapedPasses_backOffExponentially_toTheCap_andNothingElseStartsAPass() throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);

        for (var escape = 2; escape <= 10; escape++) {
            resumeAndAwaitEscapes(escape);
        }

        assertThat(delays).as("50 ms doubling to the 10 s cap")
                          .containsExactly(50L, 100L, 200L, 400L, 800L, 1_600L, 3_200L, 6_400L, 10_000L, 10_000L);
        settle();
        assertThat(reads.get()).as("one pass per backoff: the 1 ms poll ticks in between start none").isEqualTo(10);
    }

    @Test
    void aRunOfEscapes_raisesDrainFailingOnce_namingTheConsumerAndTheFrames() throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);

        for (var escape = 2; escape <= 8; escape++) {
            resumeAndAwaitEscapes(escape);
        }
        settle();

        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_FAILING))
                .as("raised once, at the %dth escape", ConsumerRuntimeState.ESCAPES_BEFORE_WARNING)
                .singleElement()
                .satisfies(warning -> {
                    assertThat(warning.subject()).isEqualTo(NOT_LOCAL + "[0]/" + GROUP);
                    assertThat(warning.message()).contains("java.lang.StackOverflowError")
                                                 .contains("runawayRecursion");
                });
        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED)).isEmpty();
    }

    @Test
    void aReadAfterAReportedRun_raisesDrainRestoredOnce_andAShortRunRaisesNothing() throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);

        for (var escape = 2; escape <= ConsumerRuntimeState.ESCAPES_BEFORE_WARNING; escape++) {
            resumeAndAwaitEscapes(escape);
        }
        throwing.set(false);
        resumeAndAwaitReads(ConsumerRuntimeState.ESCAPES_BEFORE_WARNING + 1);
        awaitWarnings(2);

        throwing.set(true);
        awaitReadsPastEscapes(delays.size() + 1);
        throwing.set(false);
        resumeAndAwaitReads(reads.get() + 1);
        settle();

        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_FAILING)).hasSize(1);
        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED))
                .as("once, for the reported run; the later one-escape run was never reported")
                .singleElement()
                .satisfies(warning -> assertThat(warning.subject()).isEqualTo(NOT_LOCAL + "[0]/" + GROUP));
    }

    @Test
    void theFirstEscapeOfARun_logsTheThrownFrames_once() throws InterruptedException {
        var lines = new CopyOnWriteArrayList<String>();
        var detach = LogCapture.warningsOf(ConsumerRuntimeState.class, lines);

        try {
            subscribe(NOT_LOCAL);
            awaitEscapes(1);

            for (var escape = 2; escape <= 8; escape++) {
                resumeAndAwaitEscapes(escape);
            }
            settle();
        } finally {
            detach.run();
        }

        var firstEscapeLines = lines.stream().filter(line -> line.contains("retrying with backoff from")).toList();

        assertThat(firstEscapeLines).as("one WARN with frames for the run, not one per pass").hasSize(1);
        assertThat(firstEscapeLines.getFirst()).contains("java.lang.StackOverflowError").contains("runawayRecursion");
    }

    /// Push mode: appends during the backoff only mark the loop dirty. Without the gate, every append started a pass.
    @Test
    void pushMode_appendsDuringTheBackoff_startNoPass() throws InterruptedException {
        manager.createStream(StreamConfig.streamConfig("orders", 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000), "earliest"));
        subscribe("orders");
        awaitEscapes(1);

        for (var i = 0; i < 20; i++) {
            manager.publishLocal("orders", 0, ("e-" + i).getBytes(UTF_8), i);
        }
        settle();

        assertThat(reads.get()).as("the pass that threw, and none for the 20 appends").isEqualTo(1);

        resumeAndAwaitEscapes(2);
        assertThat(reads.get()).isEqualTo(2);
    }

    /// The handler-level catch site: a handler that overflows its stack fails the delivery with its top frames, so the
    /// dead-letter entry names the recursing method.
    @Test
    void handlerOverflow_deadLetterEntry_namesTheRecursingFrame() throws InterruptedException {
        var errors = new CopyOnWriteArrayList<String>();
        var sink = new DeadLetterHandler() {
            @Override
            public Promise<Unit> append(String streamName,
                                        int partition,
                                        long offset,
                                        String failingGroup,
                                        byte[] payload,
                                        String errorMessage,
                                        int attemptCount) {
                errors.add(errorMessage);

                return Promise.unitPromise();
            }

            @Override
            public List<DeadLetterEntry> read(String streamName, int maxCount) {
                return List.of();
            }
        };
        var skipping = StreamConsumerRuntime.streamConsumerRuntime(manager, sink);

        try {
            manager.createStream(StreamConfig.streamConfig("orders", 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000), "earliest"));
            skipping.subscribe("orders",
                               0,
                               ConsumerConfig.consumerConfig(GROUP, 1, ConsumerConfig.ProcessingMode.ORDERED, ConsumerConfig.ErrorStrategy.SKIP),
                               (_, _, _) -> Promise.success(runawayRecursion(0)).mapToUnit());
            manager.publishLocal("orders", 0, "e-0".getBytes(UTF_8), 1L);

            awaitUntil(() -> !errors.isEmpty(), "the dead-letter append");
        } finally {
            skipping.close();
        }

        assertThat(errors.getFirst()).contains("Call overflowed its stack at").contains("runawayRecursion");
    }

    private void subscribe(String stream) {
        runtime.subscribe(stream, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> Promise.unitPromise());
    }

    private List<OperatorWarning> warningsOf(OperatorWarningCode code) {
        return warnings.stream().filter(warning -> warning.code() == code).toList();
    }

    private void awaitEscapes(int count) throws InterruptedException {
        awaitUntil(() -> delays.size() >= count, count + " escaped passes");
        assertThat(delays).hasSize(count);
    }

    private void resumeAndAwaitEscapes(int count) throws InterruptedException {
        runScheduled();
        awaitEscapes(count);
    }

    private void resumeAndAwaitReads(int count) throws InterruptedException {
        runScheduled();
        awaitUntil(() -> reads.get() >= count, count + " reads");
    }

    /// A trigger after the backoff's clock has passed: the poll tick starts the next pass on its own.
    private void awaitReadsPastEscapes(int escapes) throws InterruptedException {
        awaitUntil(() -> delays.size() >= escapes, escapes + " escaped passes");
    }

    private void runScheduled() throws InterruptedException {
        var task = scheduled.poll(5, TimeUnit.SECONDS);

        assertThat(task).as("a pass after the backoff was scheduled").isNotNull();
        task.run();
    }

    private void awaitWarnings(int count) throws InterruptedException {
        awaitUntil(() -> warnings.size() >= count, count + " operator warnings");
    }

    /// Lets the hand-off queue and any stray pass land before a negative assertion.
    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertThat(condition.getAsBoolean()).as("timed out waiting for %s", what).isTrue();
    }
}
