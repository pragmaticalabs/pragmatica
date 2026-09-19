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
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1263: the three write entry points — the slice `StreamPublisher` ({@link DefaultStreamPublisher}),
/// `StreamAccess.publish` ({@link PartitionedStreamAccess}) and the management publish
/// ({@link StreamWriteRouter}) — are ONE owner-routed write operation. Every scenario in [Contract] runs against
/// all three (one `@Nested` subclass per entry point), so a defect in the shared router reddens all three
/// rather than one, and a path that drifts reddens alone.
///
/// Fixture: the partition ring is materialized on this node, then its placement role flips to REPLICA —
/// the state a replica ring is in on a real node (#1230). The stream declares `min-sync-replicas = 2`.
class StreamWritePathContractTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final String STREAM = "contract-stream";
    private static final String STRONG_STREAM = "contract-strong-stream";
    private static final int PARTITION = 0;
    private static final int DECLARED_MIN_SYNC = 2;
    private static final long FORWARDED_OFFSET = 42L;

    private final List<Integer> awaitedMinAcks = new ArrayList<>();
    private StreamPartitionManager partitionManager;
    private RecordingForwardClient forwardClient;

    /// The scenarios every write entry point must satisfy; each `@Nested` subclass supplies one entry point.
    abstract class Contract {
        abstract Promise<Unit> publish(String stream, NodeId hrwOwner);

        @BeforeEach
        void setUp() {
            partitionManager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> {}, recordingReplication());
            partitionManager.createStream(config(STREAM, ConsistencyMode.EVENTUAL)).onFailureRun(Assertions::fail);
            partitionManager.createStream(config(STRONG_STREAM, ConsistencyMode.STRONG)).onFailureRun(Assertions::fail);
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
    }

    @Nested
    class StreamPublisherPath extends Contract {
        @Override
        Promise<Unit> publish(String stream, NodeId hrwOwner) {
            return publisher(stream, hrwOwner).publish("e0".getBytes());
        }
    }

    @Nested
    class StreamAccessPath extends Contract {
        @Override
        Promise<Unit> publish(String stream, NodeId hrwOwner) {
            return access(stream, hrwOwner).publish("e0".getBytes())
                                           .mapToUnit();
        }
    }

    @Nested
    class ManagementPath extends Contract {
        @Override
        Promise<Unit> publish(String stream, NodeId hrwOwner) {
            return StreamWriteRouter.streamWriteRouter(partitionManager,
                                                       Option.some(forwardClient),
                                                       SELF,
                                                       (_, _) -> Option.some(hrwOwner))
                                    .publish(stream, PARTITION, "e0".getBytes(), 1L)
                                    .mapToUnit();
        }
    }

    private DefaultStreamPublisher<byte[]> publisher(String stream, NodeId hrwOwner) {
        Function<Integer, Option<NodeId>> ownerResolver = _ -> Option.some(hrwOwner);
        var mode = stream.equals(STRONG_STREAM)
                   ? ConsistencyMode.STRONG
                   : ConsistencyMode.EVENTUAL;

        return DefaultStreamPublisher.streamPublisher(partitionManager,
                                                      identitySerializer(),
                                                      stream,
                                                      1,
                                                      Option.<Function<byte[], Object>> none(),
                                                      mode,
                                                      Option.none(),
                                                      0,
                                                      Option.some(forwardClient),
                                                      Option.<Fn0<Option<NodeId>>> none(),
                                                      Option.some(ownerResolver),
                                                      Option.some(SELF));
    }

    private PartitionedStreamAccess<byte[]> access(String stream, NodeId hrwOwner) {
        Function<Integer, Option<NodeId>> ownerResolver = _ -> Option.some(hrwOwner);

        return PartitionedStreamAccess.<byte[]> streamAccess(partitionManager,
                                                             identitySerializer(),
                                                             identityDeserializer(),
                                                             stream,
                                                             1,
                                                             Option.<Function<byte[], Object>> none(),
                                                             Option.some(forwardClient),
                                                             SELF,
                                                             Option.<Fn0<Option<NodeId>>> none(),
                                                             Option.some(ownerResolver),
                                                             0);
    }

    private long localHead(String stream) {
        return partitionManager.partitionBuffer(stream, PARTITION)
                               .map(OffHeapRingBuffer::headOffset)
                               .or(Long.MIN_VALUE);
    }

    private static StreamConfig config(String name, ConsistencyMode mode) {
        return StreamConfig.streamConfig(name,
                                         1,
                                         RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                         "earliest",
                                         1_048_576L,
                                         mode,
                                         3,
                                         DECLARED_MIN_SYNC,
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
