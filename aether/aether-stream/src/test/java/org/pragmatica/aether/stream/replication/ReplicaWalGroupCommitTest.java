// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
                                                      .isBetween(1L, 2L);
        assertAckedThrough(acks, RECORDS - 1);
        replica.close();

        assertThat(replayOffsets()).as("replay order == offset order")
                                   .containsExactlyElementsOf(LongStream.range(0, RECORDS).boxed().toList());
    }

    /// #1277 review N2: the barrier must not target a released WAL. A replicated write that was never
    /// synced, followed by the stream's release and rebuild, used to leave the latest-write entry pointing
    /// at the CLOSED WAL, so the rebuilt partition's first barrier failed.
    @Test
    void rebuiltPartition_firstBarrier_doesNotTargetTheReleasedWal() {
        var replica = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        replica.appendRecovered(STREAM, PARTITION, "r0".getBytes(UTF_8), 1000L).onFailure(cause -> fail(cause.message()));
        replica.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        replica.syncReplicated(STREAM, PARTITION)
               .await()
               .onFailure(cause -> fail("the rebuilt partition's barrier hit the released WAL: " + cause.message()));
        replica.close();
    }

    /// #1277 review N2: releasing a duplicate that LOST the install race must not erase the winner's
    /// latest-write entry, or the winner's next barrier resolves without an fsync. Both creates are parked
    /// inside their WAL recovery — the sealed-offset source is consulted there, after the WAL is open and
    /// before the install — so the first can win, a replicated write can land on the winner, and only then
    /// is the loser released.
    @Test
    void duplicateLoserRelease_keepsTheWinnersUnsyncedWrite_soItsBarrierStillFsyncs() throws Exception {
        var gates = List.of(new CountDownLatch(1), new CountDownLatch(1));
        var arrivals = new AtomicInteger();
        var replica = streamPartitionManager(Long.MAX_VALUE,
                                             Option.some(walDir),
                                             (_, partition) -> parkFirstPartition(partition, gates, arrivals));
        var creates = IntStream.range(0, 2)
                               .mapToObj(_ -> CompletableFuture.supplyAsync(() -> replica.createStream(StreamConfig.streamConfig(STREAM))))
                               .toList();

        awaitArrivals(arrivals, 2);
        gates.get(0).countDown();
        CompletableFuture.anyOf(creates.get(0), creates.get(1)).get(10, TimeUnit.SECONDS);
        replica.appendRecovered(STREAM, PARTITION, "r0".getBytes(UTF_8), 1000L).onFailure(cause -> fail(cause.message()));
        var fsyncsBefore = fsyncCount(replica);

        gates.get(1).countDown();
        CompletableFuture.allOf(creates.get(0), creates.get(1)).get(10, TimeUnit.SECONDS);

        assertThat(creates.stream().map(CompletableFuture::join).toList())
            .as("one create won the install and the other released its duplicate through the loser path")
            .containsExactlyInAnyOrder(Result.unitResult(), StreamError.General.STREAM_ALREADY_EXISTS.result());
        replica.syncReplicated(STREAM, PARTITION).await().onFailure(cause -> fail(cause.message()));

        assertThat(fsyncCount(replica) - fsyncsBefore).as("the winner's barrier still fsyncs after the duplicate loser is released")
                                                      .isEqualTo(1L);
        replica.close();
    }

    /// Each create consults the source once per partition, partition 0 first: park that call on the gate
    /// for the create's arrival order. Every other call — the other partitions, and the WAL snapshot's own
    /// lookups later in the test — answers "nothing sealed" at once.
    private static long parkFirstPartition(int partition, List<CountDownLatch> gates, AtomicInteger arrivals) {
        if (partition == PARTITION) {
            var arrival = arrivals.getAndIncrement();

            if (arrival < gates.size()) {
                awaitGate(gates.get(arrival));
            }
        }

        return -1L;
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            assertThat(gate.await(10, TimeUnit.SECONDS)).as("the parked create was released").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private static void awaitArrivals(AtomicInteger arrivals, int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (arrivals.get() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(arrivals.get()).as("both creates are past their WAL open and neither is installed").isEqualTo(expected);
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
