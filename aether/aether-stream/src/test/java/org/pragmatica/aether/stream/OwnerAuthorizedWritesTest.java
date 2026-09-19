// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
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
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1230: holding a partition ring authorizes reads and replication receipt, never an application write.
///
/// Every fixture here materializes the ring FIRST (default always-OWNER placement), then flips this node's
/// placement role to REPLICA — the exact state a replica ring is in on a real node. Before #1230 both write
/// routers tested ring presence, so a replica appended locally and assigned offsets the owner also assigned;
/// and `publishLocal` had no identity check, because the epoch fence compares epochs only and a live replica
/// stamps the same committed epoch the owner does.
class OwnerAuthorizedWritesTest {
    private static final NodeId SELF = new NodeId("replica-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final String STREAM = "owned-stream";
    private static final int PARTITION = 0;
    private static final long FORWARDED_OFFSET = 42L;

    private StreamPartitionManager partitionManager;

    @BeforeEach
    void setUp() {
        partitionManager = streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
        partitionManager.placementRoleSupplier((_, _) -> Role.REPLICA);
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    private long localHead() {
        return partitionManager.partitionBuffer(STREAM, PARTITION)
                               .map(OffHeapRingBuffer::headOffset)
                               .or(Long.MIN_VALUE);
    }

    @Nested
    class AppendBoundary {
        @Test
        void publishLocal_refusedWithNotOwnerAppend_whenCommittedOwnerIsAnotherNode() {
            partitionManager.ownerWriteAdmission((_, _) -> Option.some(OWNER));

            partitionManager.publishLocal(STREAM, PARTITION, "e0".getBytes(), 1L)
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.NotOwnerAppend(STREAM,
                                                                                                           PARTITION,
                                                                                                           OWNER)));
            assertThat(localHead()).isEqualTo(-1L);
        }

        @Test
        void publishLocal_refused_evenWithAnExplicitCurrentEpoch() {
            partitionManager.ownerWriteAdmission((_, _) -> Option.some(OWNER));

            partitionManager.publishLocal(STREAM, PARTITION, "e0".getBytes(), 1L, Epoch.epoch(3L, 1L))
                            .onSuccessRun(Assertions::fail);
            assertThat(localHead()).isEqualTo(-1L);
        }

        @Test
        void publishLocal_admitted_whenNoRemoteCommittedOwner() {
            partitionManager.ownerWriteAdmission((_, _) -> Option.none());

            partitionManager.publishLocal(STREAM, PARTITION, "e0".getBytes(), 1L)
                            .onFailureRun(Assertions::fail)
                            .onSuccess(offset -> assertThat(offset).isZero());
        }

        /// Replication receipt is the one write a replica MUST accept: the committed owner's events land here.
        @Test
        void appendRecovered_stillLands_whenCommittedOwnerIsAnotherNode() {
            partitionManager.ownerWriteAdmission((_, _) -> Option.some(OWNER));

            partitionManager.appendRecovered(STREAM, PARTITION, "e0".getBytes(), 1L)
                            .onFailureRun(Assertions::fail);
            assertThat(localHead()).isZero();
        }
    }

    @Nested
    class AppPublisherRouting {
        @Test
        void publish_forwardsToRemoteOwner_evenThoughAReplicaRingIsMaterialized() {
            var forwardClient = new RecordingForwardClient();

            publisher(forwardClient, OWNER).publish("e0".getBytes())
                                           .await()
                                           .onFailureRun(Assertions::fail);
            assertThat(forwardClient.owners).containsExactly(OWNER);
            assertThat(localHead()).isEqualTo(-1L);
        }

        @Test
        void publish_appendsLocally_whenSelfIsTheOwner() {
            var forwardClient = new RecordingForwardClient();

            publisher(forwardClient, SELF).publish("e0".getBytes())
                                          .await()
                                          .onFailureRun(Assertions::fail);
            assertThat(forwardClient.owners).isEmpty();
            assertThat(localHead()).isZero();
        }

