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
import org.pragmatica.cluster.state.kvstore.KVCommand;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    /// The NAMESPACED arc the entity fences and routes against (`entity:orders`), not the bare keyspace —
    /// advancing the bare one would move a different arc and the fence assertions would pass vacuously.
    private static final OwnershipDomain DOMAIN = OwnershipDomain.streamPartition(EntityPartitionArc.arcName(KEYSPACE),
                                                                                   PARTITION);

    @Nested
    class RoutesToCommittedOwner {
        /// LINEARIZABLE at the committed owner (SELF) serves the local value once the owner-side guards
        /// pass and the round has run.
        @Test
        void get_servesLocal_whenSelfIsCommittedOwner() {
            seeded(committedOwner(SELF, Epoch.ZERO), noHighWater(), someBarrier())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onFailure(EntityLinearizableReadTest::failCause)
                    .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }

        /// A REMOTE committed owner and no entity read-forward transport (1e-b): the read is rejected
        /// NotCurrentOwner so the caller re-resolves the owner, never served from a stale local copy.
        /// Decided BEFORE the round, so it holds with or without a barrier.
        @Test
        void get_rejectsNotCurrentOwner_whenCommittedOwnerIsRemote() {
            seeded(committedOwner(OTHER, Epoch.ZERO), noHighWater(), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected NotCurrentOwner, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.NotCurrentOwner.class));
        }

        /// No committed record (legacy / unowned arc): LINEARIZABLE degrades to the local read. This
        /// absence is benign — with no committed owner there is nothing to route to and nothing to fence
        /// against — unlike an absent BARRIER, which refuses (see [MissingBarrier]).
        @Test
        void get_fallsBackToLocal_whenNoCommittedRecord() {
            seeded(CommittedPartitionOwnerSource.none(), noHighWater(), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onFailure(EntityLinearizableReadTest::failCause)
                    .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }
    }

    /// #345 I1 owner ruling: an absent [EntityLinearizableBarrier] costs FRESHNESS, so the resource
    /// provisions and `BOUNDED_STALE` keeps working — but a `LINEARIZABLE` read cannot order the no-op
    /// round that makes the post-round fence current, so it is REFUSED. Serving the local read instead
    /// would answer a `LINEARIZABLE` request with `BOUNDED_STALE` data under the stronger name, which is
    /// the specific failure this refusal exists to prevent.
    @Nested
    class MissingBarrier {
        @Test
        void get_rejectsLinearizableUnavailable_whenNoBarrierWired() {
            seeded(committedOwner(SELF, Epoch.ZERO), noHighWater(), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected LinearizableUnavailable, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.LinearizableUnavailable.class));
        }

        /// The refusal is per-READ, not per-resource: the SAME entity still serves BOUNDED_STALE.
        @Test
        void get_stillServesBoundedStale_whenNoBarrierWired() {
            seeded(committedOwner(SELF, Epoch.ZERO), noHighWater(), Option.none())
                    .get(KEY, ReadConsistency.BOUNDED_STALE)
                    .await(AWAIT)
                    .onFailure(EntityLinearizableReadTest::failCause)
                    .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }

        /// A rejection that fires before the round is unaffected: a deposed owner is still reported as
        /// StaleEpochRead, not masked by the barrier refusal.
        @Test
        void get_stillRejectsStaleEpochRead_whenNoBarrierWired() {
            var highWater = highWaterAt(Epoch.epoch(5, 0));

            seeded(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater), Option.none())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected StaleEpochRead, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.StaleEpochRead.class));
        }
    }

    @Nested
    class OwnerSideEpochFence {
        /// StaleEpochRead: self IS the committed owner, but the committed ownerEpoch is STRICTLY older than
        /// the arc high-water — self is a deposed owner whose committed record is stale.
        @Test
        void get_rejectsStaleEpochRead_whenCommittedEpochBelowHighWater() {
            var highWater = highWaterAt(Epoch.epoch(5, 0));

            seeded(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater), someBarrier())
                    .get(KEY, ReadConsistency.LINEARIZABLE)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected StaleEpochRead, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.StaleEpochRead.class));
        }

        /// Equal committed epoch is NOT stale — a genuinely-current owner is never spuriously fenced.
        @Test
        void get_serves_whenCommittedEpochEqualsHighWater() {
            var highWater = highWaterAt(Epoch.epoch(3, 0));

            seeded(committedOwner(SELF, Epoch.epoch(3, 0)), Option.some(highWater), someBarrier())
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
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.StaleEpochRead.class));
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

        /// With no owner-serve wiring at all (the bare [InMemoryDurableEntity], which has no committed-owner
        /// routing to consult) a LINEARIZABLE read degrades to the local read — honest only because that
        /// entity is a single process-local map with one serialized writer per key. Distinct from a WIRED
        /// entity whose BARRIER is missing, which refuses ([MissingBarrier]): there the pipeline exists and
        /// cannot complete the round, here there is no pipeline and no replica for a round to synchronize.
        @Test
        void get_linearizable_degradesToLocal_whenUnwired() {
            var entity = seededPlain();

            entity.get(KEY, ReadConsistency.LINEARIZABLE)
                  .await(AWAIT)
                  .onFailure(EntityLinearizableReadTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }
    }

    /// The production barrier binding ([EntityLinearizableBarrier#noOpRound]) — the durable-entity mirror
    /// of the stream's `LinearizableBarrier.noOpRound`, over the same cluster applier.
    @Nested
    class ProductionBarrier {
        @Test
        void noOpRound_submitsOneNoopForTheArc() {
            var submitted = new AtomicReference<List<KVCommand<AetherKey>>>();

            EntityLinearizableBarrier.noOpRound(commands -> capture(submitted, commands), AWAIT)
                                     .awaitRound(EntityPartitionArc.arcName(KEYSPACE), PARTITION)
                                     .await(AWAIT)
                                     .onFailure(EntityLinearizableReadTest::failCause);

            assertThat(submitted.get()).hasSize(1);
            assertThat(submitted.get().getFirst()).isInstanceOf(KVCommand.Noop.class);
        }

        /// The Noop carries the arc's own ownership key, so concurrent barriers on one arc share a single
        /// round via the content-derived batch id.
        @Test
        void noOpRound_keysTheNoopByTheArcOwnershipKey() {
            var submitted = new AtomicReference<List<KVCommand<AetherKey>>>();

            EntityLinearizableBarrier.noOpRound(commands -> capture(submitted, commands), AWAIT)
                                     .awaitRound(EntityPartitionArc.arcName(KEYSPACE), PARTITION)
                                     .await(AWAIT)
                                     .onFailure(EntityLinearizableReadTest::failCause);

            assertThat(submitted.get().getFirst().key())
                    .isEqualTo(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(EntityPartitionArc.arcName(KEYSPACE),
                                                                                                   PARTITION));
        }

        /// An applier that never resolves must not hang the read: the round is bounded by the timeout and
        /// the read is rejected rather than served from a pre-round view.
        @Test
        void noOpRound_failsOnTimeout_whenTheRoundNeverApplies() {
            EntityLinearizableBarrier.noOpRound(_ -> Promise.promise(), timeSpan(100).millis())
                                     .awaitRound(KEYSPACE, PARTITION)
                                     .await(AWAIT)
                                     .onSuccess(_ -> fail("an unapplied round must not resolve successfully"));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static DurableEntity<String, Integer, IntOp> seeded(CommittedPartitionOwnerSource committedOwnerSource,
                                                         Option<OwnershipEpochHighWater> highWater,
                                                         Option<EntityLinearizableBarrier> barrier) {
        var entity = InMemoryDurableEntity.<String, Integer, IntOp> inMemoryDurableEntity(SELF,
                                                                                   ARC,
                                                                                   committedOwnerSource,
                                                                                   highWater,
                                                                                   barrier);

        entity.create(KEY, 7).await(AWAIT).onFailure(EntityLinearizableReadTest::failCause);

        return entity;
    }

    private static DurableEntity<String, Integer, IntOp> seededPlain() {
        var entity = InMemoryDurableEntity.<String, Integer, IntOp> inMemoryDurableEntity();

        entity.create(KEY, 7).await(AWAIT).onFailure(EntityLinearizableReadTest::failCause);

        return entity;
    }

    private static CommittedPartitionOwnerSource committedOwner(NodeId owner, Epoch epoch) {
        return (_, _) -> Option.some(new CommittedOwner(owner, epoch));
    }

    private static Option<OwnershipEpochHighWater> noHighWater() {
        return Option.none();
    }

    /// A barrier that orders nothing and succeeds — present so the pipeline reaches its post-round
    /// decision, for tests whose subject is routing or the fence rather than the round itself.
    private static Option<EntityLinearizableBarrier> someBarrier() {
        return Option.some((_, _) -> Promise.success(Unit.unit()));
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

    private static Promise<List<Object>> capture(AtomicReference<List<KVCommand<AetherKey>>> sink,
                                                  List<KVCommand<AetherKey>> commands) {
        sink.set(commands);

        return Promise.success(List.of());
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
