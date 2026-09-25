// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.GovernorFailoverHandler.governorFailoverHandler;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.NO_DURABILITY_BARRIER;
import static org.pragmatica.aether.stream.replication.WatermarkTracker.watermarkTracker;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;


class GovernorFailoverHandlerTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final NodeId REPLICA_B = NodeId.randomNodeId();

    private ReplicaRegistry registry;
    private WatermarkTracker localWatermarks;
    private SegmentIndex index;
    private StorageInstance storage;
    private StorageSegmentSink sink;
    private SegmentReader reader;
    private List<RecoveredEvent> recoveredEvents;
    private AtomicLong eventCounter;
    private GovernorFailoverHandler handler;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        localWatermarks = watermarkTracker();
        index = new SegmentIndex();
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        sink = storageSegmentSink(storage, index);
        reader = segmentReader(storage, index);
        recoveredEvents = new ArrayList<>();
        eventCounter = new AtomicLong(0);
        handler = governorFailoverHandler(registry, this::handleRecoveredEvent, NO_DURABILITY_BARRIER);
    }

    private Result<Long> handleRecoveredEvent(String streamName, int partition, long offset, byte[] payload, long timestamp) {
        recoveredEvents.add(new RecoveredEvent(streamName, partition, payload.clone(), timestamp));
        return Result.success(eventCounter.incrementAndGet());
    }

    private void awaitSuccess(Promise<Unit> promise) {
        promise.await().onFailure(_ -> Assertions.fail("Expected success"));
    }

    @Nested
    class CatchUpFromSegments {

        @Test
        void handleFailover_withSegments_replaysEvents() {
            sealSegment(0L, 2L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, -1L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(3);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("a".getBytes());
            assertThat(recoveredEvents.get(1).payload()).isEqualTo("b".getBytes());
            assertThat(recoveredEvents.get(2).payload()).isEqualTo("c".getBytes());
        }

        @Test
        void handleFailover_withWatermark_replaysOnlyMissingEvents() {
            sealSegment(0L, 4L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 2L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(2);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("d".getBytes());
            assertThat(recoveredEvents.get(1).payload()).isEqualTo("e".getBytes());
        }

        @Test
        void handleFailover_selectsHighestReplicaWatermark() {
            sealSegment(0L, 9L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L),
                RawEvent.rawEvent(5L, "f".getBytes(), 600L),
                RawEvent.rawEvent(6L, "g".getBytes(), 700L),
                RawEvent.rawEvent(7L, "h".getBytes(), 800L),
                RawEvent.rawEvent(8L, "i".getBytes(), 900L),
                RawEvent.rawEvent(9L, "j".getBytes(), 1000L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 3L);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_B, 7L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(2);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("i".getBytes());
            assertThat(recoveredEvents.get(1).payload()).isEqualTo("j".getBytes());
        }
    }

    @Nested
    class EmptySegments {

        @Test
        void handleFailover_noSegments_noReplicas_succeeds() {
            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).isEmpty();
        }

        @Test
        void handleFailover_noSegments_withWatermark_succeeds() {
            localWatermarks.advance(STREAM, PARTITION, 10L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).isEmpty();
        }
    }

    @Nested
    class LocalWatermarkInteraction {

        @Test
        void handleFailover_usesLocalWatermarkWhenHigherThanReplica() {
            sealSegment(0L, 9L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L),
                RawEvent.rawEvent(5L, "f".getBytes(), 600L),
                RawEvent.rawEvent(6L, "g".getBytes(), 700L),
                RawEvent.rawEvent(7L, "h".getBytes(), 800L),
                RawEvent.rawEvent(8L, "i".getBytes(), 900L),
                RawEvent.rawEvent(9L, "j".getBytes(), 1000L)
            ));

            localWatermarks.advance(STREAM, PARTITION, 8L);
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 5L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(1);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("j".getBytes());
        }
    }

    @Nested
    class PartialSegments {

        @Test
        void handleFailover_multipleSegments_replaysAcross() {
            sealSegment(0L, 4L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L)
            ));
            sealSegment(5L, 9L, List.of(
                RawEvent.rawEvent(5L, "f".getBytes(), 600L),
                RawEvent.rawEvent(6L, "g".getBytes(), 700L),
                RawEvent.rawEvent(7L, "h".getBytes(), 800L),
                RawEvent.rawEvent(8L, "i".getBytes(), 900L),
                RawEvent.rawEvent(9L, "j".getBytes(), 1000L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 2L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(7);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("d".getBytes());
            assertThat(recoveredEvents.getLast().payload()).isEqualTo("j".getBytes());
        }
    }

    /// #1244 × #1235 (CTO ruling 2026-09-20, replacing the 2026-09-19 waiver): a replay run commits its
    /// replayed frames through the replica WAL barrier ONCE, after the last one — exactly one fsync per
    /// run on a REAL WAL — and its records are visible on this replica when the run completes, with no live
    /// batch after it. Before, the run wrote WAL frames it never committed, so on a WAL-backed replica its
    /// records stayed invisible until an unrelated batch's barrier happened to cover them.
    @Nested
    class WalBackedReplica {
        @TempDir
        Path walDir;

        private StreamPartitionManager replica;

        @BeforeEach
        void openReplica() {
            replica = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> Assertions.fail(cause.message()));
            sealSegment(0L, 2L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L)
            ));
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, -1L);
        }

        @AfterEach
        void closeReplica() {
            replica.close();
        }

        @Test
        void handleFailover_completedRun_isFsyncedOnce_andItsRecordsAreVisible_withoutALaterLiveBatch() {
            var walBacked = governorFailoverHandler(registry, replica::appendRecovered, replica::syncReplicated);
            var before = fsyncCount();

            awaitSuccess(walBacked.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(replica.readLocal(STREAM, PARTITION, 0, 100).unwrap()).as("every replayed record is visible on this replica after the run, with no live batch")
                                                                              .hasSize(3);
            assertThat(fsyncCount() - before).as("one commit per replay run — never one per record, never none")
                                             .isEqualTo(1);
        }

        private long fsyncCount() {
            return replica.walSnapshot()
                          .streams()
                          .stream()
                          .flatMap(view -> view.partitions().stream())
                          .filter(view -> view.partition() == PARTITION)
                          .flatMap(view -> view.wal().stream())
                          .mapToLong(PartitionWal.WalStats::fsyncCount)
                          .sum();
        }
    }

    /// #1505 F1: failover replay into a replica ring that already holds part of the replayed range. The replay
    /// floor comes from a registry watermark (9) that lags the ring head (12), which is ordinary because the live
    /// receive never advances the self descriptor. The held offsets 10..12 must verify, and only 13..14 may be
    /// appended, each at its own offset. Before #1505 F1 the replay appended at the tail: event 10 landed at 13.
    /// (The reviewer's S3, R1507FailoverScratchTest, verbatim apart from its name.)
    @Nested
    class OffsetAlignedReplay {
        @Test
        void handleFailover_replayOverlapsHeldRing_keepsEveryEventAtItsOwnOffset() {
            var replica = ringHolding(13);
            sealSegment(0L, 14L, markers(15));
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 9L);
            var prod = governorFailoverHandler(registry, replica::appendRecovered, NO_DURABILITY_BARRIER);

            awaitSuccess(prod.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            var held = replica.readAppended(STREAM, PARTITION, 0, 100).unwrap();
            assertThat(held.stream().map(e -> e.offset() + "=" + new String(e.data())).toList())
                    .allSatisfy(s -> assertThat(s.substring(s.indexOf('=') + 3)).isEqualTo(s.substring(0, s.indexOf('='))))
                    .hasSize(15);
        }

        /// A replayed event that differs from the held one refuses the replay, fails the run, and quarantines the
        /// partition. Nothing past it is appended.
        @Test
        void handleFailover_replayMeetsDivergentHeldEntry_failsAndQuarantines() {
            var replica = ringHolding(13);
            var events = new ArrayList<>(markers(15));
            events.set(11, RawEvent.rawEvent(11L, "other-11".getBytes(), 1011L));
            sealSegment(0L, 14L, events);
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 9L);
            var prod = governorFailoverHandler(registry, replica::appendRecovered, NO_DURABILITY_BARRIER);

            assertThat(prod.handleFailover(STREAM, PARTITION, localWatermarks, index, reader).await().isFailure()).isTrue();
            assertThat(replica.quarantinedAt(STREAM, PARTITION).or(-1L)).isEqualTo(11L);
            assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).isEqualTo(13L);
        }

        /// A replayed offset the ring has already evicted is passed over, and the replay goes on to the rest.
        @Test
        void handleFailover_evictedOffsets_arePassedOver_restAreReplayed() {
            var landed = new ArrayList<Long>();
            AlignedRecovery evictingBelowTwo = (_, _, offset, _, _) -> offset < 2
                                                                        ? new StreamError.CursorExpired(offset, 2).result()
                                                                        : Result.success(recordLanded(landed, offset));
            sealSegment(0L, 3L, markers(4));
            var prod = governorFailoverHandler(registry, evictingBelowTwo, NO_DURABILITY_BARRIER);

            awaitSuccess(prod.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(landed).containsExactly(2L, 3L);
        }

        private StreamPartitionManager ringHolding(int count) {
            var replica = streamPartitionManager(Long.MAX_VALUE);
            replica.createStream(StreamConfig.streamConfig(STREAM));
            for (var i = 0; i < count; i++) {
                replica.appendRecovered(STREAM, PARTITION, (long) i, ("m-" + i).getBytes(), 1000L + i).unwrap();
            }
            return replica;
        }

        private List<RawEvent> markers(int count) {
            var events = new ArrayList<RawEvent>();
            for (var i = 0; i < count; i++) {
                events.add(RawEvent.rawEvent(i, ("m-" + i).getBytes(), 1000L + i));
            }
            return events;
        }
    }

    private static long recordLanded(List<Long> landed, long offset) {
        landed.add(offset);
        return offset;
    }

    private void sealSegment(long startOffset, long endOffset, List<RawEvent> events) {
        var serialized = serializeEvents(events);
        var minTs = events.stream().mapToLong(RawEvent::timestamp).min().orElse(0L);
        var maxTs = events.stream().mapToLong(RawEvent::timestamp).max().orElse(0L);
        var segment = SealedSegment.sealedSegment(STREAM, PARTITION, startOffset, endOffset,
                                                   events.size(), minTs, maxTs, serialized);
        sink.seal(segment).await();
    }

    private static byte[] serializeEvents(List<RawEvent> events) {
        var totalSize = events.stream()
                              .mapToInt(e -> Long.BYTES + Long.BYTES + Integer.BYTES + e.data().length)
                              .sum();
        var buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        for (var event : events) {
            buffer.putLong(event.offset());
            buffer.putLong(event.timestamp());
            buffer.putInt(event.data().length);
            buffer.put(event.data());
        }
        return buffer.array();
    }

    record RecoveredEvent(String streamName, int partition, byte[] payload, long timestamp) {}
}