        /// The ownership-lag race: HRW already names this node, the committed record still names the previous
        /// owner. The previous owner is still the fenced single writer, so the refused local append is
        /// redirected there instead of surfacing to the app.
        @Test
        void publish_redirectsToCommittedOwner_whenSelfIsHrwOwnerButCommitIsElsewhere() {
            var forwardClient = new RecordingForwardClient();
            partitionManager.ownerWriteAdmission((_, _) -> Option.some(OWNER));

            publisher(forwardClient, SELF).publish("e0".getBytes())
                                          .await()
                                          .onFailureRun(Assertions::fail);
            assertThat(forwardClient.owners).containsExactly(OWNER);
            assertThat(localHead()).isEqualTo(-1L);
        }
    }

    @Nested
    class ManagementRouting {
        @Test
        void publish_forwardsToRemoteOwner_evenThoughAReplicaRingIsMaterialized() {
            var forwardClient = new RecordingForwardClient();

            StreamWriteRouter.streamWriteRouter(partitionManager, Option.some(forwardClient), SELF, (_, _) -> Option.some(OWNER))
                             .publish(STREAM, PARTITION, "e0".getBytes(), 1L)
                             .await()
                             .onFailureRun(Assertions::fail)
                             .onSuccess(offset -> assertThat(offset).isEqualTo(FORWARDED_OFFSET));
            assertThat(forwardClient.owners).containsExactly(OWNER);
            assertThat(localHead()).isEqualTo(-1L);
        }

        @Test
        void publish_redirectsToCommittedOwner_whenSelfIsHrwOwnerButCommitIsElsewhere() {
            var forwardClient = new RecordingForwardClient();
            partitionManager.ownerWriteAdmission((_, _) -> Option.some(OWNER));

            StreamWriteRouter.streamWriteRouter(partitionManager, Option.some(forwardClient), SELF, (_, _) -> Option.some(SELF))
                             .publish(STREAM, PARTITION, "e0".getBytes(), 1L)
                             .await()
                             .onFailureRun(Assertions::fail)
                             .onSuccess(offset -> assertThat(offset).isEqualTo(FORWARDED_OFFSET));
            assertThat(forwardClient.owners).containsExactly(OWNER);
            assertThat(localHead()).isEqualTo(-1L);
        }
    }

    /// The already-owner-routed access path keeps its routing; only its local arm gains the lag redirect.
    @Nested
    class PartitionedAccessRouting {
        @Test
        void publish_redirectsToCommittedOwner_whenSelfIsHrwOwnerButCommitIsElsewhere() {
            var forwardClient = new RecordingForwardClient();
            Function<Integer, Option<NodeId>> ownerResolver = _ -> Option.some(SELF);
            partitionManager.ownerWriteAdmission((_, _) -> Option.some(OWNER));

            PartitionedStreamAccess.<byte[]> streamAccess(partitionManager,
                                                          identitySerializer(),
                                                          identityDeserializer(),
                                                          STREAM,
                                                          1,
                                                          Option.<Function<byte[], Object>> none(),
                                                          Option.some(forwardClient),
                                                          SELF,
                                                          Option.<Fn0<Option<NodeId>>> none(),
                                                          Option.some(ownerResolver))
                                   .publish("e0".getBytes())
                                   .await()
                                   .onFailureRun(Assertions::fail)
                                   .onSuccess(offset -> assertThat(offset).isEqualTo(FORWARDED_OFFSET));
            assertThat(forwardClient.owners).containsExactly(OWNER);
            assertThat(localHead()).isEqualTo(-1L);
        }
    }

    private DefaultStreamPublisher<byte[]> publisher(StreamForwardClient forwardClient, NodeId hrwOwner) {
        Function<Integer, Option<NodeId>> ownerResolver = _ -> Option.some(hrwOwner);

        return DefaultStreamPublisher.streamPublisher(partitionManager,
                                                      identitySerializer(),
                                                      STREAM,
                                                      1,
                                                      Option.<Function<byte[], Object>> none(),
                                                      ConsistencyMode.EVENTUAL,
                                                      Option.none(),
                                                      Option.some(forwardClient),
                                                      Option.<Fn0<Option<NodeId>>> none(),
                                                      Option.some(ownerResolver),
                                                      Option.some(SELF));
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
