// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// #1244: a replica's WAL group-commits a replicated batch. The frames are written inside the
/// partition's ordered append section with no per-record fsync, and the durability barrier the handler
/// awaits before acking commits them together. Before the fix each record's append was chained on its
/// predecessor's fsync, so a 100-record batch cost 100 fsyncs on the replica.
class ReplicaWalGroupCommitTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RECORDS = 100;

    @TempDir
    Path walDir;

    @Test
    void replicatedBatch_isGroupCommitted_andReplaysInOffsetOrder() {
        var replica = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        var acks = new ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck>();
        var handler = replicationReceiveHandler(NodeId.nodeId("replica").unwrap(),
                                                replica::appendRecovered,
                                                replica::nextExpectedOffset,
                                                recordingAcks(acks),
                                                (_, _) -> fail("a contiguous batch must not report a gap"),
                                                replica::syncReplicated);

        replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        var fsyncsBefore = fsyncCount(replica);

        handler.onReplicateEvents(batchOf(RECORDS));
        replica.syncReplicated(STREAM, PARTITION).await().onFailure(cause -> fail(cause.message()));

        assertThat(fsyncCount(replica) - fsyncsBefore).as("replica fsyncs for one %d-record batch", RECORDS)
                                                      .isLessThanOrEqualTo(2);
        assertAckedThrough(acks, RECORDS - 1);
        replica.close();

        assertThat(replayOffsets()).as("replay order == offset order")
                                   .containsExactlyElementsOf(LongStream.range(0, RECORDS).boxed().toList());
    }

    /// The handler acks from the barrier's completion callback, which may run on another thread.
    private static void assertAckedThrough(ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> acks, long offset) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (acks.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(acks).as("the batch is acked once the barrier resolves")
                        .extracting(ReplicationMessage.ReplicateAck::confirmedOffset)
                        .containsExactly(offset);
    }

    private ReplicationMessage.ReplicateEvents batchOf(int count) {
        var payloads = IntStream.range(0, count).mapToObj(i -> ("r" + i).getBytes(UTF_8)).toList();
        var timestamps = IntStream.range(0, count).mapToObj(i -> 1000L + i).toList();

        return ReplicationMessage.ReplicateEvents.replicateEvents(NodeId.nodeId("owner").unwrap(),
                                                                  STREAM,
                                                                  PARTITION,
                                                                  0L,
                                                                  payloads,
                                                                  timestamps,
                                                                  Epoch.ZERO);
    }

    private static long fsyncCount(StreamPartitionManager manager) {
        return manager.walSnapshot()
                      .streams()
                      .stream()
                      .flatMap(view -> view.partitions().stream())
                      .filter(view -> view.partition() == PARTITION)
                      .flatMap(view -> view.wal().stream())
                      .mapToLong(PartitionWal.WalStats::fsyncCount)
                      .sum();
    }

    private List<Long> replayOffsets() {
        var wal = PartitionWal.open(walDir.resolve(STREAM).resolve(PARTITION + ".wal")).unwrap();
        var records = new ArrayList<WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        wal.close();
        return records.stream().map(WalRecord::offset).toList();
    }

    private static ReplicationTransport recordingAcks(ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> acks) {
        return (_, message) -> recordAck(acks, message);
    }

    private static void recordAck(ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> acks,
                                  ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateAck ack) {
            acks.add(ack);
        }
    }
}
