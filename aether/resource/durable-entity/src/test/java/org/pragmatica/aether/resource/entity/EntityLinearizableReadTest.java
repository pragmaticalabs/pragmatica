// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #345 item 1e-b — per-call [ReadConsistency] on the [DurableEntity] surface + the entity-native
/// `LINEARIZABLE` owner-serve pipeline ([LinearizableEntityServe]). Drives a real linearizable-capable
/// [InMemoryDurableEntity] with a fake [CommittedPartitionOwnerSource] (so the committed owner can be made
/// to be SELF or a remote node, or absent), a real [OwnershipEpochHighWater] driving the epoch fence, and
/// a fake [EntityLinearizableBarrier] modelling the no-op consensus round. The entity-native analog of
/// the stream's `LinearizableReadRoutingTest`; the [ReadConsistency#BOUNDED_STALE] behaviour is unchanged
/// and covered by [DurableEntityTest].
class EntityLinearizableReadTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OTHER = new NodeId("other-node");
    private static final String KEYSPACE = "orders";
    private static final int PARTITION_COUNT = 1;
    private static final int PARTITION = 0;
    private static final String KEY = "k1";
    private static final TimeSpan AWAIT = timeSpan(5).seconds();
    private static final EntityPartitionArc ARC = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITION_COUNT);
    private static final OwnershipDomain DOMAIN = OwnershipDomain.streamPartition(KEYSPACE, PARTITION);

    @Nested
    class RoutesToCommittedOwner {
        /// LINEARIZABLE at the committed owner (SELF) serves the local value once the owner-side guards
        /// pass.
        @Test
        void get_servesLocal_whenSelfIsCommittedOwner() {
            seeded(committedOwner(SELF, Epoch.ZERO), noHighWater(), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onFailure(EntityLinearizableReadTest::failCause)
                    .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }

        /// A REMOTE committed owner and no entity read-forward transport (1e-b): the read is rejected
        /// NotCurrentOwner so the caller re-resolves the owner, never served from a stale local copy.
        @Test
        void get_rejectsNotCurrentOwner_whenCommittedOwnerIsRemote() {
            seeded(committedOwner(OTHER, Epoch.ZERO), noHighWater(), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected NotCurrentOwner, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.NotCurrentOwner.class));
        }

        /// No committed record (legacy / unowned arc): LINEARIZABLE degrades to the local read.
        @Test
        void get_fallsBackToLocal_whenNoCommittedRecord() {
            seeded(CommittedPartitionOwnerSource.none(), noHighWater(), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onFailure(EntityLinearizableReadTest::failCause)
                    .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }
    }

    @Nested
    class OwnerSideEpochFence {
        /// StaleEpochRead: self IS the committed owner, but the committed ownerEpoch is STRICTLY older than
        /// the arc high-water — self is a deposed owner whose committed record is stale.
        @Test
        void get_rejectsStaleEpochRead_whenCommittedEpochBelowHighWater() {
            var highWater = highWaterAt(Epoch.epoch(5, 0));

            seeded(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected StaleEpochRead, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.StaleEpochRead.class));
        }

        /// Equal committed epoch is NOT stale — a genuinely-current owner is never spuriously fenced.
        @Test
        void get_serves_whenCommittedEpochEqualsHighWater() {
            var highWater = highWaterAt(Epoch.epoch(3, 0));

            seeded(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onFailure(EntityLinearizableReadTest::failCause)
                    .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }
    }

    @Nested
    class NoOpRound {
        /// The owner's deposal is committed-but-not-yet-applied when the read starts (pre-round committed
        /// epoch 3 is NOT below the pre-round high-water 3, so it would serve). The no-op round applies the
        /// deposal — modelled by the barrier advancing the high-water to 9 — so the POST-round fence
        /// observes it and rejects StaleEpochRead. This is the window the round closes.
        @Test
        void get_rejectsStaleEpochRead_whenRoundObservesDeposalDuringRead() {
            var highWater = highWaterAt(Epoch.epoch(3, 0));
            var barrier = advancingBarrier(highWater, Epoch.epoch(9, 0));

            seeded(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater), Option.some(barrier))
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected StaleEpochRead after the round observed the deposal, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.StaleEpochRead.class));
        }

        /// Control: an owner still current after the round serves — the round is not a blanket reject, and
        /// it ran exactly once.
        @Test
        void get_serves_whenRoundObservesNoDeposal() {
            var roundCount = new AtomicInteger();

            seeded(committedOwner(SELF, Epoch.ZERO), noHighWater(), Option.some(countingBarrier(roundCount)))
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onFailure(EntityLinearizableReadTest::failCause)
                    .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
            assertThat(roundCount.get()).isEqualTo(1);
        }

        /// The round runs ONLY for LINEARIZABLE reads. The no-arg and BOUNDED_STALE reads never touch the
        /// barrier; the single LINEARIZABLE read issues exactly one round.
        @Test
        void get_issuesRoundOnlyForLinearizable() {
            var roundCount = new AtomicInteger();
            var entity = seeded(committedOwner(SELF, Epoch.ZERO), noHighWater(), Option.some(countingBarrier(roundCount)));

            entity.get(KEY).await(AWAIT);
            entity.get(KEY, ReadConsistency.BOUNDED_STALE).await(AWAIT);
            assertThat(roundCount.get()).isZero();

            entity.get(KEY, ReadConsistency.LINEARIZABLE).await(AWAIT);
            assertThat(roundCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class DefaultSurface {
        /// The default (un-wired) factory product serves BOUNDED_STALE identically to the no-arg get.
        @Test
        void get_withBoundedStale_matchesNoArgGet() {
            var entity = seededPlain();

            entity.get(KEY, ReadConsistency.BOUNDED_STALE)
                  .await(AWAIT)
                  .onFailure(EntityLinearizableReadTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }

        /// With no owner-serve wiring (default factory product / single-owner cut) a LINEARIZABLE read
        /// degrades to the local read, which on a single owner already reflects every acknowledged write.
        @Test
        void get_linearizable_degradesToLocal_whenUnwired() {
            var entity = seededPlain();

            entity.get(KEY, ReadConsistency.LINEARIZABLE)
                  .await(AWAIT)
                  .onFailure(EntityLinearizableReadTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static DurableEntity<String, Integer> seeded(CommittedPartitionOwnerSource committedOwnerSource,
                                                         Option<OwnershipEpochHighWater> highWater,
                                                         Option<EntityLinearizableBarrier> barrier) {
        var entity = InMemoryDurableEntity.<String, Integer> inMemoryDurableEntity(SELF,
                                                                                   ARC,
                                                                                   committedOwnerSource,
                                                                                   highWater,
                                                                                   barrier);

        entity.create(KEY, 7).await(AWAIT).onFailure(EntityLinearizableReadTest::failCause);

        return entity;
    }

    private static DurableEntity<String, Integer> seededPlain() {
        var entity = InMemoryDurableEntity.<String, Integer> inMemoryDurableEntity();

        entity.create(KEY, 7).await(AWAIT).onFailure(EntityLinearizableReadTest::failCause);

        return entity;
    }

    private static CommittedPartitionOwnerSource committedOwner(NodeId owner, Epoch epoch) {
        return (_, _) -> Option.some(new CommittedOwner(owner, epoch));
    }

    private static Option<OwnershipEpochHighWater> noHighWater() {
        return Option.none();
    }

    private static OwnershipEpochHighWater highWaterAt(Epoch epoch) {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());

        highWater.advance(DOMAIN, epoch);

        return highWater;
    }

    private static EntityLinearizableBarrier countingBarrier(AtomicInteger counter) {
        return (_, _) -> countThenSucceed(counter);
    }

    private static Promise<Unit> countThenSucceed(AtomicInteger counter) {
        counter.incrementAndGet();

        return Promise.success(Unit.unit());
    }

    private static EntityLinearizableBarrier advancingBarrier(OwnershipEpochHighWater highWater, Epoch newEpoch) {
        return (_, _) -> advanceThenSucceed(highWater, newEpoch);
    }

    private static Promise<Unit> advanceThenSucceed(OwnershipEpochHighWater highWater, Epoch newEpoch) {
        highWater.advance(DOMAIN, newEpoch);

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

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
