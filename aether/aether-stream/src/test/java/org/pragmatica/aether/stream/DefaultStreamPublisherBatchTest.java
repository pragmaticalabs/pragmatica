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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

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
