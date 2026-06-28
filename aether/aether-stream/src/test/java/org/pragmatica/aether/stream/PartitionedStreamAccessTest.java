// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
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
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

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
                                                 (_, _) -> Option.none(),
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
                                                                    (_, _) -> Option.none(),
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

    /// SPEC: Fix #3 forward-read — direct PartitionedStreamAccess owner-forward routing tests.
    ///
    /// Drives PartitionedStreamAccess.selectReplicaAndRead through the forward-capable
    /// `streamAccess` overload that carries the HRW owner-resolver (the production
    /// `system:cluster-events` wiring). A lagging non-owner with NO locally-known caught-up
    /// peer must forward the read to the deterministic owner instead of reading its own empty
    /// local partition (the `200 []` regression).
    @Nested
    class OwnerForwardTests {
        private static final NodeId OWNER = new NodeId("owner-node");

        // SPEC: Fix #3 — lagging non-owner, no caught-up peer → forwards to HRW owner, returns owner events
        @Test
        void selectReplicaAndRead_laggingNonOwner_forwardsToOwner_returnsOwnerEvents() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            forwardClient.setSuccess(OWNER, List.of(rawEvent(0L, "owner-data")));

            var access = ownerForwardingAccess(Option.some(OWNER));
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat((byte[]) list.getFirst().payload()).isEqualTo("owner-data".getBytes());
            });
            assertThat(forwardClient.reads).hasSize(1);
            assertThat(forwardClient.reads.getFirst().target()).isEqualTo(OWNER);
        }

        // SPEC: Fix #3 — self CAUGHT_UP → reads local, no forward attempt
        @Test
        void selectReplicaAndRead_selfCaughtUp_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 100L);

            var access = ownerForwardingAccess(Option.some(OWNER));
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        // SPEC: Fix #3 — a peer locally marked CAUGHT_UP → routed via attemptPrimary, not owner-forward
        @Test
        void selectReplicaAndRead_caughtUpPeer_routesViaPrimary() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            forwardClient.setSuccess(REPLICA_A, List.of(rawEvent(0L, "peer-data")));

            var access = ownerForwardingAccess(Option.some(OWNER));
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat((byte[]) list.getFirst().payload()).isEqualTo("peer-data".getBytes());
            });
            assertThat(forwardClient.reads).hasSize(1);
            assertThat(forwardClient.reads.getFirst().target()).isEqualTo(REPLICA_A);
        }

        // SPEC: Fix #3 — owner == self and self not caught-up → best-effort local read, no forward
        @Test
        void selectReplicaAndRead_ownerIsSelf_notCaughtUp_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);

            var access = ownerForwardingAccess(Option.some(SELF));
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        // SPEC: Fix #3 — owner unknown (bootstrap window) → fail-soft local read, no forward
        @Test
        void selectReplicaAndRead_ownerUnknown_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);

            var access = ownerForwardingAccess(Option.none());
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        private PartitionedStreamAccess<byte[]> ownerForwardingAccess(Option<NodeId> owner) {
            return PartitionedStreamAccess.streamAccess(partitionManager,
                                                        identitySerializer(),
                                                        identityDeserializer(),
                                                        STREAM,
                                                        1,
                                                        Option.none(),
                                                        ReadPreference.ANY_REPLICA,
                                                        replicaRegistry,
                                                        Option.some(forwardClient),
                                                        SELF,
                                                        Option.some(() -> owner),
                                                        StreamReadForwardMetrics.NOOP);
        }
    }

    /// A6 read-forwarding — the APP durable owner-routed overload (the `StreamAccessFactory.ownerRoutedAccess`
    /// wiring) now pins NEAREST and threads the replica registry, so an app-stream consumer deployed on a
    /// NON-replica node forwards the read to the partition's HRW owner instead of reading its own empty
    /// local partition. Reproduces the A6 `StreamCrashDurabilityTest` read path at the unit level: with the
    /// registry wired the non-replica forwards (the fix); with NO registry NEAREST short-circuits to a local
    /// read (the no-op the GOVERNOR→NEAREST-only plan would have shipped).
    @Nested
    class DurableOwnerRoutedNearestTests {
        private static final NodeId OWNER = new NodeId("owner-node");

        // Non-replica consumer whose LOCAL partition is empty for the requested range → forwards to the
        // HRW owner and returns the owner's events (the A6 post-restart non-owner read). Reads from an
        // offset past the single seeded local event so the local read is empty.
        @Test
        void durableOwnerRouted_nonReplica_localEmpty_forwardsToOwner() {
            forwardClient.setSuccess(OWNER, List.of(rawEvent(5L, "owner-data")));

            var access = durableOwnerRoutedAccess(Option.some(OWNER), Option.some(replicaRegistry));
            var result = access.fetch(PARTITION, 1L, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat((byte[]) list.getFirst().payload()).isEqualTo("owner-data".getBytes());
            });
            assertThat(forwardClient.reads).hasSize(1);
            assertThat(forwardClient.reads.getFirst().target()).isEqualTo(OWNER);
        }

        // Non-replica consumer that NONETHELESS holds the data locally (owner-routed publish landed here;
        // placement/registry never marked it a replica — the StreamFanoutConsumerTest case) → reads LOCAL,
        // does NOT forward away from the data it actually has. This is the fan-out regression guard.
        @Test
        void durableOwnerRouted_nonReplica_localHasData_readsLocal_noForward() {
            forwardClient.setSuccess(OWNER, List.of(rawEvent(0L, "owner-data")));

            var access = durableOwnerRoutedAccess(Option.some(OWNER), Option.some(replicaRegistry));
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat((byte[]) list.getFirst().payload()).isEqualTo("local-data".getBytes());
            });
            assertThat(forwardClient.reads).isEmpty();
        }

        // Regression guard for the prior plan's no-op: NEAREST with NO registry short-circuits to a local
        // read (the partition seeded in setUp), never forwarding — exactly why flipping GOVERNOR→NEAREST
        // WITHOUT wiring the registry would have left A6 red.
        @Test
        void durableOwnerRouted_noRegistry_readsLocal_noForward() {
            var access = durableOwnerRoutedAccess(Option.some(OWNER), Option.none());
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        // Self IS a caught-up replica → NEAREST keeps the read local (zero forward overhead for the common
        // owner/replica-local case) even with the registry wired.
        @Test
        void durableOwnerRouted_selfCaughtUp_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 100L);

            var access = durableOwnerRoutedAccess(Option.some(OWNER), Option.some(replicaRegistry));
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        // RF>1: self AND a remote are both caught-up replicas. NEAREST is LOCAL-FIRST — it must read the
        // LOCAL copy (the publisher's node), NOT forward to the remote replica (which may lag). This is the
        // StreamFanoutConsumerTest regression guard: the prior NEAREST==ANY_REPLICA behavior forwarded to a
        // remote replica even when self held the data, under-reading a replicated stream.
        @Test
        void durableOwnerRouted_selfCaughtUp_remoteAlsoCaughtUp_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 100L);
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            forwardClient.setSuccess(REPLICA_A, List.of(rawEvent(0L, "remote-data")));

            var access = durableOwnerRoutedAccess(Option.some(OWNER), Option.some(replicaRegistry));
            var result = access.fetch(PARTITION, FROM_OFFSET, MAX_EVENTS).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        private PartitionedStreamAccess<byte[]> durableOwnerRoutedAccess(Option<NodeId> owner, Option<ReplicaRegistry> registry) {
            return PartitionedStreamAccess.streamAccess(partitionManager,
                                                        identitySerializer(),
                                                        identityDeserializer(),
                                                        STREAM,
                                                        1,
                                                        Option.none(),
                                                        Option.some(forwardClient),
                                                        SELF,
                                                        Option.some(() -> owner),
                                                        Option.none(),
                                                        0,
                                                        Option.none(),
                                                        Option.none(),
                                                        registry);
        }
    }

    /// Convergence proof — the raw-event StreamReadRouter exercises the SAME shared
    /// ForwardingReadRouter core as PartitionedStreamAccess, so a non-owner generic stream-read API
    /// read (StreamRoutes) forwards to the owner instead of returning its own empty local partition.
    @Nested
    class RouterOwnerForwardTests {
        private static final NodeId OWNER = new NodeId("owner-node");

        // Lagging non-owner, no caught-up peer → forwards to HRW owner, returns owner's raw events
        @Test
        void read_laggingNonOwner_forwardsToOwner_returnsOwnerEvents() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            forwardClient.setSuccess(OWNER, List.of(rawEvent(0L, "owner-data")));

            var result = ownerRouter(Option.some(OWNER)).read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat(list.getFirst().data()).isEqualTo("owner-data".getBytes());
            });
            assertThat(forwardClient.reads).hasSize(1);
            assertThat(forwardClient.reads.getFirst().target()).isEqualTo(OWNER);
        }

        // Self CAUGHT_UP → reads local, no forward attempt
        @Test
        void read_selfCaughtUp_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 100L);

            var result = ownerRouter(Option.some(OWNER)).read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        // A peer locally marked CAUGHT_UP → routed via attemptPrimary to that peer, not owner-forward
        @Test
        void read_caughtUpPeer_routesViaPrimary() {
            replicaRegistry.registerReplica(STREAM, PARTITION, REPLICA_A);
            replicaRegistry.updateWatermark(STREAM, PARTITION, REPLICA_A, 100L);
            forwardClient.setSuccess(REPLICA_A, List.of(rawEvent(0L, "peer-data")));

            var result = ownerRouter(Option.some(OWNER)).read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> {
                assertThat(list).hasSize(1);
                assertThat(list.getFirst().data()).isEqualTo("peer-data".getBytes());
            });
            assertThat(forwardClient.reads).hasSize(1);
            assertThat(forwardClient.reads.getFirst().target()).isEqualTo(REPLICA_A);
        }

        // owner == self and self not caught-up → best-effort local read, no forward / loop
        @Test
        void read_ownerIsSelf_notCaughtUp_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);

            var result = ownerRouter(Option.some(SELF)).read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        // owner unknown (bootstrap window) → fail-soft local read, no forward
        @Test
        void read_ownerUnknown_readsLocal_noForward() {
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);

            var result = ownerRouter(Option.none()).read(STREAM, PARTITION, FROM_OFFSET, MAX_EVENTS, ReadPreference.ANY_REPLICA).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(list -> assertThat(list).hasSize(1));
            assertThat(forwardClient.reads).isEmpty();
        }

        private StreamReadRouter ownerRouter(Option<NodeId> owner) {
            return StreamReadRouter.streamReadRouter(partitionManager,
                                                     Option.some(replicaRegistry),
                                                     Option.some(forwardClient),
                                                     SELF,
                                                     (_, _) -> owner,
                                                     StreamReadForwardMetrics.NOOP);
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(byte[] bytes) {
                return (T) bytes;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];

                byteBuf.readBytes(bytes);

                return (T) bytes;
            }
        };
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
