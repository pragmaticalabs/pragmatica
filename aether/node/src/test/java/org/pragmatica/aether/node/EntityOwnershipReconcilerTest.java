// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityKeyspaceRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.fail;

/// The durable-entity ownership reconcile (#345 I1 narrow C), driven off a seeded [KVStore] through the
/// package-visible seams — the `ClusterOwnershipRoutesTest` precedent for testing a decision that
/// otherwise only runs inside a leader tick.
///
/// The load-bearing one is [ArcOwnership#arcOwner_placesOnlyWithinRegisteredHosts]. This is the
/// 02w defect at unit level: with `instances = 3` on a five-node cluster the leader minted entity-arc
/// owners over ALL members, so nodes 4 and 5 owned arcs whose entity was never provisioned there and
/// refused every write handed to them. Nothing anywhere reported it as a placement fault — the refusal
/// arrived at the caller wearing a transient "ownership not yet committed" message.
class EntityOwnershipReconcilerTest {
    private static final String KEYSPACE = "orders";
    private static final String ARC = EntityPartitionArc.arcName(KEYSPACE);
    private static final int PARTITIONS = 8;

    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");
    private static final NodeId N4 = new NodeId("node-4");
    private static final NodeId N5 = new NodeId("node-5");

    /// The nodes that actually host the keyspace — `instances = 3`.
    private static final List<NodeId> HOSTS = List.of(N1, N2, N3);
    /// The live reconciled member view — the whole cluster.
    private static final List<NodeId> MEMBERS = List.of(N1, N2, N3, N4, N5);

    @Nested
    class ArcOwnership {
        /// THE 02w pin. Every one of the keyspace's eight arcs must land on a node that hosts it.
        ///
        /// The test arms itself first: it asserts that HRW over ALL FIVE members — the pre-fix placement —
        /// would put at least one arc on a non-host. It would put four of the eight there with these ids,
        /// but computing it live rather than trusting that arithmetic is what keeps the test from
        /// silently going vacuous if the ids, the arc name or the hash family ever change.
        @Test
        void arcOwner_placesOnlyWithinRegisteredHosts() {
            var store = storeRegisteredOn(HOSTS);

            assertPreFixPlacementWouldMisplace();

            IntStream.range(0, PARTITIONS)
                     .forEach(partition -> assertOwnedByAHost(store, partition));
        }

        /// A SIGKILLed host: its registration record SURVIVES (nothing ran to retract it) but it is gone
        /// from the live member view, so the intersection has to drop it and re-place within the
        /// survivors — not leave the arc on a node that cannot serve it.
        @Test
        void arcOwner_movesWithinHostingSet_whenOwnerLeavesMembers() {
            var store = storeRegisteredOn(HOSTS);

            IntStream.range(0, PARTITIONS)
                     .forEach(partition -> assertOwnerMovesToASurvivingHost(store, partition));
        }

        /// No registered host is live. The honest answer is "nobody" — re-placing onto a member that
        /// never hosted the keyspace is precisely the 02w defect, and it would convert a temporary
        /// outage into permanently refused writes on an arc the committed record says is owned.
        @Test
        void arcOwner_returnsNone_whenNoRegisteredHostIsAMember() {
            var store = storeRegisteredOn(HOSTS);

            IntStream.range(0, PARTITIONS)
                     .forEach(partition -> assertNoOwner(store, List.of(N4, N5), partition));
        }

        /// A bare name is a STREAM arc, not an entity one, and the two families share the ownership
        /// record type — answering a stream's placement out of entity registrations is the collision the
        /// `entity:` prefix exists to make impossible. Armed by the second assertion: the same store and
        /// the same members DO yield an owner under the prefixed name, so the missing prefix is the only
        /// thing producing `none()` here.
        @Test
        void arcOwner_returnsNone_forNonEntityArcName() {
            var store = storeRegisteredOn(HOSTS);

            assertThat(arcOwnerOf(store, MEMBERS, KEYSPACE, 0)
                           .isEmpty())
                .as("a bare keyspace name is not an entity arc")
                .isTrue();
            assertThat(arcOwnerOf(store, MEMBERS, ARC, 0)
                           .isEmpty())
                .as("the prefixed name over the same store and members must place — else the test above is vacuous")
                .isFalse();
        }
    }

