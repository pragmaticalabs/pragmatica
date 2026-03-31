package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaDescriptor.replicaDescriptor;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.BatchSync.batchSync;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;

class ReplicationMessageTest {

    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REPLICA = NodeId.randomNodeId();
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @Nested
    class ReplicateEventsTests {

        @Test
        void replicateEvents_factory_createsCorrectly() {
            var payloads = List.of("event1".getBytes(), "event2".getBytes());
            var timestamps = List.of(1000L, 2000L);

            var msg = replicateEvents(GOVERNOR, STREAM, PARTITION, 0L, payloads, timestamps);

            assertThat(msg.governorId()).isEqualTo(GOVERNOR);
            assertThat(msg.streamName()).isEqualTo(STREAM);
            assertThat(msg.partition()).isEqualTo(PARTITION);
            assertThat(msg.fromOffset()).isZero();
            assertThat(msg.payloads()).hasSize(2);
            assertThat(msg.timestamps()).containsExactly(1000L, 2000L);
        }

        @Test
        void replicateEvents_defensiveCopy_payloadsImmutable() {
            var original = "event1".getBytes();
            var payloads = new ArrayList<>(List.of(original));
            var timestamps = new ArrayList<>(List.of(1000L));

            var msg = replicateEvents(GOVERNOR, STREAM, PARTITION, 0L, payloads, timestamps);

            // Mutate original array — should not affect message
            original[0] = 'X';
            assertThat(msg.payloads().getFirst()[0]).isEqualTo((byte) 'e');

            // Mutate source list — should not affect message
            payloads.add("extra".getBytes());
            assertThat(msg.payloads()).hasSize(1);

            // Mutate returned list payload — should not affect internal state
            var returned = msg.payloads();
            returned.getFirst()[0] = 'Z';
            assertThat(msg.payloads().getFirst()[0]).isEqualTo((byte) 'e');
        }
    }

    @Nested
    class ReplicateAckTests {

        @Test
        void replicateAck_factory_roundTrip() {
            var msg = replicateAck(REPLICA, STREAM, PARTITION, 42L);

            assertThat(msg.replicaId()).isEqualTo(REPLICA);
            assertThat(msg.streamName()).isEqualTo(STREAM);
            assertThat(msg.partition()).isEqualTo(PARTITION);
            assertThat(msg.confirmedOffset()).isEqualTo(42L);
        }
    }

    @Nested
    class BatchSyncTests {

        @Test
        void batchSync_defensiveCopy_compressedBatchCloned() {
            var original = new byte[]{1, 2, 3, 4, 5};

            var msg = batchSync(GOVERNOR, STREAM, PARTITION, 0L, 100L, original);

            // Mutate original — should not affect message
            original[0] = 99;
            assertThat(msg.compressedBatch()[0]).isEqualTo((byte) 1);

            // Mutate returned — should not affect internal state
            var returned = msg.compressedBatch();
            returned[0] = 88;
            assertThat(msg.compressedBatch()[0]).isEqualTo((byte) 1);
        }

        @Test
        void batchSync_factory_createsCorrectly() {
            var batch = new byte[]{10, 20, 30};

            var msg = batchSync(GOVERNOR, STREAM, PARTITION, 5L, 50L, batch);

            assertThat(msg.governorId()).isEqualTo(GOVERNOR);
            assertThat(msg.streamName()).isEqualTo(STREAM);
            assertThat(msg.partition()).isEqualTo(PARTITION);
            assertThat(msg.fromOffset()).isEqualTo(5L);
            assertThat(msg.toOffset()).isEqualTo(50L);
            assertThat(msg.compressedBatch()).containsExactly(10, 20, 30);
        }
    }

    @Nested
    class CatchupRequestTests {

        @Test
        void catchupRequest_factory_createsCorrectly() {
            var msg = catchupRequest(REPLICA, STREAM, PARTITION, 100L);

            assertThat(msg.replicaId()).isEqualTo(REPLICA);
            assertThat(msg.streamName()).isEqualTo(STREAM);
            assertThat(msg.partition()).isEqualTo(PARTITION);
            assertThat(msg.fromOffset()).isEqualTo(100L);
        }
    }

    @Nested
    class CatchupResponseTests {

        @Test
        void catchupResponse_defensiveCopies() {
            var original = "data".getBytes();
            var payloads = new ArrayList<>(List.of(original));
            var timestamps = new ArrayList<>(List.of(5000L));

            var msg = catchupResponse(GOVERNOR, STREAM, PARTITION, 10L, 20L, payloads, timestamps);

            // Mutate original array
            original[0] = 'X';
            assertThat(msg.payloads().getFirst()[0]).isEqualTo((byte) 'd');

            // Mutate source list
            payloads.add("extra".getBytes());
            assertThat(msg.payloads()).hasSize(1);

            // Mutate returned payload
            msg.payloads().getFirst()[0] = 'Z';
            assertThat(msg.payloads().getFirst()[0]).isEqualTo((byte) 'd');
        }

        @Test
        void catchupResponse_factory_createsCorrectly() {
            var payloads = List.of("a".getBytes(), "b".getBytes());
            var timestamps = List.of(100L, 200L);

            var msg = catchupResponse(GOVERNOR, STREAM, PARTITION, 10L, 20L, payloads, timestamps);

            assertThat(msg.governorId()).isEqualTo(GOVERNOR);
            assertThat(msg.fromOffset()).isEqualTo(10L);
            assertThat(msg.toOffset()).isEqualTo(20L);
            assertThat(msg.payloads()).hasSize(2);
            assertThat(msg.timestamps()).containsExactly(100L, 200L);
        }
    }

    @Nested
    class ReplicaDescriptorTests {

        @Test
        void replicaDescriptor_factory_createsCorrectly() {
            var descriptor = replicaDescriptor(REPLICA, STREAM, PARTITION, 50L, ReplicationState.CAUGHT_UP);

            assertThat(descriptor.nodeId()).isEqualTo(REPLICA);
            assertThat(descriptor.streamName()).isEqualTo(STREAM);
            assertThat(descriptor.partition()).isEqualTo(PARTITION);
            assertThat(descriptor.confirmedOffset()).isEqualTo(50L);
            assertThat(descriptor.state()).isEqualTo(ReplicationState.CAUGHT_UP);
        }
    }
}
