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
    private final AtomicBoolean failingRead = new AtomicBoolean();
    private final AtomicInteger clockReadsUntilCancel = new AtomicInteger();
    /// Only the thread that armed the countdown counts clock reads: the poll tick reads the clock concurrently.
    private final java.util.concurrent.atomic.AtomicReference<Thread> armedThread = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> atClockRead = new java.util.concurrent.atomic.AtomicReference<>(() -> {});
    /// Runs inside a read, on the pass's own thread, before it throws: how a test cancels the consumer mid-pass.
    private final java.util.concurrent.atomic.AtomicReference<Runnable> duringRead = new java.util.concurrent.atomic.AtomicReference<>(() -> {});
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
                                           ConsumerRuntimeState.DEAD_LETTER_APPEND_TIMEOUT,
                                           this::now,
                                           (task, delay) -> {
                                               delays.add(delay.millis());
                                               scheduled.add(task);
                                           });
        runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    /// The runtime's clock. Armed, it runs `atClockRead` on the Nth read from now: how a test lands a cancel at an exact
    /// point inside the escape bookkeeping, without sleeping.
    private long now() {
        if (Thread.currentThread() == armedThread.get() && clockReadsUntilCancel.get() > 0
            && clockReadsUntilCancel.decrementAndGet() == 0) {
            atClockRead.get().run();
        }

        return clock.get();
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> read() {
        reads.incrementAndGet();
        duringRead.get().run();

        if (failingRead.get()) {
            return StreamError.General.PARTITION_NOT_LOCAL.promise();
        }

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

    /// A consumer cancelled while its failing alert stands gets the recovery, with the reason; an unreported run does not.
    @Test
    void aConsumerCancelledWhileItsFailingAlertStands_getsTheRecovery_namingTheCancellation() throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);
        for (var escape = 2; escape <= ConsumerRuntimeState.ESCAPES_BEFORE_WARNING; escape++) {
            resumeAndAwaitEscapes(escape);
        }
        awaitWarnings(1);

        runtime.unsubscribe(NOT_LOCAL, 0, GROUP);
        awaitWarnings(2);
        settle();

        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED))
                .singleElement()
                .satisfies(warning -> {
                    assertThat(warning.subject()).isEqualTo(NOT_LOCAL + "[0]/" + GROUP);
                    assertThat(warning.message()).contains("was cancelled while its delivery passes were failing");
                });
    }

    @Test
    void anAbandonedConsumer_whileItsFailingAlertStands_getsTheRecovery() throws InterruptedException {
        reportFailingRun();

        runtime.abandon(NOT_LOCAL, 0, GROUP);
        awaitWarnings(2);
        settle();

        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED))
                .singleElement()
                .satisfies(warning -> assertThat(warning.message()).contains("was cancelled while its delivery passes were failing"));
    }

    @Test
    void anIdleReapedConsumer_whileItsFailingAlertStands_getsTheRecovery() throws InterruptedException {
        reportFailingRun();

        runtime.reapIdleConsumers(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
        awaitWarnings(2);
        settle();

        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED))
                .singleElement()
                .satisfies(warning -> assertThat(warning.message()).contains("was cancelled while its delivery passes were failing"));
    }

    /// The consumer is cancelled between the escape that reaches the threshold being counted and its alert being raised:
    /// the cancel hook then finds the run unreported and only logs, so only the recheck after the raise can end the alert.
    /// Deterministic: the clock seam, armed from inside the read, runs the cancel on the Nth clock read of the escape
    /// bookkeeping. Read 2 is after the escape is counted and before it is reported.
    @Test
    void aConsumerCancelledAfterTheThresholdEscapeIsCounted_stillGetsTheRecovery() throws InterruptedException {
        cancelAtClockReadOfTheThresholdEscape(2);

        assertThat(warnings.stream().map(OperatorWarning::code).toList())
                .containsExactly(OperatorWarningCode.STREAM_CONSUMER_DRAIN_FAILING, OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED);
    }

    /// Read 3 is while the alert's message is being built: the cancel lands after the run is marked reported and before
    /// the alert is raised. The sink must still see the failure first and the recovery exactly once, never
    /// [restored, failing, restored] (the hook's recovery before the failure, then the recheck's second one), and never
    /// [restored, failing] (a failure with nothing left to end it).
    @Test
    void aConsumerCancelledWhileTheAlertIsBeingBuilt_getsTheFailureThenOneRecovery() throws InterruptedException {
        cancelAtClockReadOfTheThresholdEscape(3);

        assertThat(warnings.stream().map(OperatorWarning::code).toList())
                .containsExactly(OperatorWarningCode.STREAM_CONSUMER_DRAIN_FAILING, OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED);
    }

    private void cancelAtClockReadOfTheThresholdEscape(int clockRead) throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);
        for (var escape = 2; escape < ConsumerRuntimeState.ESCAPES_BEFORE_WARNING; escape++) {
            resumeAndAwaitEscapes(escape);
        }
        assertThat(warnings).as("control: the alert is not yet raised").isEmpty();
        atClockRead.set(() -> runtime.unsubscribe(NOT_LOCAL, 0, GROUP));
        duringRead.set(() -> {
            armedThread.set(Thread.currentThread());
            clockReadsUntilCancel.set(clockRead);
        });
        runScheduled();
        awaitWarnings(2);
        settle();
    }

    private void reportFailingRun() throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);
        for (var escape = 2; escape <= ConsumerRuntimeState.ESCAPES_BEFORE_WARNING; escape++) {
            resumeAndAwaitEscapes(escape);
        }
        awaitWarnings(1);
    }

    @Test
    void aConsumerCancelledDuringAnUnreportedRun_raisesNoRecovery() throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);
        resumeAndAwaitEscapes(2);

        runtime.unsubscribe(NOT_LOCAL, 0, GROUP);
        settle();

        assertThat(warnings).as("the run never reached the alert, so there is nothing to recover").isEmpty();
    }

    @Test
    void closingTheRuntimeWhileTheFailingAlertStands_getsTheRecovery() throws InterruptedException {
        subscribe(NOT_LOCAL);
        awaitEscapes(1);
        for (var escape = 2; escape <= ConsumerRuntimeState.ESCAPES_BEFORE_WARNING; escape++) {
            resumeAndAwaitEscapes(escape);
        }
        awaitWarnings(1);

        runtime.close();
        awaitWarnings(2);

        assertThat(warningsOf(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED)).hasSize(1);
    }

    /// A read that FAILS (the partition moved, so it is not local) is an ordinary failed promise, not an escaped pass:
    /// it raises no operator warning and starts no escape backoff.
    @Test
    void aFailedRead_raisesNothing_andStartsNoBackoff() throws InterruptedException {
        failingRead.set(true);
        subscribe(NOT_LOCAL);
        awaitUntil(() -> reads.get() >= 3, "three failed reads");
        settle();

        assertThat(warnings).isEmpty();
        assertThat(delays).as("no escape backoff scheduled").isEmpty();
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
