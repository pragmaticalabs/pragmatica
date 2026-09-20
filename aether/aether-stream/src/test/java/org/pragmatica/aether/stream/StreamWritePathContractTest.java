// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1263: the three write entry points — the slice `StreamPublisher` ({@link DefaultStreamPublisher}),
/// `StreamAccess.publish` ({@link PartitionedStreamAccess}) and the management publish
/// ({@link StreamWriteRouter}) — are ONE owner-routed write operation. Every scenario in [Contract] (7 of them) runs against
/// all three (one `@Nested` subclass per entry point), so a defect in the shared router reddens all three
/// rather than one, and a path that drifts reddens alone.
///
/// One deliberate exception: a STRONG stream on the slice-publisher path is not served by the shared router.
/// STRONG takes `DefaultStreamPublisher`'s explicit consensus alternative (#1263: "an explicit alternative, not
/// a hidden branch"), because when a consensus path IS wired it must be used rather than refused. With none
/// wired it refuses with the same `CONSENSUS_PATH_UNAVAILABLE`. So the two STRONG cells of
/// `StreamPublisherPath` pin that consensus branch, not the router; every other cell is served by the router.
///
/// Fixture: the partition ring is materialized on this node, then its placement role flips to REPLICA —
/// the state a replica ring is in on a real node (#1230). The stream declares `min-sync-replicas = 2`.
class StreamWritePathContractTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final String STREAM = "contract-stream";
    private static final String STRONG_STREAM = "contract-strong-stream";
    private static final String UNKNOWN_STREAM = "contract-unknown-stream";
    private static final int PARTITION = 0;
    private static final int DECLARED_MIN_SYNC = 2;
    private static final int RAISED_MIN_SYNC = 3;
    private static final long FORWARDED_OFFSET = 42L;

    private final List<Integer> awaitedMinAcks = new ArrayList<>();
    private StreamPartitionManager partitionManager;
    private RecordingForwardClient forwardClient;

    /// The scenarios every write entry point must satisfy; each `@Nested` subclass supplies one entry point.
    /// An entry point is built once per `(stream, owner)` and reused, so a scenario publishing twice exercises
    /// the SAME instance — what a slice holding its publisher does.
    abstract class Contract {
        private final Map<String, Fn0<Promise<Unit>>> entryPoints = new HashMap<>();

        abstract Fn0<Promise<Unit>> entryPoint(String stream, NodeId hrwOwner);

        Promise<Unit> publish(String stream, NodeId hrwOwner) {
            return entryPoints.computeIfAbsent(stream + "@" + hrwOwner.id(), _ -> entryPoint(stream, hrwOwner))
                              .apply();
        }

        @BeforeEach
        void setUp() {
            partitionManager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> Result.unitResult(), recordingReplication());
            partitionManager.createStream(config(STREAM, ConsistencyMode.EVENTUAL)).onFailureRun(Assertions::fail);
            partitionManager.createStream(config(STRONG_STREAM, ConsistencyMode.STRONG)).onFailureRun(Assertions::fail);
            partitionManager.createStream(config(UNKNOWN_STREAM, ConsistencyMode.UNKNOWN)).onFailureRun(Assertions::fail);
            partitionManager.placementRoleSupplier((_, _) -> Role.REPLICA);
            forwardClient = new RecordingForwardClient();
        }

        @AfterEach
        void tearDown() {
            partitionManager.close();
        }

        @Test
        void publish_forwardsToRemoteOwner_evenThoughAReplicaRingIsMaterialized() {
            publish(STREAM, OWNER).await().onFailureRun(Assertions::fail);

            assertThat(forwardClient.owners).containsExactly(OWNER);
            assertThat(localHead(STREAM)).isEqualTo(-1L);
        }

        @Test
        void publish_appendsLocally_whenSelfIsTheOwner() {
            publish(STREAM, SELF).await().onFailureRun(Assertions::fail);

            assertThat(forwardClient.owners).isEmpty();
            assertThat(localHead(STREAM)).isZero();
        }

        /// The minSync barrier is the stream's DECLARED `min-sync-replicas`, read live from its committed config —
        /// never a value frozen into the entry point when it was constructed.
        @Test
        void publish_awaitsTheDeclaredMinSyncBarrier_readLive() {
            publish(STREAM, SELF).await().onFailureRun(Assertions::fail);

            assertThat(awaitedMinAcks).containsExactly(DECLARED_MIN_SYNC - 1);
        }

        /// "Read live" means per publish, not per construction: the committed config is raised between two
        /// publishes on the SAME entry point instance, and the second publish must wait for the new barrier.
        @Test
        void publish_awaitsTheRaisedMinSyncBarrier_afterTheCommittedConfigChanges() {
            publish(STREAM, SELF).await().onFailureRun(Assertions::fail);
            partitionManager.onStreamConfigPut(configPut(config(STREAM, ConsistencyMode.EVENTUAL, RAISED_MIN_SYNC)));
            publish(STREAM, SELF).await().onFailureRun(Assertions::fail);

            assertThat(awaitedMinAcks).containsExactly(DECLARED_MIN_SYNC - 1, RAISED_MIN_SYNC - 1);
        }

        @Test
        void publish_redirectsToCommittedOwner_whenSelfIsHrwOwnerButCommitIsElsewhere() {
            partitionManager.ownerWriteAdmission((_, _) -> Option.some(OWNER));

            publish(STREAM, SELF).await().onFailureRun(Assertions::fail);

            assertThat(forwardClient.owners).containsExactly(OWNER);
            assertThat(localHead(STREAM)).isEqualTo(-1L);
        }

        @Test
        void publish_refusesStrongStream_withConsensusPathUnavailable() {
            publish(STRONG_STREAM, SELF).await()
                                               .onSuccessRun(Assertions::fail)
                                               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSENSUS_PATH_UNAVAILABLE));
            assertThat(forwardClient.owners).isEmpty();
            assertThat(localHead(STRONG_STREAM)).isEqualTo(-1L);
        }
        /// A STRONG stream whose owner is REMOTE is refused on this node, before any forward is attempted.
        @Test
        void publish_refusesStrongStream_beforeForwardingToARemoteOwner() {
            publish(STRONG_STREAM, OWNER).await()
                                         .onSuccessRun(Assertions::fail)
                                         .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.CONSENSUS_PATH_UNAVAILABLE));
            assertThat(forwardClient.owners).isEmpty();
            assertThat(localHead(STRONG_STREAM)).isEqualTo(-1L);
        }

        /// #964: a mode written by a newer node may be STRONG there; it is refused, never appended as EVENTUAL.
        @Test
        void publish_refusesUnreadableConsistencyMode() {
            publish(UNKNOWN_STREAM, SELF).await()
                                         .onSuccessRun(Assertions::fail)
                                         .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.UNREADABLE_CONSISTENCY_MODE));
            assertThat(forwardClient.owners).isEmpty();
            assertThat(localHead(UNKNOWN_STREAM)).isEqualTo(-1L);
        }
    }

    @Nested
    class StreamPublisherPath extends Contract {
        @Override
        Fn0<Promise<Unit>> entryPoint(String stream, NodeId hrwOwner) {
            var publisher = publisher(stream, hrwOwner, Option.some(SELF));

            return () -> publisher.publish("e0".getBytes());
        }
    }

    @Nested
    class StreamAccessPath extends Contract {
        @Override
        Fn0<Promise<Unit>> entryPoint(String stream, NodeId hrwOwner) {
            var access = access(stream, hrwOwner, SELF);

            return () -> access.publish("e0".getBytes())
                               .mapToUnit();
        }
    }

    @Nested
    class ManagementPath extends Contract {
        @Override
        Fn0<Promise<Unit>> entryPoint(String stream, NodeId hrwOwner) {
            var router = StreamWriteRouter.streamWriteRouter(partitionManager,
                                                             Option.some(forwardClient),
                                                             SELF,
                                                             (_, _) -> Option.some(hrwOwner));

            return () -> router.publish(stream, PARTITION, "e0".getBytes(), 1L)
                               .mapToUnit();
        }
    }

    /// An entry point that does not know its own identity cannot establish that the HRW owner is another
    /// node, so it never forwards on the routing arm — it appends locally and the committed-owner admission
    /// decides. (The committed-owner REDIRECT arm is different: the refusal itself names the owner as another
    /// node, so it forwards whatever this node knows about itself — see `OwnerAuthorizedWritesTest`.)
    @Nested
    class UnknownSelf {
        @BeforeEach
        void setUp() {
            partitionManager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> Result.unitResult(), recordingReplication());
            partitionManager.createStream(config(STREAM, ConsistencyMode.EVENTUAL)).onFailureRun(Assertions::fail);
            forwardClient = new RecordingForwardClient();
        }

        @AfterEach
        void tearDown() {
            partitionManager.close();
        }

        @Test
        void streamPublisher_neverForwards_whenSelfIsUnknown() {
            publisher(STREAM, OWNER, Option.none()).publish("e0".getBytes()).await().onFailureRun(Assertions::fail);

            assertUnforwardedLocalAppend();
        }

        @Test
        void streamAccess_neverForwards_whenSelfIsTheNoSelfSentinel() {
            access(STREAM, OWNER, new NodeId("__no_self__")).publish("e0".getBytes()).await().onFailureRun(Assertions::fail);

            assertUnforwardedLocalAppend();
        }

        @Test
        void managementPublish_neverForwards_whenSelfIsUnknown() {
            StreamWriteRouter.streamWriteRouter(partitionManager,
                                                Option.some(forwardClient),
                                                Option.<NodeId> none(),
                                                (_, _) -> Option.some(OWNER))
                             .publish(STREAM, PARTITION, "e0".getBytes(), 1L)
                             .await()
                             .onFailureRun(Assertions::fail);

            assertUnforwardedLocalAppend();
        }

        private void assertUnforwardedLocalAppend() {
            assertThat(forwardClient.owners).isEmpty();
            assertThat(localHead(STREAM)).isZero();
        }
    }

    private DefaultStreamPublisher<byte[]> publisher(String stream, NodeId hrwOwner, Option<NodeId> self) {
        Function<Integer, Option<NodeId>> ownerResolver = _ -> Option.some(hrwOwner);
        var mode = declaredMode(stream);

        return DefaultStreamPublisher.streamPublisher(partitionManager,
                                                      identitySerializer(),
                                                      stream,
                                                      1,
                                                      Option.<Function<byte[], Object>> none(),
                                                      mode,
                                                      Option.none(),
                                                      Option.some(forwardClient),
                                                      Option.<Fn0<Option<NodeId>>> none(),
                                                      Option.some(ownerResolver),
                                                      self);
    }

    private static ConsistencyMode declaredMode(String stream) {
        return switch (stream) {
            case STRONG_STREAM -> ConsistencyMode.STRONG;
            case UNKNOWN_STREAM -> ConsistencyMode.UNKNOWN;
            default -> ConsistencyMode.EVENTUAL;
        };
    }

    private PartitionedStreamAccess<byte[]> access(String stream, NodeId hrwOwner, NodeId self) {
        Function<Integer, Option<NodeId>> ownerResolver = _ -> Option.some(hrwOwner);

        return PartitionedStreamAccess.<byte[]> streamAccess(partitionManager,
                                                             identitySerializer(),
                                                             identityDeserializer(),
                                                             stream,
                                                             1,
                                                             Option.<Function<byte[], Object>> none(),
                                                             Option.some(forwardClient),
                                                             self,
                                                             Option.<Fn0<Option<NodeId>>> none(),
                                                             Option.some(ownerResolver));
    }

    private long localHead(String stream) {
        return partitionManager.partitionBuffer(stream, PARTITION)
                               .map(OffHeapRingBuffer::headOffset)
                               .or(Long.MIN_VALUE);
    }

    private static StreamConfig config(String name, ConsistencyMode mode) {
        return config(name, mode, DECLARED_MIN_SYNC);
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    private static StreamConfig config(String name, ConsistencyMode mode, int minSyncReplicas) {
        return StreamConfig.streamConfig(name,
                                         1,
                                         RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                         "earliest",
                                         1_048_576L,
                                         mode,
                                         3,
                                         minSyncReplicas,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    /// Replication stand-in that records every barrier request and satisfies it at once, so a test can see
    /// which `minAcks` each entry point asked for.
    private ReplicationManager recordingReplication() {
        return new ReplicationManager() {
            @Override
            public void replicateEvent(String streamName,
                                       int partition,
                                       long offset,
                                       byte[] payload,
                                       long timestamp,
                                       Epoch ownerEpoch) {}

            @Override
            public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override
            public ReplicaRegistry registry() {
                return ReplicationManager.NONE.registry();
            }

            @Override
            public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
                awaitedMinAcks.add(minAcks);

                return Promise.unitPromise();
            }
        };
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

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(byte[] bytes) {
                return (T) bytes;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];

                byteBuf.readBytes(bytes);

                return (T) bytes;
            }
        };
    }

    /// Records every publish-forward target and answers with a fixed owner-assigned offset.
    private static final class RecordingForwardClient implements StreamForwardClient {
        private final List<NodeId> owners = new ArrayList<>();

        @Override
        public Promise<Long> publishRemote(NodeId governorId,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp) {
            owners.add(governorId);

            return Promise.success(FORWARDED_OFFSET);
        }

        @Override
        public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                     String streamName,
                                                     int partition,
                                                     long fromOffset,
                                                     int maxEvents) {
            return Promise.success(ReadForwardResult.readForwardResult(List.of(), false));
        }

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override
        @SuppressWarnings("JBCT-RET-01")
        public void onReadForwardResponse(ReadForwardResponse response) {}
    }
}