    /// The exclusive-authority boundary with the stream ownership driver: entity arcs ARE real streams
    /// (their log rides `createStream` since I3), so the stream-side replica reconcile walks them too —
    /// and its ownership driver, placing over the whole member view, would fight the entity reconcile
    /// over the identical records after every catalog or membership edge, parking arcs on non-hosting
    /// nodes for up to one entity tick each time. The forge suite cannot observe that window (it
    /// converges before asserting), so this is the only sensor.
    @Nested
    class StreamDriverBoundary {
        @Test
        void withoutEntityArcs_dropsEntityArcs_andKeepsStreamArcs() {
            var streamArc = new PartitionKey(KEYSPACE, 0);
            var systemArc = new PartitionKey("system:cluster-events", 1);
            var mixed = List.of(streamArc, new PartitionKey(ARC, 0), systemArc, new PartitionKey(ARC, 7));

            assertThat(EntityOwnershipReconciler.withoutEntityArcs(mixed))
                .as("the stream ownership driver must never write an entity arc — and must keep every stream arc,"
                    + " including one whose BARE name equals the keyspace")
                .containsExactly(streamArc, systemArc);
        }
    }

    @Nested
    class RegistrationDelta {
        @Test
        void registrationDelta_declaredButUncommitted_putsTheSelfRecord() {
            var delta = EntityOwnershipReconciler.registrationDelta(emptyStore(), Map.of(KEYSPACE, PARTITIONS), N1, true);

            assertThat(delta).containsExactly(registrationPut(KEYSPACE, N1, PARTITIONS));
        }

        /// The put half must NOT sit behind the prune gate: a registration is deliberately re-asserted
        /// until it sticks, including through windows where the node is not (yet) consensus-active —
        /// gating it would re-open the strand-forever failure the keep-asserting shape exists to close.
        /// Pinned so a future tidy-up cannot widen the #702 gate over the puts.
        @Test
        void registrationDelta_declaredButUncommitted_putsEvenWhenPruneGateIsClosed() {
            var delta = EntityOwnershipReconciler.registrationDelta(emptyStore(), Map.of(KEYSPACE, PARTITIONS), N1, false);

            assertThat(delta).containsExactly(registrationPut(KEYSPACE, N1, PARTITIONS));
        }

        /// A converged node emits nothing, so a steady-state cluster does no consensus work per tick.
        @Test
        void registrationDelta_committedAndEqual_isEmpty() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, PARTITIONS);

