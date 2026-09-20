// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationError;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.topic.DurableTopicPublisher;
import org.pragmatica.aether.stream.topic.TopicEventEnvelope;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward.publishForward;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;


/// #1236: a publish reported as a FAILURE must not be in the log. Before the fix every owner-side
/// publish path appended to the ring, fsynced the WAL and fired replication, and only THEN discovered
/// that the replica set could not meet `min-sync` — so `NOT_ENOUGH_REPLICAS` came back for an event
/// local consumers could already read. Every assertion here reads the LOG (ring head offset, and the
/// WAL through an independent reader), never only the returned cause: a cause-only test passed
/// against the defect for as long as it existed.
///
/// The replica set is the owner alone (`min-sync = 2` needs one non-self peer, zero exist), which is
/// exactly the shape a single unavailable replica produces for a durable topic, where
/// `min-sync == replicas` is parse-enforced.
class HonestPublishOutcomeTest {
    private static final NodeId SELF = new NodeId("owner-1");
    private static final NodeId SENDER = new NodeId("sender-1");
    private static final NodeId PEER = new NodeId("peer-1");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int MIN_SYNC = 2;
    private static final long NO_OFFSET = -1L;

    private static final Serializer TO_STRING_BYTES = new Serializer() {
        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            byteBuf.writeBytes(String.valueOf(object).getBytes(UTF_8));
        }
    };

    private static final Deserializer UNUSED_DESERIALIZER = new Deserializer() {
        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("publish-only test");
        }
    };

    @TempDir
    Path walDir;

    @Nested
    class PreAppendReplicaFloor {
        /// The ticket's acceptance test, on the durable topic's own publish path
        /// (`DurableTopicPublisher` over the `DefaultStreamPublisher` that `assemblePublisher` builds).
        @Test
        void durablePublish_failsNotEnoughReplicas_andNothingReachesRingOrWal_whenNoPeerExists() {
            var manager = walBackedManager(ownerOnlyReplication());

            try {
                createStream(manager);
                var publisher = new DurableTopicPublisher<String>(TO_STRING_BYTES, envelopePublisher(manager));

                var result = publisher.publish("order-1").await();

                // Soft, so a regression reports the cause AND both halves of the log, rather than stopping
                // at the first mismatch — the WAL half is otherwise never shown able to fail.
                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(result.isFailure()).isTrue();
                    result.onFailure(cause -> softly.assertThat(cause).isEqualTo(ReplicationError.General.NOT_ENOUGH_REPLICAS));
                    softly.assertThat(ringHead(manager)).as("a failed publish must not be readable from the ring")
                                                        .isEqualTo(NO_OFFSET);
                    softly.assertThat(walLastOffset()).as("a failed publish must not be in the WAL")
                                                      .isEqualTo(NO_OFFSET);
                });
            } finally {
                manager.close();
            }
        }

        /// The owner side of a write-forwarded publish (`StreamForwardHandler.onPublishForward`).
        @Test
        void forwardedPublish_repliesFailure_andNothingReachesRing_whenNoPeerExists() {
            var manager = ringOnlyManager(ownerOnlyReplication());
            var responses = new CopyOnWriteArrayList<StreamForwardMessage>();

            try {
                createStream(manager);
                var handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                                        manager,
                                                                        (_, message) -> responses.add(message));

                handler.onPublishForward(publishForward(SENDER, "c-1", STREAM, PARTITION, payload(), 1000L));

                assertThat(responses).hasSize(1);
                assertThat(responses.getFirst()).isInstanceOfSatisfying(PublishForwardResponse.class,
                                                                          HonestPublishOutcomeTest::assertCleanFailure);
                assertThat(ringHead(manager)).isEqualTo(NO_OFFSET);
            } finally {
                manager.close();
            }
        }

        /// The typed `StreamAccess` publish path (`PartitionedStreamAccess`), owner-local arm.
        @Test
        void streamAccessPublish_failsNotEnoughReplicas_andNothingReachesRing_whenNoPeerExists() {
            var manager = ringOnlyManager(ownerOnlyReplication());

            try {
                createStream(manager);
                var access = PartitionedStreamAccess.<String>streamAccess(manager,
                                                                          TO_STRING_BYTES,
                                                                          UNUSED_DESERIALIZER,
                                                                          STREAM,
                                                                          1,
                                                                          Option.none(),
                                                                          Option.none(),
                                                                          SELF,
                                                                          Option.none(),
                                                                          Option.none(),
                                                                          MIN_SYNC);

                var result = access.publish("order-1").await();

                result.onSuccess(_ -> fail("publish must fail below the replica floor"))
                      .onFailure(cause -> assertThat(cause).isEqualTo(ReplicationError.General.NOT_ENOUGH_REPLICAS));
                assertThat(ringHead(manager)).isEqualTo(NO_OFFSET);
            } finally {
                manager.close();
            }
        }

        /// The management/API write path (`StreamWriteRouter`), owner-local arm.
        @Test
        void writeRouterPublish_failsNotEnoughReplicas_andNothingReachesRing_whenNoPeerExists() {
            var manager = ringOnlyManager(ownerOnlyReplication());

            try {
                createStream(manager);
                var router = StreamWriteRouter.localOnly(manager);

                var result = router.publish(STREAM, PARTITION, payload(), 1000L).await();

                result.onSuccess(_ -> fail("publish must fail below the replica floor"))
                      .onFailure(cause -> assertThat(cause).isEqualTo(ReplicationError.General.NOT_ENOUGH_REPLICAS));
                assertThat(ringHead(manager)).isEqualTo(NO_OFFSET);
            } finally {
                manager.close();
            }
        }
    }

    /// After the append nothing can be taken back, so a barrier that does not confirm must be reported
    /// #1290 review M2: the pre-append floor is `min-sync - 1` PEERS — the owner counts itself. With exactly
    /// one in-sync peer and `min-sync = 2`, every owner-local site must ADMIT the publish; a floor of
    /// `min-sync` would refuse it (one target < two) although the barrier is satisfiable. RED under that
    /// off-by-one at each site. The peer's watermark already covers offset 0, so the post-append barrier
    /// resolves from the registry (#262.3) without a transport.
    @Nested
    class ReplicaFloorBoundary {
        @Test
        void durablePublish_succeeds_withExactlyMinSyncMinusOnePeers() {
            var manager = ringOnlyManager(ownerPlusOnePeerReplication());

            try {
                createStream(manager);
                var publisher = new DurableTopicPublisher<String>(TO_STRING_BYTES, envelopePublisher(manager));

                publisher.publish("order-1")
                         .await()
                         .onFailure(cause -> fail("one in-sync peer meets a min-sync-2 floor: " + cause.message()));
                assertThat(ringHead(manager)).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }

        @Test
        void streamAccessPublish_succeeds_withExactlyMinSyncMinusOnePeers() {
            var manager = ringOnlyManager(ownerPlusOnePeerReplication());

            try {
                createStream(manager);
                var access = PartitionedStreamAccess.<String>streamAccess(manager,
                                                                          TO_STRING_BYTES,
                                                                          UNUSED_DESERIALIZER,
                                                                          STREAM,
                                                                          1,
                                                                          Option.none(),
                                                                          Option.none(),
                                                                          SELF,
                                                                          Option.none(),
                                                                          Option.none(),
                                                                          MIN_SYNC);

                access.publish("order-1")
                      .await()
                      .onFailure(cause -> fail("one in-sync peer meets a min-sync-2 floor: " + cause.message()))
                      .onSuccess(offset -> assertThat(offset).isEqualTo(0L));
                assertThat(ringHead(manager)).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }

        @Test
        void writeRouterPublish_succeeds_withExactlyMinSyncMinusOnePeers() {
            var manager = ringOnlyManager(ownerPlusOnePeerReplication());

            try {
                createStream(manager);

                StreamWriteRouter.localOnly(manager)
                                 .publish(STREAM, PARTITION, payload(), 1000L)
                                 .await()
                                 .onFailure(cause -> fail("one in-sync peer meets a min-sync-2 floor: " + cause.message()))
                                 .onSuccess(offset -> assertThat(offset).isEqualTo(0L));
                assertThat(ringHead(manager)).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }
    }

    /// as an UNKNOWN outcome — and the log assertions prove why: the event is there.
    @Nested
    class PostAppendBarrier {
        @Test
        void durablePublish_reportsOutcomeUnknown_andEventIsInRing_whenAcksTimeOut() {
            var manager = ringOnlyManager(timingOutReplication());

            try {
                createStream(manager);
                var publisher = new DurableTopicPublisher<String>(TO_STRING_BYTES, envelopePublisher(manager));

                var result = publisher.publish("order-1").await();

                result.onSuccess(_ -> fail("an unconfirmed floor must not report success"))
                      .onFailure(HonestPublishOutcomeTest::assertOutcomeUnknownFromTimeout);
                assertThat(ringHead(manager)).as("the event IS in the log; calling it a failure was the defect")
                                             .isEqualTo(0L);
            } finally {
                manager.close();
            }
        }

        /// The owner of a forwarded publish tells the sender the outcome is unknown, so the sender's
        /// client can surface [PublishOutcomeUnknown] instead of a permanent failure.
        @Test
        void forwardedPublish_repliesOutcomeUnknown_andEventIsInRing_whenAcksTimeOut() {
            var manager = ringOnlyManager(timingOutReplication());
            var responses = new CopyOnWriteArrayList<StreamForwardMessage>();

            try {
                createStream(manager);
                var handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                                        manager,
                                                                        (_, message) -> responses.add(message));

                handler.onPublishForward(publishForward(SENDER, "c-1", STREAM, PARTITION, payload(), 1000L));

                assertThat(responses).hasSize(1);
                assertThat(responses.getFirst()).isInstanceOfSatisfying(PublishForwardResponse.class,
                                                                          HonestPublishOutcomeTest::assertOutcomeUnknownResponse);
                assertThat(ringHead(manager)).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }

        @Test
        void streamAccessPublish_reportsOutcomeUnknown_andEventIsInRing_whenAcksTimeOut() {
            var manager = ringOnlyManager(timingOutReplication());

            try {
                createStream(manager);
                var access = PartitionedStreamAccess.<String>streamAccess(manager,
                                                                          TO_STRING_BYTES,
                                                                          UNUSED_DESERIALIZER,
                                                                          STREAM,
                                                                          1,
                                                                          Option.none(),
                                                                          Option.none(),
                                                                          SELF,
                                                                          Option.none(),
                                                                          Option.none(),
                                                                          MIN_SYNC);

                access.publish("order-1")
                      .await()
                      .onSuccess(_ -> fail("an unconfirmed floor must not report success"))
                      .onFailure(HonestPublishOutcomeTest::assertOutcomeUnknownFromTimeout);
                assertThat(ringHead(manager)).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }

        @Test
        void writeRouterPublish_reportsOutcomeUnknown_andEventIsInRing_whenAcksTimeOut() {
            var manager = ringOnlyManager(timingOutReplication());

            try {
                createStream(manager);

                StreamWriteRouter.localOnly(manager)
                                 .publish(STREAM, PARTITION, payload(), 1000L)
                                 .await()
                                 .onSuccess(_ -> fail("an unconfirmed floor must not report success"))
                                 .onFailure(HonestPublishOutcomeTest::assertOutcomeUnknownFromTimeout);
                assertThat(ringHead(manager)).isEqualTo(0L);
            } finally {
                manager.close();
            }
        }
    }

    // === fixtures ===

    private static void assertCleanFailure(PublishForwardResponse response) {
        assertThat(response.success()).isFalse();
        assertThat(response.outcomeUnknown()).as("a pre-append refusal is a clean failure").isFalse();
    }

    private static void assertOutcomeUnknownResponse(PublishForwardResponse response) {
        assertThat(response.success()).isFalse();
        assertThat(response.retryable()).isFalse();
        assertThat(response.outcomeUnknown()).as("a post-append barrier failure is an unknown outcome").isTrue();
    }

    private static void assertOutcomeUnknownFromTimeout(Cause cause) {
        assertThat(cause).isInstanceOfSatisfying(PublishOutcomeUnknown.class,
                                                 unknown -> assertThat(unknown.origin()).isEqualTo(ReplicationError.General.REPLICATION_TIMEOUT));
    }

    /// A replication manager whose floor check admits the publish (the default) and whose post-append
    /// barrier times out at once — the 5 s ack timeout's verdict without the 5 s wait.
    private static ReplicationManager timingOutReplication() {
        return new ReplicationManager() {
            @Contract
            @Override
            public void replicateEvent(String streamName,
                                       int partition,
                                       long offset,
                                       byte[] payload,
                                       long timestamp,
                                       Epoch ownerEpoch) {}

            @Contract
            @Override
            public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override
            public ReplicaRegistry registry() {
                return replicaRegistry();
            }

            @Override
            public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
                return ReplicationError.General.REPLICATION_TIMEOUT.promise();
            }
        };
    }

    /// The real replication manager over a registry holding the owner alone: after self-exclusion the
    /// partition has ZERO replication targets, so any `min-sync >= 2` floor is unmeetable.
    private static ReplicationManager ownerOnlyReplication() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);

        return replicationManager(SELF, registry, (_, _) -> {});
    }

    /// The real replication manager over a registry holding the owner and ONE peer whose confirmed
    /// offset already covers the first append: a `min-sync = 2` floor is met exactly, with no slack.
    private static ReplicationManager ownerPlusOnePeerReplication() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        registry.updateWatermark(STREAM, PARTITION, PEER, 0L);

        return replicationManager(SELF, registry, (_, _) -> {});
    }

    private static StreamPartitionManager ringOnlyManager(ReplicationManager replication) {
        return streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
    }

    /// The only public factory that wires BOTH a replication manager and a WAL is the fence-enabled
    /// one, which needs a cluster node for the config commit and a high-water for the fence. Both are
    /// inert here: the commit is accepted and discarded, and an empty high-water admits the zero epoch.
    private StreamPartitionManager walBackedManager(ReplicationManager replication) {
        return streamPartitionManager(Long.MAX_VALUE,
                                      EvictionListener.NOOP,
                                      replication,
                                      new AcceptingClusterNode(),
                                      OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore()),
                                      StreamOwnerEpochSource.zero(),
                                      Option.some(walDir),
                                      LastSealedOffsetSource.none(),
                                      DurableSealedOffsetSource.none());
    }

    private static void createStream(StreamPartitionManager manager) {
        manager.createStream(minSyncConfig())
               .onFailure(cause -> fail(cause.message()));
    }

    private static StreamConfig minSyncConfig() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         StreamConfig.DEFAULT.maxEventSizeBytes(),
                                         ConsistencyMode.EVENTUAL,
                                         MIN_SYNC,
                                         MIN_SYNC,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static DefaultStreamPublisher<TopicEventEnvelope> envelopePublisher(StreamPartitionManager manager) {
        return DefaultStreamPublisher.streamPublisher(manager,
                                                      TO_STRING_BYTES,
                                                      STREAM,
                                                      1,
                                                      Option.none(),
                                                      ConsistencyMode.EVENTUAL,
                                                      Option.none(),
                                                      MIN_SYNC);
    }

    private static long ringHead(StreamPartitionManager manager) {
        return manager.partitionBuffer(STREAM, PARTITION)
                      .map(OffHeapRingBuffer::headOffset)
                      .or(() -> {
                          throw new AssertionError("partition ring must be materialized");
                      });
    }

    /// Read the WAL through an INDEPENDENT reader on the same file, as `StreamPartitionManagerWalTest`
    /// does: it sees exactly what reached the file, whatever the manager's own handle believes.
    private long walLastOffset() {
        var reader = PartitionWal.open(walDir.resolve(STREAM).resolve(PARTITION + ".wal")).unwrap();

        try {
            return reader.lastOffset();
        } finally {
            reader.close();
        }
    }

    private static byte[] payload() {
        return "evt".getBytes(UTF_8);
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), TO_STRING_BYTES, UNUSED_DESERIALIZER);
    }

    private static final class AcceptingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        @Override
        public NodeId self() {
            return SELF;
        }

        @Override
        public TopologyManager topologyManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
