// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BACK_IN_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BEHIND_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_MISSING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_RESTORED;

/// #2211 round 5: a consumer cancelled while a read is still in flight must not open a new behind-the-ring or hole episode
/// when that read answers, and an episode's warning is raised at most once with exactly one recovery, whichever way the
/// cancel hook and a late pass interleave. Each interleaving is forced, not hoped for: the in-flight read is a promise the
/// test completes after the cancel, and the hook is parked inside the log line of its own recovery (after it has taken
/// the episode, before the recovery reaches the sink) while the late pass runs.
class ConsumerEpisodeCancelRaceTest {
    private static final String STREAM = "orders";
    private static final String GROUP = "group-cancel-race";
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final long SETTLE_MS = 300L;

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, AtomicInteger> readsAt = new ConcurrentHashMap<>();
    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        manager.publishLocal(STREAM, 0, "seed".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        Option.option(runtime).onPresent(StreamConsumerRuntime::close);
        manager.close();
    }

    /// The cancel hook closes the episode; a read that was already in flight then answers from the tier. It must not open a
    /// second episode on a consumer that is gone.
    @Test
    void behindRing_aReadAnsweringAfterTheCancel_opensNoSecondEpisode() throws Exception {
        var inFlight = Promise.<StreamPartitionManager.EpochRead> promise();

        startBehindRing(inFlight);
        awaitUntil(() -> count(STREAM_CONSUMER_BEHIND_RING) == 1 && reads(1L) >= 1, "behind the ring, with a read in flight");

        runtime.unsubscribe(STREAM, 0, GROUP).onFailure(cause -> fail(cause.message()));
        awaitUntil(() -> count(STREAM_CONSUMER_BACK_IN_RING) == 1, "the cancel closed the episode");
        inFlight.resolve(Result.success(tierRead(List.of())));
        Thread.sleep(SETTLE_MS);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
    }

    /// The cancel hook has taken the open episode and is parked before its recovery reaches the sink; the in-flight read
    /// answers from the tier meanwhile. One BEHIND, one BACK: the late pass finds the episode taken, not absent.
    @Test
    void behindRing_aReadAnsweringWhileTheHookIsRaisingTheRecovery_raisesNoSecondWarning() throws Exception {
        var inFlight = Promise.<StreamPartitionManager.EpochRead> promise();

        startBehindRing(inFlight);
        awaitUntil(() -> count(STREAM_CONSUMER_BEHIND_RING) == 1 && reads(1L) >= 1, "behind the ring, with a read in flight");

        try (var parked = ParkedLogLine.parkOnce("is no longer behind the ring")) {
            var cancel = Thread.ofVirtual().start(() -> runtime.unsubscribe(STREAM, 0, GROUP));

            assertThat(parked.entered.await(5, TimeUnit.SECONDS)).as("the hook reached its recovery log line").isTrue();
            inFlight.resolve(Result.success(tierRead(List.of())));
            Thread.sleep(SETTLE_MS);
            parked.release.countDown();
            cancel.join();
        }
        Thread.sleep(SETTLE_MS);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
    }

