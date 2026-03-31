package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.FailoverRecovery.RecoveryResult;
import static org.pragmatica.aether.stream.replication.FailoverRecovery.failoverRecovery;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

class FailoverRecoveryTest {

    private static final String STREAM = "orders";
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final NodeId REPLICA_B = NodeId.randomNodeId();
    private static final byte[] EVENT_1 = "event-1".getBytes();
    private static final byte[] EVENT_2 = "event-2".getBytes();
    private static final long TS_1 = 1000L;
    private static final long TS_2 = 2000L;

    private ReplicaRegistry registry;
    private List<CapturedRequest> capturedRequests;
    private List<RecoveredEvent> recoveredEvents;
    private FailoverRecovery recovery;

    private final AtomicLong eventCounter = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        capturedRequests = new ArrayList<>();
        recoveredEvents = new ArrayList<>();
        eventCounter.set(0);

        recovery = failoverRecovery(registry, this::handleRecoveredEvent, this::handleCatchupRequest);
    }

    private Promise<ReplicationMessage.CatchupResponse> handleCatchupRequest(NodeId target,
                                                                              ReplicationMessage.CatchupRequest request) {
        capturedRequests.add(new CapturedRequest(target, request));
        return Promise.success(catchupResponse(target, request.streamName(), request.partition(),
                                               request.fromOffset(), request.fromOffset() + 1,
                                               List.of(EVENT_1, EVENT_2), List.of(TS_1, TS_2)));
    }

    private Result<Long> handleRecoveredEvent(String streamName, int partition, byte[] payload, long timestamp) {
        recoveredEvents.add(new RecoveredEvent(streamName, partition, payload.clone(), timestamp));
        return Result.success(eventCounter.incrementAndGet());
    }

    private RecoveryResult awaitSuccess(Promise<RecoveryResult> promise) {
        return promise.await()
                      .onFailure(_ -> Assertions.fail("Expected success"))
                      .or(RecoveryResult.recoveryResult(0));
    }

    @Nested
    class RecoverFromWatermarks {

        @Test
        void recover_fromWatermarks_identifiesBestReplica() {
            registry.registerReplica(STREAM, 0, REPLICA_A);
            registry.registerReplica(STREAM, 0, REPLICA_B);
            registry.updateWatermark(STREAM, 0, REPLICA_A, 5L);
            registry.updateWatermark(STREAM, 0, REPLICA_B, 10L);

            var r = awaitSuccess(recovery.recover(STREAM, 1));

            assertThat(r.partitionsRecovered()).isEqualTo(1);
            assertThat(r.eventsReplayed()).isEqualTo(2);
            assertThat(capturedRequests).hasSize(1);
            assertThat(capturedRequests.getFirst().target()).isEqualTo(REPLICA_B);
            assertThat(capturedRequests.getFirst().request().fromOffset()).isEqualTo(11L);
        }
    }

    @Nested
    class EmptyPartition {

        @Test
        void recover_emptyPartition_createsBufferWithEvents() {
            registry.registerReplica(STREAM, 0, REPLICA_A);
            registry.updateWatermark(STREAM, 0, REPLICA_A, -1L);

            var r = awaitSuccess(recovery.recover(STREAM, 1));

            assertThat(r.partitionsRecovered()).isEqualTo(1);
            assertThat(r.eventsReplayed()).isEqualTo(2);
            assertThat(recoveredEvents).hasSize(2);
            assertThat(recoveredEvents.getFirst().streamName()).isEqualTo(STREAM);
            assertThat(recoveredEvents.getFirst().partition()).isEqualTo(0);
        }
    }

    @Nested
    class NoReplicas {

        @Test
        void recover_noReplicas_skipsPartition() {
            var r = awaitSuccess(recovery.recover(STREAM, 2));

            assertThat(r.partitionsRecovered()).isZero();
            assertThat(r.eventsReplayed()).isZero();
            assertThat(capturedRequests).isEmpty();
            assertThat(recoveredEvents).isEmpty();
        }
    }

    @Nested
    class RecoveryStats {

        @Test
        void recoveryResult_tracksStats() {
            registry.registerReplica(STREAM, 0, REPLICA_A);
            registry.registerReplica(STREAM, 1, REPLICA_B);
            registry.updateWatermark(STREAM, 0, REPLICA_A, 3L);
            registry.updateWatermark(STREAM, 1, REPLICA_B, 7L);

            var r = awaitSuccess(recovery.recover(STREAM, 2));

            assertThat(r.partitionsRecovered()).isEqualTo(2);
            assertThat(r.eventsReplayed()).isEqualTo(4);
            assertThat(r.recoveryMs()).isGreaterThanOrEqualTo(0);
            assertThat(capturedRequests).hasSize(2);
        }

        @Test
        void recoveryResult_empty_hasZeroStats() {
            var empty = RecoveryResult.recoveryResult(50L);

            assertThat(empty.partitionsRecovered()).isZero();
            assertThat(empty.eventsReplayed()).isZero();
            assertThat(empty.recoveryMs()).isEqualTo(50L);
        }
    }

    @Nested
    class MultiplePartitions {

        @Test
        void recover_multiplePartitions_recoversAll() {
            registry.registerReplica(STREAM, 0, REPLICA_A);
            registry.registerReplica(STREAM, 1, REPLICA_A);
            registry.registerReplica(STREAM, 2, REPLICA_B);
            registry.updateWatermark(STREAM, 0, REPLICA_A, 0L);
            registry.updateWatermark(STREAM, 1, REPLICA_A, 5L);
            registry.updateWatermark(STREAM, 2, REPLICA_B, 10L);

            var r = awaitSuccess(recovery.recover(STREAM, 3));

            assertThat(r.partitionsRecovered()).isEqualTo(3);
            assertThat(r.eventsReplayed()).isEqualTo(6);
            assertThat(capturedRequests).hasSize(3);
        }
    }

    /// Captured catch-up request for test assertions.
    record CapturedRequest(NodeId target, ReplicationMessage.CatchupRequest request) {}

    /// Captured recovered event for test assertions.
    record RecoveredEvent(String streamName, int partition, byte[] payload, long timestamp) {}
}
