// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicClusterServer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Serializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.DefaultStreamPublisher.streamPublisher;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #1245: an EVENTUAL `publishBatch` is a storage batch. Each partition group is appended in ONE ordered
/// section (ring batch, WAL frames, one replication message), committed once, and awaits replication of
/// its LAST offset once — and every event goes to the partition it was grouped under, never re-resolved.
class DefaultStreamPublisherBatchTest {
    private static final String STREAM = "s";

    private final ScheduledExecutorService peer = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        peer.shutdownNow();
    }

    /// Keyless round-robin assigns e_i to partition i mod 4 when the batch is grouped. Before the fix each
    /// event's partition was resolved a SECOND time while publishing its group, advancing the round-robin
    /// counter again: every partition still received two events, but not the two its group held.
    @Test
    void publishBatch_keyless_landsEachEventInThePartitionItWasGroupedUnder() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(config(4)).onFailure(cause -> fail(cause.message()));
        var publisher = streamPublisher(manager, identitySerializer(), STREAM, 4, Option.<java.util.function.Function<byte[], Object>> none());

        publisher.publishBatch(events(8)).await().onFailure(cause -> fail(cause.message()));

        IntStream.range(0, 4)
                 .forEach(partition -> assertThat(payloadsIn(manager, partition)).as("partition %d", partition)
                                                                                .containsExactly("e" + partition,
                                                                                                 "e" + (partition + 4)));
        manager.close();
    }

    /// Six events over four partitions: uneven groups, so ANY re-resolution of a group's partition lands
    /// it elsewhere. (With eight events the groups are keyed in round-robin order, and re-resolving once
    /// per group coincidentally reproduces the grouping — the ticket's 8-event case cannot see that.)
    @Test
    void publishBatch_keylessUnevenGroups_landEachGroupInItsGroupedPartition() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(config(4)).onFailure(cause -> fail(cause.message()));
        var publisher = streamPublisher(manager, identitySerializer(), STREAM, 4, Option.<java.util.function.Function<byte[], Object>> none());

        publisher.publishBatch(events(6)).await().onFailure(cause -> fail(cause.message()));

        assertThat(payloadsIn(manager, 0)).containsExactly("e0", "e4");
        assertThat(payloadsIn(manager, 1)).containsExactly("e1", "e5");
        assertThat(payloadsIn(manager, 2)).containsExactly("e2");
        assertThat(payloadsIn(manager, 3)).containsExactly("e3");
        manager.close();
    }

    /// The ticket's work bound (not a time bound): 100 same-partition events with a replica that acks each
    /// ReplicateEvents message 10 ms after receiving it. Before the fix every event was its own message and
    /// its own ack round-trip — 100 sends.
    @Test
    void publishBatch_samePartition_replicatesAsOneBatch_andAwaitsOneAck() {
        var self = NodeId.nodeId("self").unwrap();
        var replica = NodeId.nodeId("replica").unwrap();
        var registry = replicaRegistry();
        var sends = new AtomicInteger();
        var replication = new AtomicReference<ReplicationManager>();

        registry.registerReplica(STREAM, 0, self);
        registry.registerReplica(STREAM, 0, replica);
        replication.set(ReplicationManager.replicationManager(self,
                                                              registry,
                                                              (_, message) -> ackLater(message, replica, replication, sends)));

        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication.get());

        manager.createStream(config(1)).onFailure(cause -> fail(cause.message()));
        var publisher = streamPublisher(manager,
                                        identitySerializer(),
                                        STREAM,
                                        1,
                                        Option.<java.util.function.Function<byte[], Object>> none(),
                                        ConsistencyMode.EVENTUAL,
                                        Option.none(),
                                        2);

        publisher.publishBatch(events(100)).await().onFailure(cause -> fail(cause.message()));

        assertThat(sends.get()).as("ReplicateEvents messages for one 100-event batch").isBetween(1, 2);
        assertThat(payloadsIn(manager, 0)).hasSize(100).startsWith("e0").endsWith("e99");
        manager.close();
    }

    /// #1287 review K1: a batch must never be worse than the per-event publishes it replaced. The same
    /// payloads go through `publishBatch` on one manager and through one `publish` per event on another;
    /// both must succeed or fail alike and leave identical ring contents. Reviewer probe G: a 1 MB data
    /// region and 2,000 × 1 KB events — the batch used to be dropped whole and acked (head −1, 0 events).
    @Test
    void publishBatch_largerThanTheDataRegion_isNeverWorseThanPerEventPublishes() {
        assertBatchMatchesPerEvent(Option.none(), distinctEvents(2_000, 1024));
    }

    /// The same with a WAL: the run's frames must land at their offsets, contiguous, with no refusal.
    @Test
    void publishBatch_largerThanTheDataRegion_withWal_isNeverWorseThanPerEventPublishes() throws Exception {
        var batchWal = Files.createTempDirectory("batch-wal");
        var perEventWal = Files.createTempDirectory("per-event-wal");

        assertBatchMatchesPerEvent(Option.some(batchWal), Option.some(perEventWal), distinctEvents(2_000, 1024));
        assertThat(replayOffsets(batchWal)).containsExactlyElementsOf(LongStream.range(0, 2_000).boxed().toList());
    }

    /// #1287 review K2: the batch ack waits for its WAL group commit. With the WAL's fsync parked, the
    /// batch must not resolve; releasing the fsync resolves it.
    @Test
    void publishBatch_withWal_acksOnlyAfterItsGroupCommit() throws Exception {
        var walDir = Files.createTempDirectory("batch-fsync");
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        manager.createStream(config(1)).onFailure(cause -> fail(cause.message()));
        var gate = StreamPartitionManagerSectionReentrancyTest.GatedForceChannel.inject(StreamPartitionManagerSectionReentrancyTest.walOf(manager,
                                                                                                                                            STREAM,
                                                                                                                                            0));
        var publisher = streamPublisher(manager, identitySerializer(), STREAM, 1, Option.<java.util.function.Function<byte[], Object>> none());
        // The local batch blocks its caller at the durability barrier, so it runs on its own thread.
        var batch = CompletableFuture.supplyAsync(() -> publisher.publishBatch(events(10)).await());

        assertThat(gate.forceEntered.await(10, TimeUnit.SECONDS)).as("the batch reached its group commit").isTrue();
        Thread.sleep(300);
        assertThat(batch.isDone()).as("no ack while the fsync is parked").isFalse();

        gate.forceProceed.countDown();
        batch.get(10, TimeUnit.SECONDS).onFailure(cause -> fail(cause.message()));
        manager.close();
    }

    /// #1287 review K3: replication messages are split by bytes below the cluster transport's frame limit
    /// ([QuicClusterServer#MAX_FRAME_LENGTH]); the batch still awaits one ack on its last offset.
    @Test
    void publishBatch_largerThanTheFrameLimit_replicatesInSeveralMessagesUnderIt() {
        var self = NodeId.nodeId("self").unwrap();
        var replica = NodeId.nodeId("replica").unwrap();
        var registry = replicaRegistry();
        var sends = new AtomicInteger();
        var largestMessage = new AtomicLong();
        var replication = new AtomicReference<ReplicationManager>();

        registry.registerReplica(STREAM, 0, self);
        registry.registerReplica(STREAM, 0, replica);
        replication.set(ReplicationManager.replicationManager(self,
                                                              registry,
                                                              (_, message) -> ackLaterMeasuring(message,
                                                                                                replica,
                                                                                                replication,
                                                                                                sends,
                                                                                                largestMessage)));
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication.get());

        manager.createStream(config(1)).onFailure(cause -> fail(cause.message()));
        var publisher = streamPublisher(manager,
                                        identitySerializer(),
                                        STREAM,
                                        1,
                                        Option.<java.util.function.Function<byte[], Object>> none(),
                                        ConsistencyMode.EVENTUAL,
                                        Option.none(),
                                        2);
        var oneMegabyte = 1024 * 1024;
        var total = (long) oneMegabyte * 40;

        publisher.publishBatch(distinctEvents(40, oneMegabyte)).await().onFailure(cause -> fail(cause.message()));

        assertThat(total).as("the batch exceeds one frame").isGreaterThan(QuicClusterServer.MAX_FRAME_LENGTH);
        assertThat(sends.get()).as("ReplicateEvents messages").isGreaterThanOrEqualTo(2);
        assertThat(largestMessage.get()).as("largest message payload bytes").isLessThan(QuicClusterServer.MAX_FRAME_LENGTH);
        manager.close();
    }

    private void assertBatchMatchesPerEvent(Option<Path> walDir, List<byte[]> payloads) {
        assertBatchMatchesPerEvent(walDir, walDir, payloads);
    }

    private void assertBatchMatchesPerEvent(Option<Path> batchWal, Option<Path> perEventWal, List<byte[]> payloads) {
        var batchManager = streamPartitionManager(Long.MAX_VALUE, batchWal);
        var perEventManager = streamPartitionManager(Long.MAX_VALUE, perEventWal);
        var smallRing = StreamConfig.streamConfig(STREAM,
                                                  1,
                                                  RetentionPolicy.retentionPolicy(1_000_000, 1024L * 1024, 3_600_000),
                                                  "earliest");

        batchManager.createStream(smallRing).onFailure(cause -> fail(cause.message()));
        perEventManager.createStream(smallRing).onFailure(cause -> fail(cause.message()));
        var batchPublisher = streamPublisher(batchManager, identitySerializer(), STREAM, 1, Option.<java.util.function.Function<byte[], Object>> none());
        var perEventPublisher = streamPublisher(perEventManager, identitySerializer(), STREAM, 1, Option.<java.util.function.Function<byte[], Object>> none());

        var batchResult = batchPublisher.publishBatch(payloads).await();
        var perEventResults = payloads.stream().map(payload -> perEventPublisher.publish(payload).await()).toList();

        assertThat(batchResult.isSuccess()).as("batch outcome: %s", batchResult)
                                         .isEqualTo(perEventResults.stream().allMatch(Result::isSuccess));
        var batchRing = batchManager.partitionBuffer(STREAM, 0).unwrap();
        var perEventRing = perEventManager.partitionBuffer(STREAM, 0).unwrap();

        assertThat(batchRing.headOffset()).as("head").isEqualTo(perEventRing.headOffset());
        assertThat(batchRing.eventCount()).as("event count").isEqualTo(perEventRing.eventCount()).isPositive();
        assertThat(ringContents(batchManager)).as("stored events").isEqualTo(ringContents(perEventManager));
        batchManager.close();
        perEventManager.close();
    }

    private static List<String> ringContents(StreamPartitionManager manager) {
        var ring = manager.partitionBuffer(STREAM, 0).unwrap();

        return manager.readLocal(STREAM, 0, ring.tailOffset(), 1_000_000)
                      .or(List.of())
                      .stream()
                      .map(event -> event.offset() + ":" + new String(event.data(), 0, 8, UTF_8))
                      .toList();
    }

    private static List<Long> replayOffsets(Path walDir) {
        var wal = PartitionWal.open(walDir.resolve(STREAM).resolve("0.wal")).unwrap();
        var records = new java.util.ArrayList<PartitionWal.WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        wal.close();
        return records.stream().map(PartitionWal.WalRecord::offset).toList();
    }

    /// Payloads of `size` bytes whose first 8 bytes are a distinct zero-padded index.
    private static List<byte[]> distinctEvents(int count, int size) {
        return IntStream.range(0, count).mapToObj(i -> padded(i, size)).toList();
    }

    private static byte[] padded(int index, int size) {
        var payload = new byte[size];
        var label = "%08d".formatted(index).getBytes(UTF_8);

        System.arraycopy(label, 0, payload, 0, label.length);
        return payload;
    }

    private void ackLaterMeasuring(ReplicationMessage message,
                                   NodeId replica,
                                   AtomicReference<ReplicationManager> replication,
                                   AtomicInteger sends,
                                   AtomicLong largestMessage) {
        if (message instanceof ReplicationMessage.ReplicateEvents events) {
            largestMessage.accumulateAndGet(events.payloads().stream().mapToLong(p -> p.length).sum(), Math::max);
        }
        ackLater(message, replica, replication, sends);
    }

    private void ackLater(ReplicationMessage message,
                          NodeId replica,
                          AtomicReference<ReplicationManager> replication,
                          AtomicInteger sends) {
        if (message instanceof ReplicationMessage.ReplicateEvents events) {
            sends.incrementAndGet();
            var confirmed = events.fromOffset() + events.payloads().size() - 1;

            peer.schedule(() -> replication.get().handleAck(replicateAck(replica, STREAM, 0, confirmed)),
                          10,
                          TimeUnit.MILLISECONDS);
        }
    }

    private static StreamConfig config(int partitions) {
        return StreamConfig.streamConfig(STREAM,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100_000, 64L * 1024 * 1024, 3_600_000),
                                         "earliest");
    }

    private static List<byte[]> events(int count) {
        return IntStream.range(0, count).mapToObj(i -> ("e" + i).getBytes(UTF_8)).toList();
    }

    private static List<String> payloadsIn(StreamPartitionManager manager, int partition) {
        return manager.readLocal(STREAM, partition, 0, 1_000)
                      .or(List.of())
                      .stream()
                      .map(event -> new String(event.data(), UTF_8))
                      .toList();
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
    }
}
