// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.StreamConfig.streamConfig;


/// #345 item 1e-c — the TYPED {@link PartitionedStreamAccess} read path now runs the SAME committed-owner
/// `LINEARIZABLE` pipeline as the raw {@link StreamReadRouter} (proven by {@link LinearizableReadRoutingTest}).
/// These mirror the raw-path shapes at the typed layer: drive `PartitionedStreamAccess.fetch` on an access
/// constructed with `ReadPreference.LINEARIZABLE` + the three threaded components (committed-owner source,
/// ownership epoch high-water, no-op-round barrier), a real {@link StreamPartitionManager} +
/// {@link ReplicaRegistry}, and a fake {@link CommittedStreamOwnerSource} so the COMMITTED owner can be made
/// to DIVERGE from the HRW owner the other arms resolve. Confirms typed LINEARIZABLE reads no longer degrade.
class LinearizableTypedReadRoutingTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OTHER = new NodeId("other-node");
    private static final String STREAM = "lin-typed-stream";
    private static final int PARTITION = 0;

    private StreamPartitionManager partitionManager;
    private ReplicaRegistry replicaRegistry;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        partitionManager.createStream(streamConfig(STREAM));
        replicaRegistry = ReplicaRegistry.replicaRegistry();
    }

    /// LINEARIZABLE routes by the COMMITTED owner, NOT the HRW owner: the HRW resolver names OTHER, the
    /// committed owner is SELF, and self serves its own two events once CAUGHT_UP — proving the typed read
    /// followed the committed owner, not the (diverging) HRW owner.
    @Test
    void fetch_servesLocal_whenCommittedOwnerIsSelf_evenIfHrwOwnerDiffers() {
        publish("e0", "e1");
        markSelfCaughtUp(1L);

        var access = linearizableAccess(hrwOwner(OTHER), committedOwner(SELF, Epoch.ZERO), noHighWater(), Option.none());

        access.fetch(PARTITION, 0, 10)
              .await()
              .onFailure(LinearizableTypedReadRoutingTest::failUnexpected)
              .onSuccess(events -> assertThat(events).hasSize(2));
    }

    /// StaleEpochRead: self IS the committed owner, but the committed ownerEpoch is STRICTLY older than the
    /// partition high-water — a deposed owner whose committed record is stale is rejected on the typed path
    /// exactly as on the raw path.
    @Test
    void fetch_rejectsStaleEpochRead_whenCommittedEpochBelowHighWater() {
        publish("e0");
        markSelfCaughtUp(0L);
        var highWater = highWaterAt(Epoch.epoch(5, 0));

        var access = linearizableAccess(hrwOwner(SELF), committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater), Option.none());

        access.fetch(PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected StaleEpochRead, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StaleEpochRead.class));
    }

    /// The no-op consensus round runs at the committed owner before a typed LINEARIZABLE serve — the
    /// injected recording barrier is invoked exactly once, and the read still serves its event.
    @Test
    void fetch_issuesRound_forLinearizable() {
        publish("e0");
        markSelfCaughtUp(0L);
        var roundCount = new AtomicInteger();

        var access = linearizableAccess(hrwOwner(SELF), committedOwner(SELF, Epoch.ZERO), noHighWater(), Option.some(countingBarrier(roundCount)));

        access.fetch(PARTITION, 0, 10)
              .await()
              .onFailure(LinearizableTypedReadRoutingTest::failUnexpected)
              .onSuccess(events -> assertThat(events).hasSize(1));
        assertThat(roundCount.get()).isEqualTo(1);
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

    private PartitionedStreamAccess<byte[]> linearizableAccess(Option<Fn0<Option<NodeId>>> hrwResolver,
                                                               Option<CommittedStreamOwnerSource> committedOwnerSource,
                                                               Option<OwnershipEpochHighWater> highWater,
                                                               Option<LinearizableBarrier> barrier) {
        return PartitionedStreamAccess.streamAccess(partitionManager,
                                                    identitySerializer(),
                                                    identityDeserializer(),
                                                    STREAM,
                                                    1,
                                                    Option.none(),
                                                    Option.none(),
                                                    SELF,
                                                    hrwResolver,
                                                    Option.none(),
                                                    0,
                                                    Option.none(),
                                                    Option.none(),
                                                    Option.some(replicaRegistry),
                                                    ReadPreference.LINEARIZABLE,
                                                    committedOwnerSource,
                                                    highWater,
                                                    barrier);
    }

    private static Option<Fn0<Option<NodeId>>> hrwOwner(NodeId owner) {
        return Option.some(() -> Option.some(owner));
    }

    private static Option<CommittedStreamOwnerSource> committedOwner(NodeId owner, Epoch epoch) {
        CommittedStreamOwnerSource source = (_, _) -> Option.some(new CommittedOwner(owner, epoch));

        return Option.some(source);
    }

    private static Option<OwnershipEpochHighWater> noHighWater() {
        return Option.none();
    }

    private static OwnershipEpochHighWater highWaterAt(Epoch epoch) {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());

        highWater.advance(OwnershipDomain.streamPartition(STREAM, PARTITION), epoch);

        return highWater;
    }

    private static LinearizableBarrier countingBarrier(AtomicInteger counter) {
        return (_, _) -> countThenSucceed(counter);
    }

    private static Promise<Unit> countThenSucceed(AtomicInteger counter) {
        counter.incrementAndGet();

        return Promise.success(Unit.unit());
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

    private static void failUnexpected(Cause cause) {
        Assertions.fail("unexpected failure: " + cause.message());
    }
}
