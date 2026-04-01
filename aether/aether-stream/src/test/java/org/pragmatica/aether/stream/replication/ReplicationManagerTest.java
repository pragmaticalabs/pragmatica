package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.replication.ReplicationMetrics.replicationMetrics;

class ReplicationManagerTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final NodeId REPLICA_B = NodeId.randomNodeId();
    private static final String STREAM = "events";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "test-event".getBytes();
    private static final long TIMESTAMP = 1000L;

    private ReplicaRegistry registry;
    private List<SentMessage> sentMessages;
    private ReplicationManager manager;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        sentMessages = new ArrayList<>();
        ReplicationTransport capturingTransport = (target, message) -> sentMessages.add(new SentMessage(target, message));
        manager = replicationManager(GOVERNOR, registry, capturingTransport);
    }

    @Nested
    class ReplicateEventTests {

        @Test
        void replicateEvent_callsTransportForEachReplica() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);

            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).hasSize(2);
            assertThat(sentMessages).extracting(SentMessage::target)
                                    .containsExactlyInAnyOrder(REPLICA_A, REPLICA_B);
        }

        @Test
        void replicateEvent_noReplicas_noTransportCall() {
            manager.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).isEmpty();
        }

        @Test
        void replicateEvent_sendsCorrectMessage() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            manager.replicateEvent(STREAM, PARTITION, 5L, PAYLOAD, TIMESTAMP);

            assertThat(sentMessages).hasSize(1);
            var message = (ReplicationMessage.ReplicateEvents) sentMessages.getFirst().message();
            assertThat(message.governorId()).isEqualTo(GOVERNOR);
            assertThat(message.streamName()).isEqualTo(STREAM);
            assertThat(message.partition()).isEqualTo(PARTITION);
            assertThat(message.fromOffset()).isEqualTo(5L);
            assertThat(message.payloads()).hasSize(1);
            assertThat(message.timestamps()).containsExactly(TIMESTAMP);
        }
    }

    @Nested
    class HandleAckTests {

        @Test
        void handleAck_updatesWatermark() {
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);

            manager.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 10L));

            var replicas = registry.replicasFor(STREAM, PARTITION);
            assertThat(replicas.getFirst().confirmedOffset()).isEqualTo(10L);
            assertThat(replicas.getFirst().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }
    }

    @Nested
    class MetricsTests {

        @Test
        void replicationMetrics_computesLag() {
            var metrics = replicationMetrics(STREAM, PARTITION, 100L, 75L, 2);

            assertThat(metrics.maxLag()).isEqualTo(25L);
            assertThat(metrics.replicaCount()).isEqualTo(2);
        }

        @Test
        void replicationMetrics_zeroLag_whenCaughtUp() {
            var metrics = replicationMetrics(STREAM, PARTITION, 50L, 50L, 1);

            assertThat(metrics.maxLag()).isZero();
        }
    }

    @Nested
    class NoOpTests {

        @Test
        void noneManager_replicateEvent_doesNothing() {
            ReplicationManager.NONE.replicateEvent(STREAM, PARTITION, 0L, PAYLOAD, TIMESTAMP);

            assertThat(ReplicationManager.NONE.registry().replicasFor(STREAM, PARTITION)).isEmpty();
        }

        @Test
        void noneManager_handleAck_doesNothing() {
            ReplicationManager.NONE.handleAck(replicateAck(REPLICA_A, STREAM, PARTITION, 10L));

            assertThat(ReplicationManager.NONE.registry().replicasFor(STREAM, PARTITION)).isEmpty();
        }
    }

    /// Captured transport message for test assertions.
    record SentMessage(NodeId target, ReplicationMessage message) {}
}