            assertThat(EntityOwnershipReconciler.registrationDelta(store, Map.of(KEYSPACE, PARTITIONS), N1, true)).isEmpty();
        }

        /// A redeployed slice that changed its partition count must re-assert, or the leader keeps minting
        /// arcs against the old count and the node fences writes against a span nobody owns.
        @Test
        void registrationDelta_committedWithDifferentCount_putsTheDeclaredCount() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, 4);

            assertThat(EntityOwnershipReconciler.registrationDelta(store, Map.of(KEYSPACE, PARTITIONS), N1, true))
                .containsExactly(registrationPut(KEYSPACE, N1, PARTITIONS));
        }

        /// The pruning direction: a record this node committed for a keyspace it no longer declares — a
        /// retraction, or a restart without the slice. Leaving it would keep this node a placement
        /// candidate for a keyspace it can no longer serve. Doubles as the arming counterpart of the
        /// closed-gate test below: the same seed IS prunable when the gate is open.
        @Test
        void registrationDelta_committedForSelfButUndeclared_removesTheRecord() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, PARTITIONS);

            assertThat(EntityOwnershipReconciler.registrationDelta(store, Map.of(), N1, true))
                .containsExactly(new KVCommand.Remove<AetherKey>(registrationKey(KEYSPACE, N1)));
        }

        /// The #702 pin. An empty declared set on a node that is not consensus-active is not evidence of
        /// absence — a constructed-but-never-started node holds exactly this state beside whatever
        /// committed self-registrations its KV replica carries, and an ungated removal half turns it
        /// into a mass-removal issued into consensus. Armed by the open-gate test above: the identical
        /// seed produces the Remove there, so the emptiness here is the gate and not an empty scan.
        @Test
        void registrationDelta_committedForSelfButUndeclared_keepsTheRecordWhenPruneGateIsClosed() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, PARTITIONS);

            assertThat(EntityOwnershipReconciler.registrationDelta(store, Map.of(), N1, false))
                .as("a non-participating node must never prune its committed registrations")
                .isEmpty();
        }

        /// Another host's record is that host's own statement about itself. Judging it from here would
        /// re-open the very gap the per-node key shape closed. Armed by the second assertion: the same
        /// store viewed as N2 DOES produce the Remove, so the emptiness above is the node filter and not
        /// an empty scan.
        @Test
        void registrationDelta_committedForAnotherNodeAndUndeclaredHere_leavesItAlone() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N2, PARTITIONS);

            assertThat(EntityOwnershipReconciler.registrationDelta(store, Map.of(), N1, true))
                .as("N1 must never prune N2's registration")
                .isEmpty();
            assertThat(EntityOwnershipReconciler.registrationDelta(store, Map.of(), N2, true))
                .as("the same record IS prunable by its own node — else the assertion above is vacuous")
                .containsExactly(new KVCommand.Remove<AetherKey>(registrationKey(KEYSPACE, N2)));
        }
    }

    @Nested
    class ScannedRegistrations {
        /// A rolling redeploy window: two hosts disagree about the keyspace's partition count. The MAX
        /// wins — extra arcs are harmless no-ops, while minting fewer than a host fences against would
        /// strand that host's writes on unowned arcs forever. BOTH insertion orders are pinned because
        /// the pre-review implementation warned (and could have maxed) in only one of them.
        @Test
        void scanRegistrations_spansTheMaxAndFlagsTheDisagreement_whicheverHostRegisteredFirst() {
            assertMaxAndDisagreement(storeWithCounts(4, PARTITIONS));
            assertMaxAndDisagreement(storeWithCounts(PARTITIONS, 4));
        }

        /// The arming counterpart: agreeing hosts must NOT be flagged, or the disagreement flag is noise
        /// nobody can act on.
        @Test
        void scanRegistrations_reportsNoDisagreement_whenHostsAgree() {
            var scanned = EntityOwnershipReconciler.scanRegistrations(storeWithCounts(PARTITIONS, PARTITIONS));

            assertThat(scanned.get(KEYSPACE)
                              .countsDisagree()).isFalse();
        }

        private static void assertMaxAndDisagreement(KVStore<AetherKey, AetherValue> store) {
            var scanned = EntityOwnershipReconciler.scanRegistrations(store);
            var hosted = scanned.get(KEYSPACE);

            assertThat(hosted.partitionCount()).as("the max count must win regardless of registration order")
                                               .isEqualTo(PARTITIONS);
            assertThat(hosted.countsDisagree()).as("the disagreement must surface as data")
                                               .isTrue();
            assertThat(hosted.hosts()).containsExactlyInAnyOrder(N1, N2);
            assertThat(EntityOwnershipReconciler.entityArcs(scanned))
                .containsExactlyInAnyOrderElementsOf(arcsOf(ARC, PARTITIONS));
        }

        private static KVStore<AetherKey, AetherValue> storeWithCounts(int firstCount, int secondCount) {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, firstCount);
            seedRegistration(store, KEYSPACE, N2, secondCount);

            return store;
        }
    }

    /// The tick driver itself — the two absorbed failure paths and the converged no-op are interaction
    /// contracts (an absorbed failure has no result to assert on), so they are pinned against a
    /// recording applier and an injected writer.
    @Nested
    class Tick {
        /// A converged node: declared set equals committed records, and the writer (a follower here)
        /// emits nothing — the tick must apply NOTHING, or every steady-state tick costs a consensus
        /// round.
        @Test
        void tick_appliesNothing_whenConverged() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, PARTITIONS);

            var applied = new ArrayList<List<KVCommand<AetherKey>>>();
            var reconciler = reconciler(store, followerWriter(), applied);

            reconciler.declare(KEYSPACE, PARTITIONS);
            reconciler.tick();

            assertThat(applied).as("a converged tick must not reach consensus")
                               .isEmpty();
        }

        /// `ScheduledExecutorService` CANCELS a periodic task whose run throws — permanently and
        /// silently. A writer failure must be absorbed, and the NEXT tick must still do its work: the
        /// registration half runs before the writer and must reach the applier on every tick.
        @Test
        void tick_absorbsAWriterThrow_andKeepsWorkingNextTick() {
            var store = emptyStore();

            // Committed count differs from the declared one, so EVERY tick emits a registration Put
            // (the fake applier commits nothing) — proving the half BEFORE the throwing writer ran.
            seedRegistration(store, KEYSPACE, N1, 4);

            var applied = new ArrayList<List<KVCommand<AetherKey>>>();
            var reconciler = reconciler(store, throwingWriter(), applied);

            reconciler.declare(KEYSPACE, PARTITIONS);

            assertThatCode(() -> {
                reconciler.tick();
                reconciler.tick();
            }).as("a writer throw must never escape the tick")
              .doesNotThrowAnyException();
            assertThat(applied).as("the registration half must have run on BOTH ticks despite the writer failing")
                               .hasSize(2);
        }

        /// The snapshot ordering contract: the tick publishes the freshly-scanned hosting view BEFORE
        /// driving the writer, so the writer's per-arc owner asks resolve against THIS tick's
        /// registrations — a writer driven before the publish would see an empty view and place nothing.
        @Test
        void tick_publishesTheHostingSnapshot_beforeDrivingTheWriter() {
            var store = storeRegisteredOn(HOSTS);
            var observed = new ArrayList<Option<NodeId>>();
            var applied = new ArrayList<List<KVCommand<AetherKey>>>();
            var reconciler = EntityOwnershipReconciler.entityOwnershipReconciler(store,
                                                                                 N1,
                                                                                 () -> MEMBERS,
                                                                                 () -> true,
                                                                                 hrwOwner -> observingWriter(hrwOwner, observed),
                                                                                 recordingApplier(applied));

            reconciler.declare(KEYSPACE, PARTITIONS);
            reconciler.tick();

            assertThat(observed).as("the writer must have been asked once per arc")
                                .hasSize(PARTITIONS);
            assertThat(observed).allSatisfy(owner -> assertThat(snapshotAnswer(owner)).isIn(HOSTS));
        }

        /// Extracted so the fold's type variable is pinned by the return type — nested directly inside
        /// `assertThat` the poly expression is ambiguous to javac.
        private static NodeId snapshotAnswer(Option<NodeId> owner) {
            return owner.fold(() -> fail("the ask must resolve against this tick's snapshot"),
                              node -> node);
        }

        /// The #702 defect at tick level, and the gate's DEFER-not-cancel contract in one run. While the
        /// node is not consensus-active (never started, or dropped out of quorum) a tick over committed
        /// self-registrations and an empty declared set must reach consensus with NOTHING; the moment
        /// the node is active, the SAME state must produce the removal — so the restart-without-the-slice
        /// heal survives the gate, merely deferred to the first active tick.
        @Test
        void tick_suppressesStaleSelfRemovals_untilConsensusIsActive() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, PARTITIONS);

            var applied = new ArrayList<List<KVCommand<AetherKey>>>();
            var active = new AtomicBoolean(false);
            var reconciler = EntityOwnershipReconciler.entityOwnershipReconciler(store,
                                                                                 N1,
                                                                                 () -> MEMBERS,
                                                                                 active::get,
                                                                                 _ -> followerWriter(),
                                                                                 recordingApplier(applied));

            reconciler.tick();

            assertThat(applied).as("a non-participating node must not issue removals into consensus")
                               .isEmpty();

            active.set(true);
            reconciler.tick();

            assertThat(applied).as("the same state must prune on the first ACTIVE tick — the gate defers, never cancels")
                               .containsExactly(List.of(new KVCommand.Remove<AetherKey>(registrationKey(KEYSPACE, N1))));
        }

        private static EntityOwnershipReconciler reconciler(KVStore<AetherKey, AetherValue> store,
                                                            StreamPartitionOwnershipWriter writer,
                                                            List<List<KVCommand<AetherKey>>> applied) {
            return EntityOwnershipReconciler.entityOwnershipReconciler(store,
                                                                       N1,
                                                                       () -> MEMBERS,
                                                                       () -> true,
                                                                       _ -> writer,
                                                                       recordingApplier(applied));
        }

        private static Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> recordingApplier(List<List<KVCommand<AetherKey>>> applied) {
            return commands -> {
                applied.add(commands);

                return Promise.success(List.of());
            };
        }

        /// What the writer looks like on a follower: every ask short-circuits to none, so the batch is
        /// empty.
        private static StreamPartitionOwnershipWriter followerWriter() {
            return new StreamPartitionOwnershipWriter() {
                @Override
                public Option<KVCommand<AetherKey>> decide(String stream,
                                                           int partition,
                                                           Option<StreamPartitionOwnershipValue> committed,
                                                           NodeId hrwOwner,
                                                           Epoch committedEpoch) {
                    return Option.none();
                }

                @Override
                public Option<KVCommand<AetherKey>> writeOwnershipChange(String stream, int partition) {
                    return Option.none();
                }
            };
        }

        private static StreamPartitionOwnershipWriter throwingWriter() {
            return new StreamPartitionOwnershipWriter() {
                @Override
                public Option<KVCommand<AetherKey>> decide(String stream,
                                                           int partition,
                                                           Option<StreamPartitionOwnershipValue> committed,
                                                           NodeId hrwOwner,
                                                           Epoch committedEpoch) {
                    throw new IllegalStateException("writer deliberately failing");
                }

                @Override
                public Option<KVCommand<AetherKey>> writeOwnershipChange(String stream, int partition) {
                    throw new IllegalStateException("writer deliberately failing");
                }
            };
        }

        /// Records what the reconciler-bound [StreamPartitionOwnershipWriter.HrwOwner] answers for each
        /// arc the tick drives — the seam through which the snapshot-ordering contract is observable.
        private static StreamPartitionOwnershipWriter observingWriter(StreamPartitionOwnershipWriter.HrwOwner hrwOwner,
                                                                      List<Option<NodeId>> observed) {
            return new StreamPartitionOwnershipWriter() {
                @Override
                public Option<KVCommand<AetherKey>> decide(String stream,
                                                           int partition,
                                                           Option<StreamPartitionOwnershipValue> committed,
                                                           NodeId owner,
                                                           Epoch committedEpoch) {
                    return Option.none();
                }

                @Override
                public Option<KVCommand<AetherKey>> writeOwnershipChange(String stream, int partition) {
                    observed.add(hrwOwner.ownerOf(stream, partition));

                    return Option.none();
                }
            };
        }
    }

    // ---- assertions ----------------------------------------------------------------------------

    private static void assertOwnedByAHost(KVStore<AetherKey, AetherValue> store, int partition) {
        assertThat(ownerOf(store, MEMBERS, partition))
            .as("arc %s partition %d must be owned by a node that HOSTS the keyspace", ARC, partition)
            .isIn(HOSTS);
    }

    private static void assertOwnerMovesToASurvivingHost(KVStore<AetherKey, AetherValue> store, int partition) {
        var dead = ownerOf(store, MEMBERS, partition);
        var survivingHosts = HOSTS.stream()
                                  .filter(host -> !host.equals(dead))
                                  .toList();
        var moved = ownerOf(store, without(MEMBERS, dead), partition);

        assertThat(moved)
            .as("partition %d must leave the departed host %s and land on another REGISTERED host", partition, dead)
            .isIn(survivingHosts);
    }

    private static void assertNoOwner(KVStore<AetherKey, AetherValue> store, List<NodeId> members, int partition) {
        assertThat(arcOwnerOf(store, members, ARC, partition)
                       .isEmpty())
            .as("partition %d has no live registered host, so no owner may be minted", partition)
            .isTrue();
    }

    /// The arming check for [ArcOwnership#arcOwner_placesOnlyWithinRegisteredHosts]: HRW over the
    /// FULL member list — what the leader did before the fix — must land on a non-hosting node somewhere,
    /// or that test would pass against the defect it exists to catch.
    private static void assertPreFixPlacementWouldMisplace() {
        var misplaced = IntStream.range(0, PARTITIONS)
                                 .filter(partition -> !HOSTS.contains(preFixOwnerOf(partition)))
                                 .count();

        assertThat(misplaced)
            .as("HRW over all %d members must misplace at least one of the %d arcs, or the hosting-set"
                + " assertion proves nothing", MEMBERS.size(), PARTITIONS)
            .isPositive();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static NodeId preFixOwnerOf(int partition) {
        return ReplicaPlacement.place(ARC, partition, MEMBERS, 1)
                               .map(Placement::owner)
                               .fold(() -> fail("HRW must place over a non-empty member list"), owner -> owner);
    }

    private static NodeId ownerOf(KVStore<AetherKey, AetherValue> store, List<NodeId> members, int partition) {
        return arcOwnerOf(store, members, ARC, partition).fold(() -> fail("no owner minted for partition " + partition),
                                                               owner -> owner);
    }

    /// The production composition: the tick's one registration scan feeding the per-arc owner decision.
    private static Option<NodeId> arcOwnerOf(KVStore<AetherKey, AetherValue> store,
                                             List<NodeId> members,
                                             String arcName,
                                             int partition) {
        return EntityOwnershipReconciler.arcOwner(EntityOwnershipReconciler.scanRegistrations(store),
                                                  members,
                                                  arcName,
                                                  partition);
    }

    private static List<NodeId> without(List<NodeId> members, NodeId departed) {
        return members.stream()
                      .filter(member -> !member.equals(departed))
                      .toList();
    }

    private static List<PartitionKey> arcsOf(String arcName, int partitionCount) {
        return IntStream.range(0, partitionCount)
                        .mapToObj(partition -> new PartitionKey(arcName, partition))
                        .toList();
    }

    private static EntityKeyspaceRegistrationKey registrationKey(String keyspace, NodeId node) {
        return EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey(keyspace, node);
    }

    private static KVCommand<AetherKey> registrationPut(String keyspace, NodeId node, int partitionCount) {
        return new KVCommand.Put<AetherKey, AetherValue>(registrationKey(keyspace, node),
                                                         EntityKeyspaceRegistrationValue.entityKeyspaceRegistrationValue(partitionCount));
    }

    /// A store where every node in `hosts` has committed its OWN per-node registration for the keyspace —
    /// the state the leader reads to learn the hosting set.
    private static KVStore<AetherKey, AetherValue> storeRegisteredOn(List<NodeId> hosts) {
        var store = emptyStore();

        hosts.forEach(host -> seedRegistration(store, KEYSPACE, host, PARTITIONS));

        return store;
    }

    private static void seedRegistration(KVStore<AetherKey, AetherValue> store,
                                         String keyspace,
                                         NodeId node,
                                         int partitionCount) {
        store.process(store.createBatch(List.of(registrationPut(keyspace, node, partitionCount))));
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

    /// Nothing here restores a snapshot, so a read is a bug rather than a value worth stubbing — it
    /// fails loudly instead of handing back a null the assertion would blame on the reconciler.
    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                throw new UnsupportedOperationException("not used by this test");
            }
        };
    }
}
