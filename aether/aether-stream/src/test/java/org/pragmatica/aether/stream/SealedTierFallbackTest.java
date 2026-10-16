// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.SealedTierFallback.sealedTierFallback;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;

/// NT-01 (#2201 C-16, I-09): the one decision for an offset the ring no longer holds. A retained offset is `SealInFlight`;
/// a sealed one is served from the tier, bounded by visible and continued from the ring; a reclaimed one is
/// `CursorExpired` naming the earliest retained offset; a hole is `SealedRangeMissing`; with no tier wired it is
/// `CursorExpired` at the ring tail.
class SealedTierFallbackTest {
    private static final String STREAM = "behind";
    private static final int PARTITION = 0;

    private final SealedTierFixture tier = new SealedTierFixture();
    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        manager.close();
        tier.shutdown();
    }

    /// The sealer holds offsets 0..4 (its sink never answers), so they are in flight: transient, never a reason to skip.
    @Test
    void read_offsetTheSealerRetains_isSealInFlight() {
        manager = streamPartitionManager(Long.MAX_VALUE, segmentSealer(neverAnswers()));
        createStream(5, 1);
        publish(10);

        assertThat(manager.sealInFlight(STREAM, PARTITION, 0)).as("control: the sealer retains offset 0").isTrue();
        assertThat(failureOf(fallback().read(STREAM, PARTITION, 0, 10))).isEqualTo(new SegmentError.SealInFlight(STREAM,
                                                                                                                  PARTITION,
                                                                                                                  0));
    }

    /// Ring 20..29; the tier holds 0..19. A read from 15 takes 15..19 from the tier and continues from the ring.
    @Test
    void read_sealedOffset_isServedFromTheTier_andContinuedFromTheRing() {
        ringOf30WithTail20();
        tier.seal(STREAM, PARTITION, 0, 9);
        tier.seal(STREAM, PARTITION, 10, 19);

        assertThat(offsets(fallback().read(STREAM, PARTITION, 15, 8))).containsExactly(15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L);
        assertThat(payloadOf(fallback().read(STREAM, PARTITION, 3, 1))).as("the tier's own event").isEqualTo("evt-3");
    }

    /// Visible is 0 while the tier holds offset 1 (min-sync 2, the peer acknowledged only 0): the tier must not serve 1.
    @Test
    void read_isBoundedByTheVisiblePosition() {
        var self = NodeId.randomNodeId();
        var peer = NodeId.randomNodeId();
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, self);
        registry.registerReplica(STREAM, PARTITION, peer);
        ReplicationManager replication = replicationManager(self, registry);
        manager = streamPartitionManager(Long.MAX_VALUE, segmentSealer(SegmentSink.DISCARD), replication);
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(2, 1_048_576L, 600_000L),
                                                       "earliest",
                                                       1_048_576L,
                                                       ConsistencyMode.EVENTUAL,
                                                       3,
                                                       2,
                                                       StreamCompression.NONE,
                                                       Option.none()))
               .onFailure(cause -> fail(cause.message()));
        publish(4);
        replication.handleAck(replicateAck(peer, STREAM, PARTITION, 0));
        tier.seal(STREAM, PARTITION, 0, 1);

        assertThat(visible()).as("control: only offset 0 is acknowledged").isZero();
        assertThat(offsets(fallback().read(STREAM, PARTITION, 0, 10))).as("stops AT visible").containsExactly(0L);
        assertThat(offsets(fallback().read(STREAM, PARTITION, 1, 10))).as("above visible reads as nothing yet").isEmpty();
    }

    /// Retention reclaimed the segment 0..9 below the sealed watermark (19): the cursor expired, and the earliest retained
    /// offset is the tier's next sealed one, 10, not the ring tail.
    @Test
    void read_reclaimedOffset_isCursorExpiredNamingTheEarliestRetained() {
        ringOf30WithTail20();
        tier.seal(STREAM, PARTITION, 0, 9);
        tier.seal(STREAM, PARTITION, 10, 19);
        tier.reclaim(STREAM, PARTITION, 0);

        assertThat(failureOf(fallback().read(STREAM, PARTITION, 3, 10))).isEqualTo(new StreamError.CursorExpired(3, 10));
    }

    /// A hole above the watermark (5..9 never sealed) passes through as the tier answers it.
    @Test
    void read_insideAHole_isSealedRangeMissing() {
        ringOf30WithTail20();
        tier.seal(STREAM, PARTITION, 0, 4);
        tier.seal(STREAM, PARTITION, 10, 19);

        assertThat(failureOf(fallback().read(STREAM, PARTITION, 5, 10))).isEqualTo(new SegmentError.SealedRangeMissing(STREAM,
                                                                                                                        PARTITION,
                                                                                                                        5,
                                                                                                                        10));
    }

    /// No tier: the offset is gone as far as this node can tell, so it expires at the ring tail.
    @Test
    void read_withNoTierWired_isCursorExpiredAtTheRingTail() {
        ringOf30WithTail20();

        assertThat(failureOf(sealedTierFallback(manager, Option.none()).read(STREAM, PARTITION, 3, 10))).isEqualTo(new StreamError.CursorExpired(3,
                                                                                                                                                20));
    }

    private SealedTierFallback fallback() {
        return sealedTierFallback(manager, Option.some(tier.reader()));
    }

    private void ringOf30WithTail20() {
        manager = streamPartitionManager();
        createStream(10, 1);
        publish(30);
        assertThat(manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::tailOffset).or(-1L)).as("control: ring tail")
                                                                                                         .isEqualTo(20L);
    }

    private void createStream(long ringEvents, int partitions) {
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       partitions,
                                                       RetentionPolicy.retentionPolicy(ringEvents, 1_048_576L, 600_000L),
                                                       "earliest"))
               .onFailure(cause -> fail(cause.message()));
    }

    private void publish(int count) {
        var head = manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::headOffset).or(-1L);

        for (var offset = head + 1; offset <= head + count; offset++) {
            manager.publishLocal(STREAM, PARTITION, SealedTierFixture.payload(offset), 1_000L + offset)
                   .onFailure(cause -> fail("publish: " + cause.message()));
        }
    }

    private long visible() {
        return manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::visibleOffset).or(Long.MIN_VALUE);
    }

    private static SegmentSink neverAnswers() {
        return (_, _) -> Promise.<Unit> promise();
    }

    private static List<Long> offsets(Promise<List<OffHeapRingBuffer.RawEvent>> read) {
        return read.await()
                   .onFailure(cause -> fail("read failed: " + cause.message()))
                   .or(List.of())
                   .stream()
                   .map(OffHeapRingBuffer.RawEvent::offset)
                   .toList();
    }

    private static String payloadOf(Promise<List<OffHeapRingBuffer.RawEvent>> read) {
        return read.await()
                   .map(events -> new String(events.getFirst().data(), UTF_8))
                   .or("read failed");
    }

    private static Cause failureOf(Promise<List<OffHeapRingBuffer.RawEvent>> read) {
        return read.await()
                   .fold(cause -> cause, events -> fail("expected a failure, got " + events.size() + " events"));
    }
}
