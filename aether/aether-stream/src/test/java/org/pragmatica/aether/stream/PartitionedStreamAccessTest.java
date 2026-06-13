// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;


/// SPEC: §11.3 PartitionedStreamAccess read-routing tests via StreamReadRouter.
///
/// The router mirrors PartitionedStreamAccess.selectReplicaAndRead exactly
/// (SPEC: §6.2). Both use the identical filter → pick → retry policy; testing
/// the router exercises the same SPEC: §6.2 logic without the StreamAccess
/// typed-payload scaffolding.
class PartitionedStreamAccessTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId REPLICA_A = new NodeId("replica-a");
    private static final NodeId REPLICA_B = new NodeId("replica-b");
    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final long FROM_OFFSET = 0L;
    private static final int MAX_EVENTS = 10;

    private StreamPartitionManager partitionManager;
    private ReplicaRegistry replicaRegistry;
    private RecordingForwardClient forwardClient;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
        partitionManager.publishLocal(STREAM, PARTITION, "local-data".getBytes(), 999L);
        replicaRegistry = ReplicaRegistry.replicaRegistry();
        forwardClient = new RecordingForwardClient();
    }

    private StreamReadRouter router() {
        return StreamReadRouter.streamReadRouter(partitionManager,
                                                 Option.some(replicaRegistry),
                                                 Option.some(forwardClient),
                                                 SELF,
                                                 StreamReadForwardMetrics.NOOP);
    }

    @Nested
    class FallbackToLocalTests {

        // SPEC: §6.2.a zero caught-up replicas → local read
        @Test
        void selectReplicaAndRead_zeroCaughtUpReplicas_readsLocal() {
            // No replicas registered at all
            var result = router().read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        // SPEC: §6.2.a self-only caught-up → post-filter empty → local read
        @Test
        void selectReplicaAndRead_selfIsOnlyReplica_readsLocal() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 100L);

            var result = router().read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(forwardClient.reads).isEmpty();
        }

        // SPEC: §6.2.c no forwardClient → local read fallback
        @Test
        void selectReplicaAndRead_noForwardClient_readsLocal() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);

            var noClientRouter = StreamReadRouter.streamReadRouter(partitionManager,
                                                                    Option.some(replicaRegistry),
                                                                    Option.none(),
                                                                    SELF,
                                                                    StreamReadForwardMetrics.NOOP);
            var result = noClientRouter.read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
        }
    }

    @Nested
    class RemoteReadTests {

        @Test
        void selectReplicaAndRead_singleRemoteReplica_success() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            forwardClient.setSuccess(REPLICA_A, List.of(rawEvent(0L, "remote-a")));

            var result = router().read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat(list.getFirst().data()).isEqualTo("remote-a".getBytes());
            });
            assertThat(forwardClient.reads).hasSize(1);
        }

        // SPEC: §6.2.d single replica edge case → no retry → error propagated
        @Test
        void selectReplicaAndRead_singleRemoteReplica_failure_noRetry_returnsError() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            forwardClient.setFailure(REPLICA_A, "boom");

            var result = router().read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isFalse();
            assertThat(forwardClient.reads).hasSize(1);
        }

        // SPEC: §6.2.d primary fails → retry second replica → success
        @Test
        void selectReplicaAndRead_twoReplicas_primaryFails_secondSucceeds() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_B);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_B, 100L);
            // Fail whichever is picked first, succeed for whichever is second.
            forwardClient.failAllButLast = true;
            forwardClient.lastSuccessEvents = List.of(rawEvent(1L, "second-try"));

            var result = router().read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).hasSize(2);
            // SPEC: §6.2.d retry excludes primary — the two requests target different replicas
            var targets = new HashSet<NodeId>();
            forwardClient.reads.forEach(r -> targets.add(r.target()));
            assertThat(targets).hasSize(2);
        }

        // SPEC: §6.2.d both fail → error propagated
        @Test
        void selectReplicaAndRead_twoReplicas_bothFail_returnsError() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_B);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_B, 100L);
            forwardClient.failAll = true;

            var result = router().read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isFalse();
            assertThat(forwardClient.reads).hasSize(2);
        }

        // SPEC: §6.2.d retry excludes primary — correctness
        @Test
        void selectReplicaAndRead_twoReplicas_retryExcludesPrimary() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_B);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_B, 100L);
            forwardClient.failAllButLast = true;
            forwardClient.lastSuccessEvents = List.of(rawEvent(0L, "x"));

            router().read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(forwardClient.reads).hasSize(2);
            assertThat(forwardClient.reads.get(0).target()).isNotEqualTo(forwardClient.reads.get(1).target());
        }
    }

    private static RawEventDto rawEvent(long offset, String data) {
        return new RawEventDto(offset, System.currentTimeMillis(), data.getBytes());
    }

    /// Test helper — records readRemote calls and can be scripted to succeed/fail.
    static final class RecordingForwardClient implements StreamForwardClient {
        final java.util.List<ReadCall> reads = new java.util.ArrayList<>();
        private final java.util.Map<NodeId, List<RawEventDto>> scriptedSuccess = new ConcurrentHashMap<>();
        private final Set<NodeId> scriptedFailures = ConcurrentHashMap.newKeySet();
        boolean failAll = false;
        boolean failAllButLast = false;
        List<RawEventDto> lastSuccessEvents = List.of();

        void setSuccess(NodeId target, List<RawEventDto> events) {
            scriptedSuccess.put(target, events);
        }

        void setFailure(NodeId target, String reason) {
            scriptedFailures.add(target);
        }

        @Override public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
            return Promise.success(0L);
        }

        @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                                String streamName,
                                                                int partition,
                                                                long fromOffset,
                                                                int maxEvents) {
            reads.add(new ReadCall(replicaId));
            if (failAll) {return new StreamForwardError.ReadForwardFailed("scripted").promise();}
            if (failAllButLast) {
                // First call fails, second succeeds
                if (reads.size() == 1) {return new StreamForwardError.ReadForwardFailed("first").promise();}
                return Promise.success(new ReadForwardResult(lastSuccessEvents, false));
            }
            if (scriptedFailures.contains(replicaId)) {return new StreamForwardError.ReadForwardFailed("scripted").promise();}
            var events = scriptedSuccess.getOrDefault(replicaId, List.of());
            return Promise.success(new ReadForwardResult(events, false));
        }

        @Override public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override public void onReadForwardResponse(ReadForwardResponse response) {}

        record ReadCall(NodeId target) {}
    }
}
