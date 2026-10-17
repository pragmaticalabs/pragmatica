// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.FailoverRecovery.RecoveryResult;
import static org.pragmatica.aether.stream.replication.FailoverRecovery.failoverRecovery;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.NO_DURABILITY_BARRIER;

class FailoverRecoveryTest {

    private static final String STREAM = "orders";
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final NodeId REPLICA_B = NodeId.randomNodeId();
    private static final byte[] EVENT_1 = "event-1".getBytes();
    private static final byte[] EVENT_2 = "event-2".getBytes();
    private static final long TS_1 = 1000L;
    private static final long TS_2 = 2000L;
    private static final Epoch E1 = Epoch.epoch(0,1, 0);
    private static final Epoch E2 = Epoch.epoch(0,2, 0);

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

        recovery = failoverRecovery(registry, AlignedRecovery.appendOnly(this::handleRecoveredEvent), this::handleCatchupRequest, NO_DURABILITY_BARRIER);
    }

    private Promise<ReplicationMessage.CatchupResponse> handleCatchupRequest(NodeId target,
                                                                              ReplicationMessage.CatchupRequest request) {
        capturedRequests.add(new CapturedRequest(target, request));
        return Promise.success(catchupResponse(target, request.streamName(), request.partition(),
                                               request.fromOffset(), request.fromOffset() + 1,
                                               List.of(EVENT_1, EVENT_2), List.of(TS_1, TS_2)));
    }

    private Result<Long> handleRecoveredEvent(String streamName, int partition, long offset, byte[] payload, long timestamp) {
        recoveredEvents.add(new RecoveredEvent(streamName, partition, payload.clone(), timestamp));
        return Result.success(eventCounter.incrementAndGet());
    }

    private RecoveryResult awaitSuccess(Promise<RecoveryResult> promise) {
        return promise.await()
                      .onFailure(_ -> Assertions.fail("Expected success"))
                      .or(RecoveryResult.recoveryResult(0));
    }

    /// #1638 F4: catch-up failover recovery into a WAL-backed replica. The page's apply fails at offset 4 after the
    /// source's slice (e1@0, e2@4) was installed; e2@4 lies above head 3 and is dropped, so the retry installs
    /// cleanly and the copy is never ranked by e2. Red under "the recovery applies outside the seam's settlement".
    @Nested
    class WalBackedReplica {
        @TempDir
        Path walDir;

        private StreamPartitionManager replica;

        @BeforeEach
        void openReplica() {
            replica = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            replica.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> Assertions.fail(cause.message()));
            for (var offset = 0; offset < 3; offset++) {
                replica.appendRecovered(STREAM, 0, offset, EVENT_1, TS_1, E1).onFailure(cause -> Assertions.fail(cause.message()));
            }
            registry.registerReplica(STREAM, 0, REPLICA_A);
            registry.updateWatermark(STREAM, 0, REPLICA_A, 2L);
        }

        @AfterEach
        void closeReplica() {
            replica.close();
        }

        @Test
        void failedApply_isTrimmed_andTheRetryInstallsTheSlice() {
            var real = replica.alignedRecovery();
            var failing = AlignedRecovery.alignedRecovery((stream, partition, offset, payload, timestamp) -> offset == 4
                                                                                                          ? Causes.cause("injected apply failure").<Long> result()
                                                                                                          : real.appendRecovered(stream, partition, offset, payload, timestamp),
                                                          real::applyAttributed,
                                                          real::applyUnattributed);

            assertThat(failoverRecovery(registry, failing, this::sevenRecordsFromThree, NO_DURABILITY_BARRIER).recover(STREAM, 1)
                                                                                                             .await()
                                                                                                             .isFailure()).isTrue();
            assertThat(replica.nextExpectedOffset(STREAM, 0)).isEqualTo(4L);
            assertThat(replica.epochHistory(STREAM, 0).unwrap()).as("e2@4 trimmed").containsExactly(at(E1, 0));

            failoverRecovery(registry, real, this::sevenRecordsFromThree, NO_DURABILITY_BARRIER).recover(STREAM, 1)
                                                                                               .await()
                                                                                               .onFailure(cause -> Assertions.fail("the retry: " + cause.message()));

            assertThat(replica.epochHistory(STREAM, 0).unwrap()).containsExactly(at(E1, 0), at(E2, 4));
        }

        private Promise<ReplicationMessage.CatchupResponse> sevenRecordsFromThree(NodeId target,
                                                                                 ReplicationMessage.CatchupRequest request) {
            return Promise.success(catchupResponse(target,
                                                   request.streamName(),
                                                   request.partition(),
                                                   3,
                                                   9,
                                                   Collections.nCopies(7, EVENT_2),
                                                   Collections.nCopies(7, TS_2),
                                                   List.of(at(E1, 0), at(E2, 4))));
        }

        private static ProvenanceEntry at(Epoch epoch, long start) {
            return ProvenanceEntry.provenanceEntry(epoch, Option.none(), start);
        }
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
