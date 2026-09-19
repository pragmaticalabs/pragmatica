// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;
import static org.pragmatica.aether.stream.replication.WatermarkTracker.watermarkTracker;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1244 (ruling know 801a8b54e, B2): replica WAL frames carry no per-record fsync, so every catch-up run
/// that re-appends events through `appendRecovered` — backfill, replica failover recovery, governor
/// segment replay — commits them itself when the run completes. Each case runs against a REAL WAL and
/// asserts exactly ONE fsync with NO later live batch: one proves the run is durable on a quiet
/// partition, and not more than one proves the commit is per run, never per record.
class CatchUpWalDurabilityTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int EVENTS = 5;
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId SOURCE = NodeId.nodeId("source").unwrap();

    @TempDir
    Path walDir;

    private StreamPartitionManager replica;
    private ReplicaRegistry registry;

    @BeforeEach
    void setUp() {
        replica = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, SOURCE);
        registry.updateWatermark(STREAM, PARTITION, SOURCE, EVENTS - 1);
    }

    @AfterEach
    void tearDown() {
        replica.close();
    }

    @Test
    void partitionBackfill_completedRun_isFsyncedOnce_withoutALaterLiveBatch() {
        registry.registerReplica(STREAM, PARTITION, SELF);
        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           replica::appendRecovered,
                                                           CatchUpWalDurabilityTest::sourceRange,
                                                           ReplicationTransport.NOOP,
                                                           (_, _, _) -> Causes.cause("no probe").promise(),
                                                           (_, _) -> -1L,
                                                           SELF,
                                                           TimeSpan.timeSpan(3600).seconds(),
                                                           List::of,
                                                           CommittedStreamOwnerSource.none(),
                                                           replica::syncReplicated);
        var before = fsyncCount();

        backfill.backfill(STREAM, PARTITION).await().onFailure(cause -> fail(cause.message()));

        assertOneCommitCovering(before);
    }

    @Test
    void failoverRecovery_fetchedRange_isFsyncedOnce_withoutALaterLiveBatch() {
        var recovery = FailoverRecovery.failoverRecovery(registry,
                                                         replica::appendRecovered,
                                                         CatchUpWalDurabilityTest::sourceRange,
                                                         replica::syncReplicated);
        var before = fsyncCount();

        recovery.recover(STREAM, 1).await().onFailure(cause -> fail(cause.message()));

        assertOneCommitCovering(before);
    }

    @Test
    void governorFailover_replayedSegments_areFsyncedOnce_withoutALaterLiveBatch() {
        var storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(64L * 1024 * 1024)));
        var index = new SegmentIndex();

        storageSegmentSink(storage, index).seal(sealedSegment()).await().onFailure(cause -> fail(cause.message()));
        // No replica watermark and no local watermark: the handler replays from the first sealed segment.
        var handler = GovernorFailoverHandler.governorFailoverHandler(replicaRegistry(),
                                                                      replica::appendRecovered,
                                                                      replica::syncReplicated);
        var before = fsyncCount();

        handler.handleFailover(STREAM, PARTITION, watermarkTracker(), index, segmentReader(storage, index))
               .await()
               .onFailure(cause -> fail(cause.message()));

        assertOneCommitCovering(before);
    }

    /// Exactly one fsync, and it covers every re-appended frame: the ring holds all EVENTS records and
    /// the WAL's last written offset is the last of them.
    private void assertOneCommitCovering(long fsyncsBefore) {
        assertThat(replica.readLocal(STREAM, PARTITION, 0, 100).unwrap()).hasSize(EVENTS);
        assertThat(walStats().lastOffset()).isEqualTo(EVENTS - 1);
        assertThat(fsyncCount() - fsyncsBefore).as("one commit per catch-up run — durable without a later live batch,"
                                                     + " and never one fsync per record")
                                               .isEqualTo(1);
    }

    private long fsyncCount() {
        return walStats().fsyncCount();
    }

    private PartitionWal.WalStats walStats() {
        return replica.walSnapshot()
                      .streams()
                      .stream()
                      .flatMap(view -> view.partitions().stream())
                      .filter(view -> view.partition() == PARTITION)
                      .flatMap(view -> view.wal().stream())
                      .findFirst()
                      .orElseThrow();
    }

    private static Promise<ReplicationMessage.CatchupResponse> sourceRange(NodeId target,
                                                                          ReplicationMessage.CatchupRequest request) {
        return Promise.success(catchupResponse(target,
                                               STREAM,
                                               PARTITION,
                                               0L,
                                               EVENTS - 1,
                                               IntStream.range(0, EVENTS).mapToObj(i -> payload(i)).toList(),
                                               IntStream.range(0, EVENTS).mapToObj(i -> 1000L + i).toList()));
    }

    private static SealedSegment sealedSegment() {
        var events = IntStream.range(0, EVENTS).mapToObj(i -> RawEvent.rawEvent(i, payload(i), 1000L + i)).toList();
        var buffer = ByteBuffer.allocate(events.stream().mapToInt(e -> 20 + e.data().length).sum())
                               .order(ByteOrder.BIG_ENDIAN);

        events.forEach(e -> buffer.putLong(e.offset()).putLong(e.timestamp()).putInt(e.data().length).put(e.data()));
        return SealedSegment.sealedSegment(STREAM, PARTITION, 0L, EVENTS - 1, EVENTS, 1000L, 1000L + EVENTS - 1, buffer.array());
    }

    private static byte[] payload(int i) {
        return ("event-" + i).getBytes(UTF_8);
    }
}
