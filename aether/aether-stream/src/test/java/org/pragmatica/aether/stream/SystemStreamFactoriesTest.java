// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Fix #3 — proves the forward-capable system-stream CONSUMER lets a node OUTSIDE the system stream's
/// replica set read-forward to a caught-up replica (the owner) instead of reading its own empty local
/// partition. The local-only overload returned `200 []` on a non-replica node; this overload threads
/// the replica registry + forward client + self id + `ANY_REPLICA` read preference so the read is
/// routed to a replica that actually has the data.
class SystemStreamFactoriesTest {
    private static final NodeId SELF = new NodeId("non-replica-node");
    private static final NodeId OWNER = new NodeId("owner-replica-node");
    private static final String STREAM = SystemStreams.CLUSTER_EVENTS.asString();

    private StreamPartitionManager partitionManager;
    private ReplicaRegistry replicaRegistry;
    private RecordingForwardClient forwardClient;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        replicaRegistry = ReplicaRegistry.replicaRegistry();
        forwardClient = new RecordingForwardClient();
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    private static StreamConfig clusterEventsConfig() {
        var retention = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000);
        return StreamConfig.streamConfig(STREAM, 1, retention, "earliest");
    }

    @Test
    void systemStreamConsumer_forwardCapable_nonReplicaForwardsToCaughtUpReplica() {
        // SELF is NOT a replica of the system stream; OWNER is the sole caught-up replica.
        replicaRegistry.registerReplica(STREAM, 0, OWNER);
        replicaRegistry.updateWatermark(STREAM, 0, OWNER, 100L);
        forwardClient.setSuccess(OWNER, List.of(new RawEventDto(0L, 1_000L, "evt".getBytes())));

        var consumer = SystemStreamFactories.<byte[]>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS,
                                                                          partitionManager,
                                                                          identitySerializer(),
                                                                          identityDeserializer(),
                                                                          clusterEventsConfig(),
                                                                          replicaRegistry,
                                                                          Option.some(forwardClient),
                                                                          SELF,
                                                                          Option.none(),
                                                                          StreamReadForwardMetrics.NOOP)
                                            .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected consumer wiring success"))
                                            .unwrap();

        var events = consumer.fetch(0L, 10).await().unwrap();

        assertThat(forwardClient.targets())
            .as("a non-replica node must read-forward to the caught-up replica, not read empty locally")
            .contains(OWNER);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().payload()).isEqualTo("evt".getBytes());
    }

    @Test
    void systemStreamConsumer_forwardCapable_bootstrapWindowFailsSoftToLocal() {
        // No caught-up replica visible yet AND no owner resolvable (bootstrap window): the read must fail
        // soft to the local partition rather than fail/forward. SELF owns a local partition with one event.
        partitionManager.createStream(clusterEventsConfig());
        partitionManager.publishLocal(STREAM, 0, "local".getBytes(), 1_000L);

        var consumer = SystemStreamFactories.<byte[]>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS,
                                                                          partitionManager,
                                                                          identitySerializer(),
                                                                          identityDeserializer(),
                                                                          clusterEventsConfig(),
                                                                          replicaRegistry,
                                                                          Option.some(forwardClient),
                                                                          SELF,
                                                                          Option.none(),
                                                                          StreamReadForwardMetrics.NOOP)
                                            .unwrap();

        var events = consumer.fetch(0L, 10).await().unwrap();

        assertThat(forwardClient.targets())
            .as("with no caught-up replica visible, the read must fail soft to local — no forwarding")
            .isEmpty();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().payload()).isEqualTo("local".getBytes());
    }

    /// #265 increment 5 / owner decision 2026-07-05: a `system:*` stream over budget no longer fails
    /// soft-empty — it OVERSUBSCRIBES (bypasses the budget reject so a cluster-critical stream is never
    /// starved behind app-stream pressure), still returning a wired publisher AND materializing the partition,
    /// and surfaces the oversubscription through the exhaustion sink as a named SYSTEM_OVERSUBSCRIBE event
    /// (distinct from the app-stream CREATE_FLOOR deferral). App streams still defer; the exemption is
    /// system-only. (`SystemStreamRegistrar` still owns the leader-pinned retry; the node must boot.)
    @Test
    void ensureLocalPartition_overBudget_oversubscribesForSystemStream_andEmits() {
        var capturedExhaustions = new ArrayList<StreamPartitionManager.Exhaustion>();
        var tinyManager = StreamPartitionManager.streamPartitionManager(1L);
        tinyManager.exhaustionSink(capturedExhaustions::add);

        try {
            // 4 partitions, EVENTUAL: the per-partition floor cannot fit a 1-byte budget — a system stream
            // oversubscribes rather than reject.
            var retention = RetentionPolicy.retentionPolicy(10_000, 4 * 1024 * 1024L, 60_000L);
            var config = StreamConfig.streamConfig(STREAM, 4, retention, "earliest", 1024 * 1024L,
                                                   ConsistencyMode.EVENTUAL, 1);

            var publisher = SystemStreamFactories.<byte[]>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                               tinyManager,
                                                                               identitySerializer(),
                                                                               config)
                                                 .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("System-stream publisher wiring must stay fail-soft"))
                                                 .unwrap();

            assertThat(publisher).as("a wired publisher is still returned").isNotNull();
            assertThat(tinyManager.streamInfo(STREAM).isPresent()).as("the system stream is materialized past budget").isTrue();
            assertThat(tinyManager.totalAllocatedBytes()).as("budget is oversubscribed past the 1-byte cap").isGreaterThan(1L);
            assertThat(capturedExhaustions).as("the oversubscription is visible via the exhaustion sink")
                                           .anyMatch(e -> e.phase() == StreamPartitionManager.Exhaustion.Phase.SYSTEM_OVERSUBSCRIBE
                                                       && e.streamName().equals(STREAM));
        } finally {
            tinyManager.close();
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked") @Override public <T> byte[] encode(T object) {return (byte[]) object;}

            @Override public <T> void write(ByteBuf byteBuf, T object) {byteBuf.writeBytes((byte[]) object);}
        };
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked") @Override public <T> T decode(byte[] bytes) {return (T) bytes;}

            @SuppressWarnings("unchecked") @Override public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(bytes);
                return (T) bytes;
            }
        };
    }

    /// Records every `readRemote` target so the test can assert whether (and to whom) a read was
    /// forwarded.
    private static final class RecordingForwardClient implements StreamForwardClient {
        private final List<NodeId> targets = new ArrayList<>();
        private final java.util.Map<NodeId, List<RawEventDto>> scriptedSuccess = new java.util.concurrent.ConcurrentHashMap<>();

        void setSuccess(NodeId target, List<RawEventDto> events) {scriptedSuccess.put(target, events);}

        List<NodeId> targets() {return List.copyOf(targets);}

        @Override public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
            return Promise.success(0L);
        }

        @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                               String streamName,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
            targets.add(replicaId);
            var events = scriptedSuccess.get(replicaId);
            if (events == null) {return new StreamForwardError.ReadForwardFailed("no script for " + replicaId).promise();}
            return Promise.success(new ReadForwardResult(events, false));
        }

        @Override public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override public void onReadForwardResponse(ReadForwardResponse response) {}
    }
}
