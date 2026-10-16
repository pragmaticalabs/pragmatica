// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.WarningLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BACK_IN_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BEHIND_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_MISSING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_RESTORED;

/// #2201 C-16, the consumer's side of a read below the ring tail, against a scripted owner: what `pollFailed` does with each
/// answer the sealed-tier fallback can give, and when the operator is told. NT-06 (in flight), NT-07 (reset versus rewind),
/// NT-08 (the behind-the-ring episode), the reset's own warning and checkpoint, the hole arm, and a tier I/O error.
class ConsumerBehindTheRingTest {
    private static final String STREAM = "orders";
    private static final String GROUP = "group-behind";
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<Long> delivered = new CopyOnWriteArrayList<>();
    private final List<Long> commits = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, AtomicInteger> readsAt = new ConcurrentHashMap<>();
    private StreamPartitionManager manager;
    private StreamConsumerRuntime runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"));
    }

    @AfterEach
    void tearDown() {
        Option.option(runtime).onPresent(StreamConsumerRuntime::close);
        manager.close();
    }

    /// NT-06: an in-flight seal is transient by design. The read is retried at the SAME offset with the poll backoff; nothing
    /// is skipped, nothing is raised beyond the lag episode of the read that finally succeeds.
    @Test
    void sealInFlight_isAFailedPass_noWarningNoReset_cursorUnchanged() throws InterruptedException {
        start((from, _) -> from == 0L && reads(0L) <= 3
                           ? new SegmentError.SealInFlight(STREAM, 0, 0L).promise()
                           : tier(from == 0L ? events(0, 1) : List.of(), E1));

        keepWaking(() -> delivered.size() >= 2 && !warningsOf(STREAM_CONSUMER_BEHIND_RING).isEmpty());

        assertThat(readsAt.get(0L).get()).as("the same offset was re-read after each in-flight answer").isGreaterThanOrEqualTo(4);
        assertThat(delivered).containsExactly(0L, 1L);
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_CURSOR_EXPIRED, STREAM_CONSUMER_SEALED_RANGE_MISSING);
    }

    /// Failure mode of 3.1: a tier that cannot be read (disk, DHT) is a failed pass, never confused with expiry.
    @Test
    void tierIoError_isAFailedPass_andNeverResets() throws InterruptedException {
        start((from, _) -> from == 0L && reads(0L) <= 3
                           ? Causes.cause("segment block read failed: disk I/O").<StreamPartitionManager.EpochRead> promise()
                           : tier(from == 0L ? events(0, 1) : List.of(), E1));

        keepWaking(() -> delivered.size() >= 2 && !warningsOf(STREAM_CONSUMER_BEHIND_RING).isEmpty());

        assertThat(readsAt.get(0L).get()).isGreaterThanOrEqualTo(4);
        assertThat(delivered).containsExactly(0L, 1L);
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_CURSOR_EXPIRED);
    }

    /// The reclaimed range: the cursor moves from 0 to the earliest retained offset 7, once; one CRITICAL warning names the
    /// group, the range and the exact count; the move is checkpointed at once; the records from 7 on are delivered.
    @Test
    void cursorExpired_resetsForwardOnce_raisesOneCriticalWarning_andCheckpointsTheMove() throws InterruptedException {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> new StreamError.CursorExpired(0L, 7L).<StreamPartitionManager.EpochRead> promise();
            case 7 -> tier(events(7, 8), E1);
            default -> ring(List.of(), E1);
        });

        keepWaking(() -> delivered.size() >= 2 && !commits.isEmpty() && !warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED).isEmpty());

        assertThat(delivered).containsExactly(7L, 8L);
        assertThat(commits.getFirst()).as("the reset itself is checkpointed, before any delivery").isEqualTo(7L);
        assertThat(warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED)).singleElement().satisfies(warning -> {
            assertThat(warning.code().level()).isEqualTo(WarningLevel.CRITICAL);
            assertThat(warning.subject()).contains(GROUP).contains("@7");
            assertThat(warning.message()).contains(GROUP).contains("skipped 7 records at offsets [0, 7)").contains("from 0 to 7");
        });
    }

    /// NT-07: a divergence rewinds and never resets; an expiry after the rewind resets from the REWOUND cursor and leaves the
    /// owner epoch the rewind adopted in place (the read at the new position still carries E2).
    @Test
    void epochDivergence_rewindsWithoutAReset_andALaterExpiryKeepsTheRewoundEpoch() throws InterruptedException {
        var epochAtFour = new java.util.concurrent.atomic.AtomicReference<Epoch>();

        start((from, epoch) -> {
            if (from == 0L && epoch.equals(Epoch.ZERO)) {
                return ring(events(0, 2), E1);
            }
            if (from == 3L && epoch.equals(E1)) {
                return new StreamError.EpochDiverged(E2, 1L, StreamError.EpochDiverged.NO_PROVEN_LOSS).promise();
            }
            if (from == 1L && epoch.equals(E2)) {
                return new StreamError.CursorExpired(1L, 4L).promise();
            }
            if (from == 4L) {
                epochAtFour.set(epoch);

                return tier(events(4, 4), E2);
            }
            return ring(List.of(), epoch);
        });

        keepWaking(() -> delivered.contains(4L) && !warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED).isEmpty());

        assertThat(delivered).containsExactly(0L, 1L, 2L, 4L);
        assertThat(epochAtFour.get()).as("the reset kept the owner epoch the rewind adopted").isEqualTo(E2);
        assertThat(warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED)).singleElement()
                                                              .satisfies(warning -> assertThat(warning.message()).contains("[1, 4)"));
    }

    /// NT-07, the interleaving itself: an expiry for the offset a read was issued at, handled after a rewind moved the
    /// cursor, must not move it, and no reset ever touches the rewind epoch or the owner epoch.
    @Test
    void resetForward_afterARewindMovedTheCursor_isRefused_andNeverTouchesTheEpochs() {
        var state = ConsumerRuntimeState.ConsumerState.consumerState(ConsumerConfig.consumerConfig(GROUP),
                                                                     (_, _, _) -> Promise.unitPromise(),
                                                                     10L,
                                                                     IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED);

        state.rewindTo(3L, E2);

        assertThat(state.resetForward(10L, 20L)).as("the expiry was for the pre-rewind cursor").isFalse();
        assertThat(state.cursor()).isEqualTo(3L);
        assertThat(state.resetForward(3L, 3L)).as("never a reset that does not move forward").isFalse();
        assertThat(state.resetForward(3L, 20L)).isTrue();
        assertThat(state.cursor()).isEqualTo(20L);
        assertThat(state.resetForward(3L, 25L)).as("one reset per expiry").isFalse();
        assertThat(state.epoch()).as("no rewind epoch is minted").isEqualTo(RewindEpoch.NONE);
        assertThat(state.ownerEpoch()).isEqualTo(E2);
    }

    /// NT-08: one WARNING per episode, at the first read served from the tier, and one INFO recovery at the first read served
    /// from the ring again; a second episode raises a second pair; consecutive tier reads raise nothing more.
    @Test
    void behindTheRing_isRaisedOncePerEpisode_andResolvedWhenARingReadServesAgain() throws InterruptedException {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> tier(events(0, 0), E1);
            case 1 -> tier(events(1, 1), E1);
            case 2 -> ring(events(2, 2), E1);
            case 3 -> tier(events(3, 3), E1);
            default -> ring(List.of(), E1);
        });

        keepWaking(() -> delivered.size() >= 4 && codes().size() >= 4);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING,
                                            STREAM_CONSUMER_BACK_IN_RING,
                                            STREAM_CONSUMER_BEHIND_RING,
                                            STREAM_CONSUMER_BACK_IN_RING);
        assertThat(warnings.getFirst().code().level()).isEqualTo(WarningLevel.WARNING);
        assertThat(warnings.getFirst().message()).contains(GROUP).contains("cursor 0");
        assertThat(warnings.get(1).subject()).isEqualTo(warnings.getFirst().subject());
    }

    /// A consumer cancelled while behind the ring would leave the alert open for good: its end is raised, with the reason.
    @Test
    void cancelWhileBehindTheRing_resolvesTheEpisode() throws InterruptedException {
        start((from, _) -> tier(from == 0L ? events(0, 0) : List.of(), E1));
        keepWaking(() -> delivered.contains(0L));

        runtime.close();
        awaitWarnings(2);

        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
        assertThat(warnings.getLast().message()).contains("cancelled");
    }

    /// A read that was in flight when the consumer was cancelled answers afterwards, after the cancel's hook already ran. An
    /// episode it opens must be closed by the opener itself, or nothing ever would. Deterministic: the read closes the
    /// runtime before it answers.
    @Test
    void tierReadAnsweringAfterTheCancel_opensNoBehindTheRingEpisode() throws InterruptedException {
        cancelInsideTheRead(tier(events(0, 0), E1));

        assertThat(codes()).as("the late read's episode is opened and closed by the opener").containsExactly(STREAM_CONSUMER_BEHIND_RING,
                                                                                                              STREAM_CONSUMER_BACK_IN_RING);
        assertThat(warnings.getLast().message()).contains("cancelled");
    }

    @Test
    void holeAnsweringAfterTheCancel_opensNoHoleEpisode() throws InterruptedException {
        cancelInsideTheRead(new SegmentError.SealedRangeMissing(STREAM, 0, 0L, 3L).promise());

        assertThat(codes()).containsExactly(STREAM_CONSUMER_SEALED_RANGE_MISSING, STREAM_CONSUMER_SEALED_RANGE_RESTORED);
        assertThat(warnings.getLast().message()).contains("cancelled");
    }

    private void cancelInsideTheRead(Promise<StreamPartitionManager.EpochRead> answer) throws InterruptedException {
        var answered = new java.util.concurrent.CountDownLatch(1);

        start((_, _) -> closeThenAnswer(answered, answer));
        assertThat(answered.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("the read was issued").isTrue();
        awaitWarnings(2);
    }

    private Promise<StreamPartitionManager.EpochRead> closeThenAnswer(java.util.concurrent.CountDownLatch answered,
                                                                      Promise<StreamPartitionManager.EpochRead> answer) {
        runtime.close();
        answered.countDown();

        return answer;
    }

    /// The hole arm at unit level: the cursor waits at 5 (re-read there), one WARNING names the range, and the first read
    /// that succeeds from 5 resolves it.
    @Test
    void sealedRangeMissing_waitsAtTheOffset_raisesOnce_andResolvesWhenARead_succeeds() throws InterruptedException {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> ring(events(0, 4), E1);
            case 5 -> reads(5L) <= 3
                      ? new SegmentError.SealedRangeMissing(STREAM, 0, 5L, 9L).<StreamPartitionManager.EpochRead> promise()
                      : tier(events(5, 6), E1);
            default -> ring(List.of(), E1);
        });

        keepWaking(() -> delivered.size() >= 7 && !warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED).isEmpty());

        assertThat(readsAt.get(5L).get()).isGreaterThanOrEqualTo(4);
        assertThat(delivered).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_MISSING)).singleElement().satisfies(warning -> {
            assertThat(warning.subject()).contains(GROUP).endsWith("@5");
            assertThat(warning.message()).contains(GROUP).contains("[5, 8]").contains("offset 9 is sealed");
        });
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED)).singleElement()
                                                                     .satisfies(warning -> assertThat(warning.subject()).endsWith("@5"));
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_CURSOR_EXPIRED);
    }

    private interface Script {
        Promise<StreamPartitionManager.EpochRead> answer(Long from, Epoch consumerEpoch);
    }

    private void start(Script script) {
        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                              DeadLetterHandler.deadLetterHandler(),
                                                              recordingStore(),
                                                              reader(script));
        runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> deliver(offset));
    }

    private Promise<org.pragmatica.lang.Unit> deliver(long offset) {
        delivered.add(offset);

        return Promise.unitPromise();
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

    private int reads(long from) {
        return readsAt.get(from).get();
    }

    private ConsumerCursorStore recordingStore() {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                commits.add(offset);

                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private List<OperatorWarningCode> codes() {
        return warnings.stream().map(OperatorWarning::code).toList();
    }

    private List<OperatorWarning> warningsOf(OperatorWarningCode code) {
        return warnings.stream().filter(warning -> warning.code() == code).toList();
    }

    private void keepWaking(BooleanSupplier done) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;
        var tick = 0L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.publishLocal(STREAM, 0, ("wake-" + tick++).getBytes(UTF_8), tick);
            Thread.sleep(50);
        }
        assertThat(done.getAsBoolean()).as("the scenario completed within 10 s (delivered %s, warnings %s)", delivered, codes()).isTrue();
    }

    /// Warnings reach the sink through its hand-off thread, after the log line.
    private void awaitWarnings(int count) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;

        while (warnings.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private static Promise<StreamPartitionManager.EpochRead> tier(List<OffHeapRingBuffer.RawEvent> events, Epoch epoch) {
        return Promise.success(StreamPartitionManager.EpochRead.epochRead(events, epoch, true));
    }

    private static Promise<StreamPartitionManager.EpochRead> ring(List<OffHeapRingBuffer.RawEvent> events, Epoch epoch) {
        return Promise.success(new StreamPartitionManager.EpochRead(events, epoch));
    }

    private static List<OffHeapRingBuffer.RawEvent> events(long from, long to) {
        return java.util.stream.LongStream.rangeClosed(from, to)
                                          .mapToObj(offset -> new OffHeapRingBuffer.RawEvent(offset, ("e" + offset).getBytes(UTF_8), offset))
                                          .toList();
    }
}
