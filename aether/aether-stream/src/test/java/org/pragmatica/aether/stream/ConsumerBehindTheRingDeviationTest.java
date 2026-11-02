// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BACK_IN_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BEHIND_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_MISSING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_SEALED_RANGE_RESTORED;

/// #2201 C-16, what the consumer does beyond 3.1 of the spec: a tier read continues the drain (no push consumer strands
/// behind the ring), every behind-the-ring and hole episode is closed (on unsubscribe, on a rewind, on a reset), the
/// forwarded read's `fromTier` can only add a spurious episode pair, and the #1333 rewound catch-up is not taken early.
/// Scripted reads, and NO wake-up publishes unless a test says why: a strand shows as a timeout.
class ConsumerBehindTheRingDeviationTest {
    private static final String STREAM = "orders";
    private static final String GROUP = "group-deviation";
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
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        manager.publishLocal(STREAM, 0, "seed".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        Option.option(runtime).onPresent(StreamConsumerRuntime::close);
        manager.close();
    }

    /// D-1: two short tier reads in a row (each cut short, e.g. at a contiguous-run end) and then the ring. With no append
    /// to wake it, a push consumer must still drain all of it. RED when `moreBehindTheRing` is removed.
    @Test
    void d1_shortTierReads_drainToTheRing_withoutAnyAppend() {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> tier(events(0, 4), E1);
            case 5 -> tier(events(5, 7), E1);
            case 8 -> ring(events(8, 9), E1);
            default -> ring(List.of(), E1);
        });

        // The strand check is the delivery count: with D-1 removed nothing re-drives the drain after the first short tier
        // read and this times out. The warnings reach the sink on its own hand-off thread, after the delivery that follows
        // them on the drain, so the wait also covers the episode's close before the exact sequence is asserted.
        awaitUntil(() -> delivered.size() >= 10 && codes().size() >= 2, "drained 0..9 with no wake-up, and the episode closed");
        assertThat(delivered).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
    }

    /// D-1 after a forward reset: the reset continues the drain, and the short tier read after it continues too.
    @Test
    void d1_afterAReset_theDrainContinues_withoutAnyAppend() {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> new StreamError.CursorExpired(0L, 7L).<StreamPartitionManager.EpochRead> promise();
            case 7 -> tier(events(7, 8), E1);
            case 9 -> ring(events(9, 10), E1);
            default -> ring(List.of(), E1);
        });

        awaitUntil(() -> delivered.size() >= 4 && codes().contains(STREAM_CONSUMER_BACK_IN_RING)
                         && !warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED).isEmpty(),
                   "drained 7..10 after the reset, and the expiry handed off");
        assertThat(delivered).containsExactly(7L, 8L, 9L, 10L);
        assertThat(warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED)).hasSize(1);
    }

    /// Honest limit of the forwarded `fromTier`: a read the RING served at its edge, mis-named a tier read. Its only effects
    /// are one behind/back pair and one extra (empty) read: the same events, the same cursor, no reset, no skip. A ring read
    /// that is not a full batch ends the drain, so one append wakes the consumer for the second read (as in production).
    @Test
    void spuriousTierFlag_onARingEdgeRead_changesNothingButOneEpisodePair() {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> ring(events(0, 4), E1);
            case 5 -> tier(events(5, 6), E1);
            default -> ring(List.of(), E1);
        });
        awaitUntil(() -> delivered.size() >= 5, "delivered 0..4 from the ring");
        wakeOnce();

        awaitUntil(() -> delivered.size() >= 7 && codes().size() >= 2, "drained 5..6 and closed the episode");
        assertThat(delivered).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
        assertThat(runtime.cursorPosition(STREAM, 0, GROUP).or(-1L)).isEqualTo(7L);
        assertThat(readsAt.get(7L).get()).as("the one extra read the flag causes, at the head").isEqualTo(1);
    }

    /// D-3 on the UNSUBSCRIBE path (the author's test closes the whole runtime): a consumer unsubscribed while waiting at a
    /// hole ends that episode. RED when `endEpisodesOnCancel` is removed from `detachWithoutFlush`.
    @Test
    void d3_unsubscribeWhileAtAHole_endsTheHoleEpisode() {
        start((from, _) -> new SegmentError.SealedRangeMissing(STREAM, 0, from, from + 3).<StreamPartitionManager.EpochRead> promise());
        awaitUntil(() -> !warningsOf(STREAM_CONSUMER_SEALED_RANGE_MISSING).isEmpty(), "the hole is raised");

        runtime.unsubscribe(STREAM, 0, GROUP).onFailure(cause -> fail(cause.message()));

        awaitUntil(() -> !warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED).isEmpty(), "the hole episode ends");
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_MISSING)).hasSize(1);
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED)).singleElement()
                                                                     .satisfies(warning -> assertThat(warning.message()).contains("cancelled"));
    }

    /// D-3 on the unsubscribe path, behind the ring.
    @Test
    void d3_unsubscribeWhileBehindTheRing_endsTheLagEpisode() {
        start((from, _) -> tier(from == 0L ? events(0, 0) : List.of(), E1));
        awaitUntil(() -> delivered.contains(0L) && !warningsOf(STREAM_CONSUMER_BEHIND_RING).isEmpty(), "behind the ring");

        runtime.unsubscribe(STREAM, 0, GROUP).onFailure(cause -> fail(cause.message()));

        awaitUntil(() -> !warningsOf(STREAM_CONSUMER_BACK_IN_RING).isEmpty(), "the lag episode ends");
        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
    }

    /// D-3 / NT-07 together at runtime: waiting at a hole, a divergence rewinds the cursor off it (episode ends MOVED), an
    /// expiry from the REWOUND cursor resets forward once, and the read after the reset still carries the rewind's epoch.
    @Test
    void d3_nt07_rewindOffAHole_thenAnExpiry_resetsOnceFromTheRewoundCursor_keepingItsEpoch() {
        var epochAtEight = new AtomicReference<Epoch>();

        start((from, epoch) -> {
            if (from == 0L) {
                return ring(events(0, 4), E1);
            }
            if (from == 5L) {
                return reads(5L) <= 2
                       ? new SegmentError.SealedRangeMissing(STREAM, 0, 5L, 9L).promise()
                       : new StreamError.EpochDiverged(E2, 3L, StreamError.EpochDiverged.NO_PROVEN_LOSS).promise();
            }
            if (from == 3L && epoch.equals(E2)) {
                return new StreamError.CursorExpired(3L, 8L).promise();
            }
            if (from == 8L) {
                epochAtEight.set(epoch);

                return ring(events(8, 9), E2);
            }
            return ring(List.of(), epoch);
        });

        awaitUntil(() -> delivered.size() >= 5, "delivered 0..4 from the ring");
        wakeOnce();
        awaitUntil(() -> delivered.contains(9L) && !warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED).isEmpty(),
                   "delivered past the reset, and its warning handed off");
        assertThat(delivered).containsExactly(0L, 1L, 2L, 3L, 4L, 8L, 9L);
        assertThat(epochAtEight.get()).isEqualTo(E2);
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED)).singleElement()
                                                                     .satisfies(warning -> assertThat(warning.message()).contains("moved off it"));
        assertThat(warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED)).singleElement()
                                                              .satisfies(warning -> assertThat(warning.message()).contains("[3, 8)"));
    }

    /// D-3: a hole that turns into an expiry at the same offset (reachable only when the tier's index changes under the
    /// consumer) ends the hole episode as RECLAIMED, before the expiry is raised.
    @Test
    void d3_holeThenExpiryAtTheSameOffset_endsTheHoleAsReclaimed() {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> reads(0L) <= 2
                      ? new SegmentError.SealedRangeMissing(STREAM, 0, 0L, 4L).<StreamPartitionManager.EpochRead> promise()
                      : new StreamError.CursorExpired(0L, 6L).promise();
            case 6 -> ring(events(6, 7), E1);
            default -> ring(List.of(), E1);
        });

        // The expiry is the last warning of the sequence and reaches the sink on its hand-off thread, after the delivery that
        // follows the reset: the codes are read only once it has been handed off.
        awaitUntil(() -> delivered.contains(7L) && !warningsOf(STREAM_CONSUMER_CURSOR_EXPIRED).isEmpty(),
                   "reset past the hole, and its expiry handed off");
        assertThat(codes()).containsSubsequence(STREAM_CONSUMER_SEALED_RANGE_MISSING,
                                                STREAM_CONSUMER_SEALED_RANGE_RESTORED,
                                                STREAM_CONSUMER_CURSOR_EXPIRED);
        assertThat(warningsOf(STREAM_CONSUMER_SEALED_RANGE_RESTORED)).singleElement()
                                                                     .satisfies(warning -> assertThat(warning.message()).contains("reclaimed"));
    }

    /// D-3, the defensive arm: a hole answer naming a different offset than the open episode closes the old episode (MOVED)
    /// before opening the new one, so no WARNING is left without its recovery.
    @Test
    void d3_aHoleAtANewOffset_closesTheOldEpisodeFirst() {
        start((from, _) -> switch (reads(0L)) {
            case 1, 2 -> new SegmentError.SealedRangeMissing(STREAM, 0, 0L, 4L).<StreamPartitionManager.EpochRead> promise();
            case 3, 4 -> new SegmentError.SealedRangeMissing(STREAM, 0, 1L, 4L).<StreamPartitionManager.EpochRead> promise();
            default -> from == 0L ? ring(events(0, 1), E1) : ring(List.of(), E1);
        });

        awaitUntil(() -> delivered.contains(1L) && count(STREAM_CONSUMER_SEALED_RANGE_RESTORED) >= 2, "both hole episodes closed");
        assertThat(codes()).containsExactly(STREAM_CONSUMER_SEALED_RANGE_MISSING,
                                            STREAM_CONSUMER_SEALED_RANGE_RESTORED,
                                            STREAM_CONSUMER_SEALED_RANGE_MISSING,
                                            STREAM_CONSUMER_SEALED_RANGE_RESTORED);
        assertThat(warnings.get(1).message()).contains("moved off it");
    }

    /// D-1 and the #1333 rewound catch-up: a consumer resumed under a REWOUND epoch requests its one catch-up checkpoint at
    /// the first pass that is really caught up, not at a short tier read in the middle of the backlog. RED when the tier
    /// continuation is dropped from `commitRewoundCatchUp`.
    @Test
    void d1_aRewoundConsumer_checkpointsItsCatchUp_onlyOnceCaughtUp_notAtAShortTierRead() {
        var stored = org.pragmatica.aether.stream.segment.ConsumerCursorStore.Cursor.cursor(0L,
                                                                                            org.pragmatica.aether.slice.generation.RewindEpoch.rewindEpoch(1L, 7L, 3L),
                                                                                            E1);
        runtime = StreamConsumerRuntime.streamConsumerRuntime(manager,
                                                              DeadLetterHandler.deadLetterHandler(),
                                                              rewoundStore(stored),
                                                              reader((from, _) -> switch (from.intValue()) {
                                                                  case 0 -> tier(events(0, 4), E1);
                                                                  case 5 -> tier(events(5, 9), E1);
                                                                  case 10 -> ring(events(10, 11), E1);
                                                                  default -> ring(List.of(), E1);
                                                              }));
        runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> deliver(offset),
                          StreamConsumerRuntime.IdlePolicy.REAP_WHEN_IDLE, ADMIT);

        awaitUntil(() -> delivered.size() >= 12 && !commits.isEmpty(), "drained 0..11 and requested the catch-up checkpoint");
        assertThat(commits.getFirst()).as("the catch-up checkpoint is the caught-up cursor").isEqualTo(12L);
    }

    private static final ConsumerFence ADMIT = new ConsumerFence() {
        @Override
        public Epoch epoch() {
            return Epoch.epoch(1L, 1L, 9L);
        }

        @Override
        public boolean admitted() {
            return true;
        }
    };

    private ConsumerCursorStore rewoundStore(org.pragmatica.aether.stream.segment.ConsumerCursorStore.Cursor stored) {
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

            @Override
            public Promise<Option<Cursor>> fetchCursor(String group, String stream, int partition, Epoch assignmentEpoch) {
                return Promise.success(Option.some(stored));
            }
        };
    }

    /// An expiry naming a tail at or below the read's own offset (the fallback's `CursorExpired(from, 0)` when the ring was
    /// released between the ring read and the fallback, `SealedTierFallback.cursorExpiredAtTheRingTail`) never moves the
    /// cursor: `resetForward` refuses a move that is not forward, no CRITICAL is raised, and the read is retried.
    @Test
    void anExpiryWhoseTailIsNotAboveTheCursor_neverResets_andIsRetried() {
        start((from, _) -> reads(from) <= 3
                           ? new StreamError.CursorExpired(from, 0L).<StreamPartitionManager.EpochRead> promise()
                           : ring(from == 0L ? events(0, 1) : List.of(), E1));

        awaitUntil(() -> delivered.size() >= 2, "retried past the bogus expiry");
        assertThat(readsAt.get(0L).get()).isGreaterThanOrEqualTo(4);
        assertThat(delivered).containsExactly(0L, 1L);
        assertThat(codes()).doesNotContain(STREAM_CONSUMER_CURSOR_EXPIRED);
    }

    /// Episode balance: whatever sequence of answers, every WARNING opened is closed once the consumer is gone.
    @Test
    void d3_everyEpisodeOpened_isClosed_afterAMixedSequenceAndAClose() {
        start((from, _) -> switch (from.intValue()) {
            case 0 -> tier(events(0, 1), E1);
            case 2 -> reads(2L) <= 2
                      ? new SegmentError.SealedRangeMissing(STREAM, 0, 2L, 4L).<StreamPartitionManager.EpochRead> promise()
                      : ring(events(2, 3), E1);
            case 4 -> tier(events(4, 4), E1);
            case 5 -> reads(5L) <= 2
                      ? new SegmentError.SealedRangeMissing(STREAM, 0, 5L, 9L).<StreamPartitionManager.EpochRead> promise()
                      : new SegmentError.SealedRangeMissing(STREAM, 0, 5L, 9L).promise();
            default -> ring(List.of(), E1);
        });
        wakeUntil(() -> delivered.contains(4L) && warningsOf(STREAM_CONSUMER_SEALED_RANGE_MISSING).size() >= 2, "second hole raised");

        runtime.close();

        awaitUntil(() -> count(STREAM_CONSUMER_BEHIND_RING) >= 2
                         && count(STREAM_CONSUMER_SEALED_RANGE_RESTORED) == count(STREAM_CONSUMER_SEALED_RANGE_MISSING)
                         && count(STREAM_CONSUMER_BACK_IN_RING) == count(STREAM_CONSUMER_BEHIND_RING),
                   "every episode closed");
        assertThat(count(STREAM_CONSUMER_SEALED_RANGE_MISSING)).isEqualTo(2);
        assertThat(count(STREAM_CONSUMER_BEHIND_RING)).isEqualTo(2);
    }

    private void wakeOnce() {
        manager.publishLocal(STREAM, 0, "wake".getBytes(UTF_8), 2L).onFailure(cause -> fail(cause.message()));
    }

    /// Only for scenarios that are not about strands: an append after each ring read that ends the drain.
    private void wakeUntil(BooleanSupplier done, String what) {
        var deadline = System.currentTimeMillis() + 5_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            wakeOnce();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(done.getAsBoolean()).as("%s within 5 s (delivered %s, warnings %s)", what, delivered, codes()).isTrue();
    }

    private long count(OperatorWarningCode code) {
        return warningsOf(code).size();
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

    /// No publishing here: a drain that does not continue on its own times out.
    private void awaitUntil(BooleanSupplier done, String what) {
        var deadline = System.currentTimeMillis() + 5_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(done.getAsBoolean()).as("%s within 5 s (delivered %s, warnings %s)", what, delivered, codes()).isTrue();
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