    /// As the first behind-the-ring test, for a hole.
    @Test
    void hole_aReadAnsweringAfterTheCancel_opensNoSecondEpisode() throws Exception {
        var inFlight = Promise.<StreamPartitionManager.EpochRead> promise();

        startAtHole(inFlight);
        awaitUntil(() -> count(STREAM_CONSUMER_SEALED_RANGE_MISSING) == 1 && reads(0L) >= 2, "at the hole, with a read in flight");

        runtime.unsubscribe(STREAM, 0, GROUP).onFailure(cause -> fail(cause.message()));
        awaitUntil(() -> count(STREAM_CONSUMER_SEALED_RANGE_RESTORED) == 1, "the cancel closed the episode");
        inFlight.resolve(Result.failure(hole()));
        Thread.sleep(SETTLE_MS);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_SEALED_RANGE_MISSING, STREAM_CONSUMER_SEALED_RANGE_RESTORED);
    }

    /// As the second behind-the-ring test, for a hole.
    @Test
    void hole_aReadAnsweringWhileTheHookIsRaisingTheRecovery_raisesNoSecondWarning() throws Exception {
        var inFlight = Promise.<StreamPartitionManager.EpochRead> promise();

        startAtHole(inFlight);
        awaitUntil(() -> count(STREAM_CONSUMER_SEALED_RANGE_MISSING) == 1 && reads(0L) >= 2, "at the hole, with a read in flight");

        try (var parked = ParkedLogLine.parkOnce("no longer waits at the hole")) {
            var cancel = Thread.ofVirtual().start(() -> runtime.unsubscribe(STREAM, 0, GROUP));

            assertThat(parked.entered.await(5, TimeUnit.SECONDS)).as("the hook reached its recovery log line").isTrue();
            inFlight.resolve(Result.failure(hole()));
            Thread.sleep(SETTLE_MS);
            parked.release.countDown();
            cancel.join();
        }
        Thread.sleep(SETTLE_MS);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_SEALED_RANGE_MISSING, STREAM_CONSUMER_SEALED_RANGE_RESTORED);
    }

    /// The cancel lands while the opener is inside its own WARN line (claimed, not yet published): the hook finds a claim, not
    /// an episode, and raises nothing; the opener then owes the recovery and raises it, exactly once, last.
    @Test
    void behindRing_aCancelWhileTheOpenerIsRaisingItsWarning_leavesTheOpenerToRaiseTheRecovery() throws Exception {
        var never = Promise.<StreamPartitionManager.EpochRead> promise();

        try (var parked = ParkedLogLine.parkOnce("is behind the ring: its cursor")) {
            start((from, _) -> from == 0L
                               ? Promise.success(tierRead(events(0, 0)))
                               : never);
            assertThat(parked.entered.await(5, TimeUnit.SECONDS)).as("the opener is parked inside its warning").isTrue();

            var cancel = Thread.ofVirtual().start(() -> runtime.unsubscribe(STREAM, 0, GROUP));

            cancel.join(5_000);
            assertThat(cancel.isAlive()).as("the hook ran while the opener was parked").isFalse();
            parked.release.countDown();
            awaitUntil(() -> warnings.size() >= 2, "the warning and the opener's recovery");
        }
        Thread.sleep(SETTLE_MS);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
        assertThat(warnings.getLast().message()).contains("cancelled");
    }

    /// As above, for a hole.
    @Test
    void hole_aCancelWhileTheOpenerIsRaisingItsWarning_leavesTheOpenerToRaiseTheRecovery() throws Exception {
        try (var parked = ParkedLogLine.parkOnce("waits at offset")) {
            start((_, _) -> hole().promise());
            assertThat(parked.entered.await(5, TimeUnit.SECONDS)).as("the opener is parked inside its warning").isTrue();

            var cancel = Thread.ofVirtual().start(() -> runtime.unsubscribe(STREAM, 0, GROUP));

            cancel.join(5_000);
            assertThat(cancel.isAlive()).as("the hook ran while the opener was parked").isFalse();
            parked.release.countDown();
            awaitUntil(() -> warnings.size() >= 2, "the warning and the opener's recovery");
        }
        Thread.sleep(SETTLE_MS);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_SEALED_RANGE_MISSING, STREAM_CONSUMER_SEALED_RANGE_RESTORED);
        assertThat(warnings.getLast().message()).contains("cancelled");
    }

    /// A read served from the RING after the cancel closed the episode neither un-cancels the consumer nor raises a second
    /// recovery.
    @Test
    void behindRing_aRingReadAfterTheCancel_raisesNoSpuriousRecovery() throws Exception {
        var inFlight = Promise.<StreamPartitionManager.EpochRead> promise();

        startBehindRing(inFlight);
        awaitUntil(() -> count(STREAM_CONSUMER_BEHIND_RING) == 1 && reads(1L) >= 1, "behind the ring, with a read in flight");

        runtime.unsubscribe(STREAM, 0, GROUP).onFailure(cause -> fail(cause.message()));
        awaitUntil(() -> count(STREAM_CONSUMER_BACK_IN_RING) == 1, "the cancel closed the episode");
        inFlight.resolve(Result.success(new StreamPartitionManager.EpochRead(List.of(), E1)));
        Thread.sleep(SETTLE_MS);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
    }

    /// Read 0 is served from the tier (the episode opens), the first read at 1 stays in flight on `inFlight`, any later one
    /// is an empty tier read.
    private void startBehindRing(Promise<StreamPartitionManager.EpochRead> inFlight) {
        start((from, _) -> from == 0L
                           ? Promise.success(tierRead(events(0, 0)))
                           : firstAt(from, inFlight, Promise.success(tierRead(List.of()))));
    }

    /// Every read lands in the same hole; the second one stays in flight on `inFlight`.
    private void startAtHole(Promise<StreamPartitionManager.EpochRead> inFlight) {
        start((from, _) -> reads(from) == 2
                           ? inFlight
                           : hole().promise());
    }

    private Promise<StreamPartitionManager.EpochRead> firstAt(long from,
                                                              Promise<StreamPartitionManager.EpochRead> first,
                                                              Promise<StreamPartitionManager.EpochRead> later) {
        return reads(from) == 1
               ? first
               : later;
    }

    private SegmentError.SealedRangeMissing hole() {
        return new SegmentError.SealedRangeMissing(STREAM, 0, 0L, 3L);
    }

    private interface Script {
        Promise<StreamPartitionManager.EpochRead> answer(Long from, Epoch consumerEpoch);
    }

    private void start(Script script) {
        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                              DeadLetterHandler.deadLetterHandler(),
                                                              noCommitStore(),
                                                              reader(script));
        runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (_, _, _) -> Promise.unitPromise());
    }

    private StreamConsumerRuntime.PartitionReader reader(Script script) {
        return new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String stream, int partition, long from, int max) {
                throw new AssertionError("a consumer reads through readFrom");
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String stream, int partition, long from, int max, Epoch consumerEpoch) {
                readsAt.computeIfAbsent(from, _ -> new AtomicInteger()).incrementAndGet();

                return script.answer(from, consumerEpoch);
            }
        };
    }

    private static ConsumerCursorStore noCommitStore() {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private int reads(long from) {
        return Option.option(readsAt.get(from)).map(AtomicInteger::get).or(0);
    }

    private List<OperatorWarningCode> codes() {
        return warnings.stream().map(OperatorWarning::code).toList();
    }

    private long count(OperatorWarningCode code) {
        return warnings.stream().filter(warning -> warning.code() == code).count();
    }

    private void awaitUntil(BooleanSupplier done, String what) {
        var deadline = System.currentTimeMillis() + 5_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(done.getAsBoolean()).as("%s within 5 s (warnings %s)", what, codes()).isTrue();
    }

    private static StreamPartitionManager.EpochRead tierRead(List<OffHeapRingBuffer.RawEvent> events) {
        return StreamPartitionManager.EpochRead.epochRead(events, E1, true);
    }

    private static List<OffHeapRingBuffer.RawEvent> events(long from, long to) {
        return java.util.stream.LongStream.rangeClosed(from, to)
                                          .mapToObj(offset -> new OffHeapRingBuffer.RawEvent(offset, ("e" + offset).getBytes(UTF_8), offset))
                                          .toList();
    }

    /// Parks the first thread that logs a line containing `token` (from `ConsumerRuntimeState`) until `release` is counted
    /// down, with the logger's level lowered to INFO for the duration so the recovery lines reach the appender.
    private static final class ParkedLogLine implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final org.apache.logging.log4j.core.config.LoggerConfig loggerConfig;
        private final Level previousLevel;
        private final AbstractAppender appender;
        private final LoggerContext context = (LoggerContext) LogManager.getContext(false);

        private ParkedLogLine(String token) {
            loggerConfig = context.getConfiguration().getLoggerConfig(ConsumerRuntimeState.class.getName());
            previousLevel = loggerConfig.getLevel();
            appender = new AbstractAppender("parked-" + token, null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
                private final java.util.concurrent.atomic.AtomicBoolean parked = new java.util.concurrent.atomic.AtomicBoolean();

                @SuppressWarnings("JBCT-RET-01")  // `Appender.append` is log4j's void override
                @Override
                public void append(LogEvent event) {
                    if (event.getMessage().getFormattedMessage().contains(token) && parked.compareAndSet(false, true)) {
                        entered.countDown();
                        awaitRelease();
                    }
                }
            };
        }

        static ParkedLogLine parkOnce(String token) {
            var parked = new ParkedLogLine(token);

            parked.appender.start();
            parked.loggerConfig.addAppender(parked.appender, Level.INFO, null);
            parked.loggerConfig.setLevel(Level.INFO);
            parked.context.updateLoggers();

            return parked;
        }

        private void awaitRelease() {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            release.countDown();
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(previousLevel);
            context.updateLoggers();
            appender.stop();
        }
    }
}
