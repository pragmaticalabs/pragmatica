package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;

class ReplicationWiringTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REPLICA = NodeId.randomNodeId();
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @Nested
    class WithReplicationManager {

        private StreamPartitionManager manager;
        private List<SentMessage> sentMessages;

        @BeforeEach
        void setUp() {
            sentMessages = new ArrayList<>();
            ReplicationTransport capturingTransport = (target, message) -> sentMessages.add(new SentMessage(target, message));
            var registry = replicaRegistry();
            registry.registerReplica(STREAM, PARTITION, REPLICA);
            var replicationManager = replicationManager(GOVERNOR, registry, capturingTransport);
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replicationManager);
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            manager.createStream(StreamConfig.streamConfig(STREAM, 4, retention, "latest"));
        }

        @AfterEach
        void tearDown() {
            manager.close();
        }

        @Test
        void publishLocal_withReplicationManager_callsTransport() {
            manager.publishLocal(STREAM, PARTITION, "event-data".getBytes(), 1000L)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            assertThat(sentMessages).hasSize(1);

            var sent = sentMessages.getFirst();
            assertThat(sent.target()).isEqualTo(REPLICA);
            assertThat(sent.message()).isInstanceOf(ReplicationMessage.ReplicateEvents.class);

            var replicateEvents = (ReplicationMessage.ReplicateEvents) sent.message();
            assertThat(replicateEvents.governorId()).isEqualTo(GOVERNOR);
            assertThat(replicateEvents.streamName()).isEqualTo(STREAM);
            assertThat(replicateEvents.partition()).isEqualTo(PARTITION);
            assertThat(replicateEvents.fromOffset()).isEqualTo(0L);
            assertThat(replicateEvents.payloads()).hasSize(1);
            assertThat(replicateEvents.payloads().getFirst()).isEqualTo("event-data".getBytes());
        }
    }

    @Nested
    class WithNoopReplication {

        private StreamPartitionManager manager;

        @BeforeEach
        void setUp() {
            manager = streamPartitionManager(Long.MAX_VALUE);
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            manager.createStream(StreamConfig.streamConfig(STREAM, 4, retention, "latest"));
        }

        @AfterEach
        void tearDown() {
            manager.close();
        }

        @Test
        void publishLocal_withNoopReplication_noTransportCall() {
            manager.publishLocal(STREAM, PARTITION, "event-data".getBytes(), 1000L)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(offset -> assertThat(offset).isEqualTo(0L));

            // NOOP manager has an empty registry -- no replicas to send to
            assertThat(ReplicationManager.NONE.registry().replicasFor(STREAM, PARTITION)).isEmpty();
        }
    }

    record SentMessage(NodeId target, ReplicationMessage message) {}
}
