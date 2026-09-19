// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1231 / #1232: one partition has ONE ordered append section. Offset assignment, the WAL frame write and
/// the replication send all happen inside it, so concurrent publishers to one partition get distinct,
/// contiguous offsets whose WAL file order and replication send order both equal offset order. Only the
/// group-commit fsync runs outside it (throughput).
///
/// The races are intermittent by nature, so each case is a `@RepeatedTest`: one red repetition is a
/// defect, and the repetition count is what a RED-on-unmodified-code claim is measured against.
class StreamPartitionManagerOrderedAppendTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    /// #1231 acceptance: 16 threads × 5,000 `publishLocal` on one WAL-less partition. The returned offsets
    /// are distinct and cover exactly `[0, 79999]`, the ring counts 80,000 events, and every offset reads
    /// back the payload of the publish that was told that offset.
    @RepeatedTest(20)
    void publishLocal_assignsDistinctContiguousOffsets_underConcurrentPublishers() {
        var threads = 16;
        var perThread = 5_000;
        var total = threads * perThread;
        var manager = streamPartitionManager(Long.MAX_VALUE);

        createStream(manager);

        var acked = publishConcurrently(manager, threads, perThread);
        var byOffset = indexByOffset(acked);

        assertThat(byOffset).as("every publish acked a DISTINCT offset").hasSize(total);
        assertThat(byOffset.keySet()).as("offsets cover exactly [0, %d]", total - 1)
                                     .containsExactlyInAnyOrderElementsOf(LongStream.range(0, total).boxed().toList());
        assertThat(manager.partitionBuffer(STREAM, PARTITION).unwrap().eventCount()).isEqualTo(total);
        assertEveryOffsetReadsBackItsPayload(manager, byOffset, total);

        manager.close();
    }

    /// #1231 (replication half): replicateEvent is invoked INSIDE the section, so the owner's send order is
    /// offset order. A send issued after the section can be overtaken by a later offset's send, and the
    /// replica's `fromOffset` check (#260) then rejects the overtaken batch.
    @RepeatedTest(20)
    void publishLocal_replicatesInOffsetOrder_underConcurrentPublishers() {
        var threads = 8;
        var perThread = 2_000;
        var recorder = new RecordingReplicationManager();
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, recorder);

        createStream(manager);
        publishConcurrently(manager, threads, perThread);

        assertThat(List.copyOf(recorder.sentOffsets)).as("replication send order == offset order")
                                                    .containsExactlyElementsOf(LongStream.range(0, threads * perThread)
                                                                                         .boxed()
                                                                                         .toList());
        manager.close();
    }

    /// #1232 acceptance 3: concurrent owner publishes followed by replay — the WAL frames are in strictly
    /// increasing offset order (the frame write is inside the section; only the fsync is outside).
    @RepeatedTest(5)
    void publishLocal_walFileOrderEqualsOffsetOrder_underConcurrentPublishers() {
        var threads = 8;
        var perThread = 250;
        var total = threads * perThread;
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        createStream(manager);
        publishConcurrently(manager, threads, perThread);
        manager.close();

        var records = replayAll(walDir.resolve(STREAM).resolve(PARTITION + ".wal"));

        assertThat(records.stream().map(WalRecord::offset).toList())
            .as("WAL file order must be offset order — recovery places records by their stored offsets")
            .containsExactlyElementsOf(LongStream.range(0, total).boxed().toList());
    }

    // === helpers ===

    private static void createStream(StreamPartitionManager manager) {
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
    }

    /// Fires `threads × perThread` publishes released together by one latch; returns every (offset,
    /// payload) the manager acked. A failed publish is itself a defect (a torn concurrent append can
    /// surface as a guarded-access failure), so it fails the test.
    private static List<Acked> publishConcurrently(StreamPartitionManager manager, int threads, int perThread) {
        var acked = new ConcurrentLinkedQueue<Acked>();
        var failures = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            var thread = t;

            pool.submit(() -> publishBatch(manager, thread, perThread, start, acked, failures));
        }

        start.countDown();
        pool.shutdown();
        awaitTermination(pool);

        assertThat(failures).as("no concurrent publish may fail").isEmpty();
        return new ArrayList<>(acked);
    }

    private static void publishBatch(StreamPartitionManager manager,
                                     int thread,
                                     int perThread,
                                     CountDownLatch start,
                                     ConcurrentLinkedQueue<Acked> acked,
                                     ConcurrentLinkedQueue<String> failures) {
        awaitLatch(start);

        for (int i = 0; i < perThread; i++) {
            var payload = "t" + thread + "-" + i;

            manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1000L + i)
                   .onSuccess(offset -> acked.add(new Acked(offset, payload)))
                   .onFailure(cause -> failures.add(cause.message()));
        }
    }

    private static Map<Long, String> indexByOffset(List<Acked> acked) {
        var byOffset = new HashMap<Long, String>();

        acked.forEach(a -> byOffset.put(a.offset(), a.payload()));
        return byOffset;
    }

    private static void assertEveryOffsetReadsBackItsPayload(StreamPartitionManager manager,
                                                             Map<Long, String> byOffset,
                                                             int total) {
        for (long from = 0; from < total; from += 1_000) {
            var events = manager.readLocal(STREAM, PARTITION, from, 1_000).unwrap();

            assertThat(events).hasSize(1_000);
            events.forEach(event -> assertThat(new String(event.data(), UTF_8)).as("payload at offset %d", event.offset())
                                                                              .isEqualTo(byOffset.get(event.offset())));
        }
    }

    private static List<WalRecord> replayAll(Path file) {
        var wal = PartitionWal.open(file).unwrap();
        var records = new ArrayList<WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        wal.close();
        return records;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private static void awaitTermination(ExecutorService pool) {
        try {
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("publishers finished").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private record Acked(long offset, String payload) {}

    /// Records the offset of every `replicateEvent` in invocation order; everything else is a no-op.
    private static final class RecordingReplicationManager implements ReplicationManager {
        private final ConcurrentLinkedQueue<Long> sentOffsets = new ConcurrentLinkedQueue<>();
        private final ReplicaRegistry registry = ReplicaRegistry.replicaRegistry();

        @Contract
        @Override
        public void replicateEvent(String streamName,
                                   int partition,
                                   long offset,
                                   byte[] payload,
                                   long timestamp,
                                   Epoch ownerEpoch) {
            sentOffsets.add(offset);
        }

        @Contract
        @Override
        public void handleAck(ReplicationMessage.ReplicateAck ack) {}

        @Override
        public ReplicaRegistry registry() {
            return registry;
        }

        @Override
        public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
            return Promise.unitPromise();
        }

        @Override
        public long replicatedThrough(String streamName, int partition, int minAcks) {
            return Long.MAX_VALUE;
        }

        @Override
        public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
            return Long.MAX_VALUE;
        }

        @Contract
        @Override
        public void observeAcks(AckObserver observer) {}
    }
}
