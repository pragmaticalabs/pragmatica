// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.segment.SealerThresholds;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.DeadLetterHandler.DeadLetterEntry;
import org.pragmatica.aether.stream.DefaultStreamPublisher;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// v-2254 D1: `DlqStreamSink.read` carries its failure and never turns one into an empty list. Three cases the first
/// version answered `[]` for: a seal still in flight, the oldest dead letter reclaimed by retention (the read starts at
/// the earliest retained offset), and the owner gate refusing (the plain ring read used to list all five).
class DlqStreamSinkReadFailuresTest {
    private static final String TOPIC_STREAM = "topic:org.example:orders:1.0.0";
    private static final String DLQ = DurableTopicNames.dlqStreamForTopicStream(TOPIC_STREAM);
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final int DEAD_LETTERS = 5;

    private final SliceCodec codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), TopicCodecsStream.CODECS);
    private final StorageInstance storage = StorageInstance.storageInstance("dlq-failures", List.of(MemoryTier.memoryTier(ONE_GB)));
    private final SegmentIndex index = new SegmentIndex();
    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
    }

    private DlqStreamSink sinkWith(SegmentSink segmentSink, int ringCapacity) {
        manager = streamPartitionManager(Long.MAX_VALUE, segmentSealer(segmentSink));
        manager.tieredReader(tieredStreamReader(index, storage));
        manager.createStream(StreamConfig.streamConfig(DLQ,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(ringCapacity, 1_048_576L, 14L * 24 * 3_600_000L),
                                                       "earliest"))
               .onFailure(cause -> fail(cause.message()));

        var sink = new DlqStreamSink(codec,
                                     manager,
                                     stream -> DefaultStreamPublisher.streamPublisher(manager, codec, stream, 1, Option.none()));

        for (int i = 0; i < DEAD_LETTERS; i++) {
            sink.append(TOPIC_STREAM, 0, 100L + i, "group-a", envelope("m" + i), "boom", 5)
                .await()
                .onFailure(cause -> fail(cause.message()));
        }

        return sink;
    }

    private byte[] envelope(String messageId) {
        return codec.encode(new TopicEventEnvelope(messageId, 1L, "p".getBytes(UTF_8)));
    }

    private static List<Long> offsets(List<DeadLetterEntry> entries) {
        return entries.stream().map(DeadLetterEntry::offset).toList();
    }

    @Test
    void read_failsWithSealInFlight_whileASealIsInFlight_insteadOfAnsweringEmpty() {
        SegmentSink neverSeals = (segment, log) -> Promise.promise();
        var sink = sinkWith(neverSeals, 2);

        sink.read(TOPIC_STREAM, 10)
            .await()
            .onSuccess(entries -> fail("a read that met an in-flight seal answered " + offsets(entries)))
            .onFailure(cause -> assertThat(cause).isInstanceOf(SegmentError.SealInFlight.class));
    }

    @Test
    void read_startsAtTheEarliestRetainedOffset_afterRetentionReclaimedTheOldestDeadLetter() {
        var sink = sinkWith(storageSegmentSink(storage, index), 2);

        awaitListed(sink, DEAD_LETTERS);

        var first = index.listSegments(DLQ, 0).getFirst();

        index.recordReclaimed(DLQ, 0, first.endOffset());
        index.removeSegment(DLQ, 0, first.startOffset());

        assertThat(offsets(sink.read(TOPIC_STREAM, 10).await().unwrap()))
            .as("the reclaimed dead letter is gone; the others are still listed")
            .containsExactly(101L, 102L, 103L, 104L);
    }

    @Test
    void read_failsWithTheGatesCause_whenTheOwnerGateRefuses_insteadOfAnsweringEmpty() {
        var sink = sinkWith(storageSegmentSink(storage, index), 100);

        manager.placementRoleSupplier((_, _) -> Role.OWNER);
        manager.ownerServeGate((_, _) -> StreamError.General.SEALING_BEHIND.result());

        sink.read(TOPIC_STREAM, 10)
            .await()
            .onSuccess(entries -> fail("a refused owner gate answered " + offsets(entries)))
            .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.SEALING_BEHIND));
    }

    @Test
    void read_failsWithCursorExpired_whenRetentionReclaimsAgainBetweenTheFirstReadAndTheRestart() {
        var sink = sinkWith(storageSegmentSink(storage, index), 2);

        awaitListed(sink, DEAD_LETTERS);
        reclaimFirstSegment();
        manager.placementRoleSupplier((_, _) -> Role.OWNER);
        manager.ownerServeGate(secondReadReclaimsAgain());

        sink.read(TOPIC_STREAM, 10)
            .await()
            .onSuccess(entries -> fail("a restart that expired again answered " + offsets(entries)))
            .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.CursorExpired.class));
    }

    @Test
    void read_failsWithReplicaNotVerified_insteadOfListingTheVerifiedPrefix_onAnUnverifiedReplica() {
        var sink = sinkWith(storageSegmentSink(storage, index), 100);
        var epoch = Epoch.epoch(1L, 1L, 2L);

        manager.ownerEpochSource((_, _) -> epoch);
        manager.epochStarts((_, _, _) -> Option.some(2L));
        manager.placementRoleSupplier((_, _) -> Role.REPLICA);

        sink.read(TOPIC_STREAM, 10)
            .await()
            .onSuccess(entries -> fail("an unverified replica listed the prefix " + offsets(entries)))
            .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaNotVerified.class));

        manager.markVerifiedForEpoch(DLQ, 0, epoch);

        assertThat(offsets(sink.read(TOPIC_STREAM, 10).await().unwrap())).as("verified for the epoch: the whole DLQ")
                                                                         .containsExactly(100L, 101L, 102L, 103L, 104L);
    }

    private void reclaimFirstSegment() {
        var first = index.listSegments(DLQ, 0).getFirst();

        index.recordReclaimed(DLQ, 0, first.endOffset());
        index.removeSegment(DLQ, 0, first.startOffset());
    }

    /// The owner gate runs once per read: the second run (the restart) finds one more segment reclaimed.
    private StreamPartitionManager.OwnerServeGate secondReadReclaimsAgain() {
        var reads = new AtomicInteger();

        return (_, _) -> reclaimOnSecondRead(reads);
    }

    private Result<Unit> reclaimOnSecondRead(AtomicInteger reads) {
        if (reads.incrementAndGet() == 2) {
            reclaimFirstSegment();
        }
        return Result.unitResult();
    }

    private static void awaitListed(DlqStreamSink sink, int expected) {
        var deadline = System.currentTimeMillis() + 10_000;

        while (sink.read(TOPIC_STREAM, 10).await().or(List.of()).size() < expected && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(sink.read(TOPIC_STREAM, 10).await().or(List.of())).as("control: all dead letters listed before the reclaim")
                                                                       .hasSize(expected);
    }

    /// One sealed segment per eviction, sealed at once (the sealer before runs, #2201 C-02): this fixture reclaims the segment
    /// of ONE dead letter, which needs one segment per eviction.
    private static SegmentSealer segmentSealer(SegmentSink sink) {
        return SegmentSealer.segmentSealer(sink, SegmentSealer.DEFAULT_PENDING_CAP_BYTES, SealerThresholds.immediate());
    }
}
