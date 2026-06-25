// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardClient.ReadForwardResult;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;


/// #345 item 1e — `LINEARIZABLE` owner-routed reads + the owner-side guards. Drives the real
/// {@link StreamReadRouter} over a real {@link StreamPartitionManager} + {@link ReplicaRegistry}, with a
/// fake {@link CommittedStreamOwnerSource} so the COMMITTED owner can be made to DIVERGE from the HRW
/// owner the other arms forward to, and a real {@link OwnershipEpochHighWater} to drive the epoch fence.
/// Non-linearizable arms are unaffected — they are covered by {@link StreamReadRouterReplicaSnapshotTest}
/// and the forward-read tests.
class LinearizableReadRoutingTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OTHER = new NodeId("other-node");
    private static final String STREAM = "lin-stream";
    private static final int PARTITION = 0;

    private StreamPartitionManager partitionManager;
    private ReplicaRegistry replicaRegistry;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
        replicaRegistry = ReplicaRegistry.replicaRegistry();
    }

    @Nested
    class RoutesToCommittedOwner {
        /// LINEARIZABLE routes by the COMMITTED owner, NOT the HRW owner. The HRW owner resolver names
        /// OTHER, but the committed owner is SELF — and self serves its own data once CAUGHT_UP, proving
        /// the read followed the committed owner, not the (diverging) HRW owner.
        @Test
        void route_servesLocal_whenCommittedOwnerIsSelf_evenIfHrwOwnerDiffers() {
            publish("e0", "e1");
            markSelfCaughtUp(1L);

            var router = router(hrwOwner(OTHER), committedOwner(SELF, Epoch.ZERO), noHighWater());

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onFailure(LinearizableReadRoutingTest::failUnexpected)
                  .onSuccess(events -> assertThat(events).hasSize(2));
        }

        /// The committed owner is a REMOTE node: the read is forwarded there (committed owner OTHER) and
        /// the forwarded events are returned, even though the HRW resolver names SELF. Proves routing by
        /// the committed owner across nodes.
        @Test
        void route_forwardsToCommittedRemoteOwner_andReturnsForwardedData() {
            var forwardClient = forwardClientServing(rawEvent(0, "r0"), rawEvent(1, "r1"));
            var router = routerWithForward(hrwOwner(SELF), committedOwner(OTHER, Epoch.ZERO), noHighWater(), forwardClient);

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onFailure(LinearizableReadRoutingTest::failUnexpected)
                  .onSuccess(events -> assertThat(events).hasSize(2));
        }

        /// No committed record (legacy / unowned partition): LINEARIZABLE degrades to the replica-routed
        /// read, which with self CAUGHT_UP reads local — nothing breaks.
        @Test
        void route_fallsBackToReplicaRouted_whenNoCommittedRecord() {
            publish("e0");
            markSelfCaughtUp(0L);

            var router = router(hrwOwner(SELF), noCommittedOwner(), noHighWater());

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onFailure(LinearizableReadRoutingTest::failUnexpected)
                  .onSuccess(events -> assertThat(events).hasSize(1));
        }
    }

    @Nested
    class OwnerSideGuards {
        /// NotCurrentOwner: the committed owner is a REMOTE node and no forward client is wired, so self
        /// cannot serve a linearizable read for an arc it does not own — rejected so the client re-resolves.
        @Test
        void route_rejectsNotCurrentOwner_whenCommittedOwnerIsRemoteAndNoForwardClient() {
            var router = router(hrwOwner(SELF), committedOwner(OTHER, Epoch.ZERO), noHighWater());

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onSuccess(events -> Assertions.fail("expected NotCurrentOwner, got " + events.size() + " events"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.NotCurrentOwner.class));
        }

        /// StaleEpochRead: self IS the committed owner, but the committed ownerEpoch is STRICTLY older than
        /// the partition high-water — self is a deposed owner whose committed record is stale. Mirrors the
        /// write-side StaleEpochAppend fence on the read path.
        @Test
        void route_rejectsStaleEpochRead_whenCommittedEpochBelowHighWater() {
            publish("e0");
            markSelfCaughtUp(0L);
            var highWater = highWaterAt(Epoch.epoch(5, 0));

            var router = router(hrwOwner(SELF), committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater));

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onSuccess(events -> Assertions.fail("expected StaleEpochRead, got " + events.size() + " events"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StaleEpochRead.class));
        }

        /// Equal-or-newer committed epoch is NOT stale — a genuinely-current owner is never spuriously
        /// fenced. Committed epoch equals the high-water; the read serves.
        @Test
        void route_serves_whenCommittedEpochEqualsHighWater() {
            publish("e0");
            markSelfCaughtUp(0L);
            var highWater = highWaterAt(Epoch.epoch(3, 0));

            var router = router(hrwOwner(SELF), committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater));

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onFailure(LinearizableReadRoutingTest::failUnexpected)
                  .onSuccess(events -> assertThat(events).hasSize(1));
        }

        /// Catch-up gate: self is the committed owner but NOT yet CAUGHT_UP for the partition (a lagging
        /// freshly-promoted owner), so serving could miss handover events — rejected OwnerCatchupPending.
        @Test
        void route_rejectsOwnerCatchupPending_whenCommittedOwnerNotCaughtUp() {
            publish("e0");
            replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
            replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, 0L, ReplicationState.SYNCING);

            var router = router(hrwOwner(SELF), committedOwner(SELF, Epoch.ZERO), noHighWater());

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onSuccess(events -> Assertions.fail("expected OwnerCatchupPending, got " + events.size() + " events"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerCatchupPending.class));
        }

        /// Catch-up gate serves once self IS CAUGHT_UP — the gate uses the existing CAUGHT_UP signal the
        /// failover-recovery / backfill path drives.
        @Test
        void route_serves_whenCommittedOwnerCaughtUp() {
            publish("e0");
            markSelfCaughtUp(0L);

            var router = router(hrwOwner(SELF), committedOwner(SELF, Epoch.ZERO), noHighWater());

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onFailure(LinearizableReadRoutingTest::failUnexpected)
                  .onSuccess(events -> assertThat(events).hasSize(1));
        }

        /// An ESTABLISHED / single owner with no self replica entry is its own authority and serves — the
        /// catch-up gate holds back only a registered-but-still-SYNCING fresh takeover, never an owner
        /// with no prior owner to catch up to (the RF=1 / not-yet-reconciled case).
        @Test
        void route_serves_whenCommittedOwnerHasNoReplicaEntry() {
            publish("e0");

            var router = router(hrwOwner(SELF), committedOwner(SELF, Epoch.ZERO), noHighWater());

            router.read(STREAM, PARTITION, 0, 10, ReadPreference.LINEARIZABLE)
                  .await()
                  .onFailure(LinearizableReadRoutingTest::failUnexpected)
                  .onSuccess(events -> assertThat(events).hasSize(1));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void publish(String... payloads) {
        for (var payload : payloads) {
            partitionManager.publishLocal(STREAM, PARTITION, payload.getBytes(), 1L);
        }
    }

    private void markSelfCaughtUp(long confirmedOffset) {
        replicaRegistry.registerReplica(STREAM, PARTITION, SELF);
        replicaRegistry.updateWatermark(STREAM, PARTITION, SELF, confirmedOffset, ReplicationState.CAUGHT_UP);
    }

    private StreamReadRouter router(ForwardingReadRouter.OwnerResolver hrwResolver,
                                    Option<CommittedStreamOwnerSource> committedOwnerSource,
                                    Option<OwnershipEpochHighWater> highWater) {
        return StreamReadRouter.streamReadRouter(partitionManager,
                                                 Option.some(replicaRegistry),
                                                 Option.none(),
                                                 SELF,
                                                 hrwResolver,
                                                 StreamReadForwardMetrics.NOOP,
                                                 committedOwnerSource,
                                                 highWater);
    }

    private StreamReadRouter routerWithForward(ForwardingReadRouter.OwnerResolver hrwResolver,
                                               Option<CommittedStreamOwnerSource> committedOwnerSource,
                                               Option<OwnershipEpochHighWater> highWater,
                                               StreamForwardClient forwardClient) {
        return StreamReadRouter.streamReadRouter(partitionManager,
                                                 Option.some(replicaRegistry),
                                                 Option.some(forwardClient),
                                                 SELF,
                                                 hrwResolver,
                                                 StreamReadForwardMetrics.NOOP,
                                                 committedOwnerSource,
                                                 highWater);
    }

    private static ForwardingReadRouter.OwnerResolver hrwOwner(NodeId owner) {
        return (_, _) -> Option.some(owner);
    }

    private static Option<CommittedStreamOwnerSource> committedOwner(NodeId owner, Epoch epoch) {
        CommittedStreamOwnerSource source = (_, _) -> Option.some(new CommittedOwner(owner, epoch));

        return Option.some(source);
    }

    private static Option<CommittedStreamOwnerSource> noCommittedOwner() {
        return Option.some(CommittedStreamOwnerSource.none());
    }

    private static Option<OwnershipEpochHighWater> noHighWater() {
        return Option.none();
    }

    private static OwnershipEpochHighWater highWaterAt(Epoch epoch) {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());

        highWater.advance(OwnershipDomain.streamPartition(STREAM, PARTITION), epoch);

        return highWater;
    }

    private static OffHeapRingBuffer.RawEvent rawEvent(long offset, String data) {
        return new OffHeapRingBuffer.RawEvent(offset, data.getBytes(), 1L);
    }

    /// A forward client that serves a fixed event list on `readRemote` (the committed remote owner's
    /// response) and is otherwise unused.
    private static StreamForwardClient forwardClientServing(OffHeapRingBuffer.RawEvent... events) {
        var dtos = List.of(events).stream().map(RawEventDto::fromRawEvent).toList();

        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return Promise.success(new ReadForwardResult(dtos, false));
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static void failUnexpected(Cause cause) {
        Assertions.fail("unexpected failure: " + cause.message());
    }
}
