// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityKeyspaceRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.PartitionKey;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
            // Settle gate off (0 ticks): this test pins the snapshot ORDER, not the gate (see SettleGate).
            var reconciler = EntityOwnershipReconciler.entityOwnershipReconciler(store,
                                                                                 N1,
                                                                                 () -> MEMBERS,
                                                                                 () -> true,
                                                                                 hrwOwner -> observingWriter(hrwOwner, observed),
                                                                                 recordingApplier(applied),
                                                                                 Runnable::run,
                                                                                 0,
                                                                                 EntityOwnershipReconciler.FIRST_MINT_CEILING_TICKS,
                                                                                 OperatorWarningSink.logOnly());

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
                                                                                 recordingApplier(applied), Runnable::run);

            reconciler.tick();

            assertThat(applied).as("a non-participating node must not issue removals into consensus")
                               .isEmpty();

            active.set(true);
            reconciler.tick();

            assertThat(applied).as("the same state must prune on the first ACTIVE tick — the gate defers, never cancels")
                               .containsExactly(List.of(new KVCommand.Remove<AetherKey>(registrationKey(KEYSPACE, N1))));
        }

        /// The unloading node: `retract` has to ask for a pass (and the pass must prune the node's committed
        /// registration). The executor DEFERS, so the pass running only when the queue is drained proves the
        /// trigger is the retract and not an unrelated periodic tick.
        @Test
        void retract_requestsAPassOffTheCallersThread_thatPrunesTheRegistration() {
            var store = emptyStore();

            seedRegistration(store, KEYSPACE, N1, PARTITIONS);

            var applied = new ArrayList<List<KVCommand<AetherKey>>>();
            var queued = new ArrayList<Runnable>();
            var reconciler = reconcilerOn(store, followerWriter(), applied, queued::add);

            reconciler.declare(KEYSPACE, PARTITIONS);
            reconciler.retract(KEYSPACE);

            assertThat(queued).as("retract must request exactly one pass")
                              .hasSize(1);
            assertThat(applied).as("the pass is handed off, not run on the caller's thread")
                               .isEmpty();

            queued.getFirst().run();

            assertThat(applied).as("the requested pass must prune the retracted keyspace's registration")
                               .containsExactly(List.of(new KVCommand.Remove<AetherKey>(registrationKey(KEYSPACE, N1))));
        }

        /// The leader: a committed registration REMOVAL must drive a pass (so the writer is asked to
        /// re-place the arcs), and an unrelated removal must not — the notification fires for every key
        /// family, and a pass per removal anywhere would be a consensus-read storm.
        @Test
        void onRegistrationRemoved_drivesAPassOnlyForARegistrationKey() {
            var store = storeRegisteredOn(HOSTS);
            var observed = new ArrayList<Option<NodeId>>();
            var reconciler = EntityOwnershipReconciler.entityOwnershipReconciler(store,
                                                                                 N1,
                                                                                 () -> MEMBERS,
                                                                                 () -> true,
                                                                                 hrwOwner -> observingWriter(hrwOwner, observed),
                                                                                 recordingApplier(new ArrayList<>()),
                                                                                 Runnable::run);

            reconciler.onRegistrationRemoved(new ValueRemove<>(new KVCommand.Remove<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(ARC,
                                                                                                                                              0)),
                                                               Option.none()));

            assertThat(observed).as("an ownership-record removal is not a hosting-set change")
                                .isEmpty();

            reconciler.onRegistrationRemoved(new ValueRemove<>(new KVCommand.Remove<>(registrationKey(KEYSPACE, N3)),
                                                               Option.none()));

            assertThat(observed).as("a registration removal must re-ask the writer for every arc")
                                .hasSize(PARTITIONS);
        }
        /// Passes now have three triggers on different (virtual) threads — the periodic tick, `retract`, a
        /// committed registration removal — and every pass publishes the SHARED hosting snapshot the writer's
        /// per-arc asks read. Two overlapping passes would emit the same delta twice and could drive one
        /// pass's writer from the other pass's snapshot. Pins the `synchronized` on `tick`: while one pass is
        /// inside the writer, a second must BLOCK on the monitor, never enter. Deterministic: the wait ends
        /// as soon as the second thread is either BLOCKED or inside the writer, so it needs no timeout.
        @Test
        void tick_neverRunsTwoPassesAtOnce() throws InterruptedException {
            var store = storeRegisteredOn(HOSTS);
            var inside = new java.util.concurrent.atomic.AtomicInteger();
            var maxInside = new java.util.concurrent.atomic.AtomicInteger();
            var firstEntered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var writer = new StreamPartitionOwnershipWriter() {
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

                @Override
                public List<KVCommand<AetherKey>> writeOwnershipChanges(List<PartitionKey> partitions) {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    firstEntered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();

                    return List.of();
                }
            };
            var reconciler = reconciler(store, writer, java.util.Collections.synchronizedList(new ArrayList<>()));
            var first = Thread.ofPlatform().start(reconciler::tick);

            firstEntered.await();

            var second = Thread.ofPlatform().start(reconciler::tick);

            while (second.getState() != Thread.State.BLOCKED && second.getState() != Thread.State.TERMINATED
                   && inside.get() < 2) {
                Thread.onSpinWait();
            }

            var overlapped = inside.get() >= 2;

            release.countDown();
            first.join();
            second.join();

            assertThat(overlapped).as("a second pass must block on the monitor, never enter the writer alongside the first")
                                  .isFalse();
            assertThat(maxInside.get()).as("both passes ran, one at a time")
                                       .isEqualTo(1);
        }

        private static EntityOwnershipReconciler reconciler(KVStore<AetherKey, AetherValue> store,
                                                            StreamPartitionOwnershipWriter writer,
                                                            List<List<KVCommand<AetherKey>>> applied) {
            return reconcilerOn(store, writer, applied, Runnable::run);
        }

        private static EntityOwnershipReconciler reconcilerOn(KVStore<AetherKey, AetherValue> store,
                                                              StreamPartitionOwnershipWriter writer,
                                                              List<List<KVCommand<AetherKey>>> applied,
                                                              Executor executor) {
            return EntityOwnershipReconciler.entityOwnershipReconciler(store,
                                                                       N1,
                                                                       () -> MEMBERS,
                                                                       () -> true,
                                                                       _ -> writer,
                                                                       recordingApplier(applied),
                                                                       executor);
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


    /// #1734: the settle gate. Driven through the real basic ownership writer over a store the applier commits to, so a
    /// "Put" here is a genuine ownership record (term and owner) and a second Put on an arc is a genuine owner change.
    /// Time is the pass count: registrations are sequenced between explicit `tick()` calls.
    @Nested
    class SettleGate {
        private static final int K = EntityOwnershipReconciler.SETTLE_TICKS;
        private static final int M = EntityOwnershipReconciler.FIRST_MINT_CEILING_TICKS;

        /// Test 1. The first mint waits for a quiet hosting set. Today (red on the base) the first tick over {N1,N2}
        /// mints all eight arcs, and N3's registration then moves some of them.
        @Test
        void firstMint_waitsForSettledHostingSet() {
            var cluster = new Cluster(K, M);

            cluster.register(N1);
            cluster.register(N2);

            assertThat(cluster.tick()).as("first sight of {N1,N2}: nothing may be minted").isZero();

            cluster.register(N3);

            assertThat(cluster.tick()).as("the set just changed").isZero();
            assertThat(cluster.tick()).as("unchanged for one pass: still short of %d", K).isZero();
            assertThat(cluster.tick()).as("unchanged for %d passes: the settled set is minted, once", K).isEqualTo(PARTITIONS);
            assertThat(cluster.owners()).as("every arc is placed over all three hosts, exactly as HRW says")
                                        .isEqualTo(hrwOver(cluster, List.of(N1, N2, N3)));
            assertThat(cluster.tick()).as("steady state").isZero();
        }

        /// Test 4, the arming control: with the gate off the SAME scenario mints on the first tick — so test 1's zero
        /// is produced by the gate, not by an empty store, a missing leader or an unreachable writer.
        @Test
        void firstMint_mintsOnTheFirstPass_whenTheGateIsOff() {
            var cluster = new Cluster(0, M);

            cluster.register(N1);
            cluster.register(N2);

            assertThat(cluster.tick()).as("control: the pre-#1734 behaviour mints at once").isEqualTo(PARTITIONS);
        }

        /// Test 2. A late host (a flap that registers and goes away inside the window) moves nothing: the live committed
        /// owners are kept, no term is bumped. Armed: HRW over four hosts WOULD move an arc, so zero Puts is the gate.
        @Test
        void lateHost_doesNotMoveMaterializingArcs() {
            var cluster = settledOn(N1, N2, N3);
            var before = cluster.owners();

            assertThat(hrwOver(cluster, List.of(N1, N2, N3, N4))).as("arming: HRW over the flap set differs from the committed owners")
                                                                 .isNotEqualTo(before);

            cluster.register(N4);

            assertThat(cluster.tick()).as("the flapping host just registered").isZero();

            cluster.unregister(N4);

            assertThat(cluster.tick()).as("and went away again before the set settled").isZero();
            assertThat(cluster.tick()).isZero();
            assertThat(cluster.tick()).isZero();
            assertThat(cluster.owners()).as("no owner changed").isEqualTo(before);
            assertThat(cluster.maxTerm()).as("no fence bump: every term is still 1").isEqualTo(1L);
        }

        /// Convergence half of the guarantee: a host that STAYS registered is rebalanced to HRW once the set is quiet
        /// for K passes — and not before.
        @Test
        void lateHost_registeredPermanently_rebalancesOnceSettled() {
            var cluster = settledOn(N1, N2, N3);

            cluster.register(N4);

            assertThat(cluster.tick()).isZero();
            assertThat(cluster.tick()).isZero();

            var moved = cluster.tick();

            assertThat(moved).as("settled on four hosts: the arcs HRW prefers on N4 move").isPositive();
            assertThat(cluster.owners()).isEqualTo(hrwOver(cluster, List.of(N1, N2, N3, N4)));
            assertThat(cluster.maxTerm()).as("a moved arc is one fence bump").isEqualTo(2L);
        }

        /// Test 3. Failover is never gated: the set is unsettled (N4 just registered), yet an owner that left the live
        /// members is replaced on that very pass — and only its arcs move.
        @Test
        void failover_bypassesTheGate_whenOwnerLeavesTheMembers() {
            var cluster = settledOn(N1, N2, N3);
            var before = cluster.owners();
            var departed = before.get(0);

            cluster.register(N4);
            cluster.members.set(without(MEMBERS, departed));

            var moved = cluster.tick();

            assertThat(moved).as("exactly the departed owner's arcs are re-minted, on the pass the set changed")
                             .isEqualTo((int) before.stream().filter(departed::equals).count());
            assertThat(cluster.owners()).allSatisfy(owner -> assertThat(owner).isNotEqualTo(departed));
            assertThat(cluster.ownersOtherThan(before, departed)).as("arcs of live owners were kept").isTrue();
        }

        /// Failover by retraction: the owner stays a member but its registration is gone, so it is no longer a host.
        @Test
        void failover_bypassesTheGate_whenOwnerRegistrationIsRetracted() {
            var cluster = settledOn(N1, N2, N3);
            var before = cluster.owners();
            var retracted = before.get(0);

            cluster.register(N4);
            cluster.unregister(retracted);

            assertThat(cluster.tick()).as("the retracted host's arcs move at once")
                                      .isEqualTo((int) before.stream().filter(retracted::equals).count());
            assertThat(cluster.owners()).allSatisfy(owner -> assertThat(owner).isNotEqualTo(retracted));
        }

        /// A1. A set that never settles must not starve the first mint: exactly one mint at pass M, one operator warning
        /// naming the keyspace and the ceiling, and no owner moves after it. A non-committing applier re-asks every pass,
        /// which is what pins the once-per-run announcement.
        @Test
        void firstMint_atTheCeiling_whenTheSetNeverSettles_mintsOnceAndWarns() {
            var cluster = new Cluster(K, M);

            cluster.register(N1);
            cluster.register(N2);

            IntStream.range(1, M)
                     .forEach(pass -> assertThat(flap(cluster, pass)).as("pass %d is under the ceiling", pass).isZero());

            assertThat(cluster.drainedWarnings()).as("no warning under the ceiling").isEmpty();
            assertThat(flap(cluster, M)).as("pass %d is the ceiling: every arc is minted, once", M).isEqualTo(PARTITIONS);

            var owners = cluster.owners();

            IntStream.rangeClosed(M + 1, 2 * M)
                     .forEach(pass -> assertThat(flap(cluster, pass)).as("after the ceiling mint nothing moves").isZero());

            assertThat(cluster.owners()).isEqualTo(owners);
            assertThat(cluster.maxTerm()).isEqualTo(1L);

            var warnings = cluster.drainedWarnings();

            assertThat(warnings).as("exactly one warning for the run").hasSize(1);
            assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.ENTITY_OWNERSHIP_UNSETTLED_MINT);
            assertThat(warnings.getFirst().subject()).isEqualTo(KEYSPACE);
            assertThat(warnings.getFirst().message()).contains("ceiling " + M);
            assertThat(warnings.getFirst().message()).as("passes 2..%d each changed the set", M)
                                                     .contains((M - 1) + " change(s) seen in " + M + " reconcile passes");
        }

        /// The announcement is once per unsettled run even when the mint is re-asked: the applier here commits nothing,
        /// so every pass from the ceiling on asks again, and eight Puts per pass reach it.
        @Test
        void firstMint_atTheCeiling_announcesOncePerRun_evenWhenTheApplyIsLost() {
            var cluster = new Cluster(K, M, false);

            cluster.register(N1);
            cluster.register(N2);
            IntStream.range(1, M)
                     .forEach(pass -> flap(cluster, pass));

            assertThat(flap(cluster, M)).isEqualTo(PARTITIONS);
            assertThat(flap(cluster, M + 1)).as("the lost apply is re-asked").isEqualTo(PARTITIONS);
            assertThat(flap(cluster, M + 2)).isEqualTo(PARTITIONS);
            assertThat(cluster.drainedWarnings()).as("one announcement for the whole run").hasSize(1);
        }

        /// A host that comes back as a member is a change of the hosting set (hosts that are also LIVE members): its
        /// arcs, failed over while it was away, do not return until the set has been quiet again.
        @Test
        void memberReturn_isAChangeOfTheHostingSet_andDoesNotMoveArcsBackAtOnce() {
            var cluster = settledOn(N1, N2, N3);
            var away = cluster.owners().get(0);

            cluster.members.set(without(MEMBERS, away));

            assertThat(cluster.tick()).as("the member left: failover").isPositive();

            var failedOver = cluster.owners();

            cluster.members.set(MEMBERS);

            assertThat(cluster.tick()).as("the member is back: a new hosting set, not yet settled").isZero();
            assertThat(cluster.owners()).isEqualTo(failedOver);
            assertThat(cluster.tick()).isZero();
            assertThat(cluster.tick()).as("quiet for %d passes: HRW again, the returned host reclaims its arcs", K).isPositive();
        }

        /// A changed arc span is a change of the hosting set too: arcs 8..15 appear unminted, and are minted only once
        /// the set is quiet.
        @Test
        void partitionCountChange_isAChangeOfTheHostingSet() {
            var cluster = settledOn(N1, N2, N3);

            seedRegistration(cluster.store, KEYSPACE, N3, 2 * PARTITIONS);

            assertThat(cluster.tick()).as("the span grew: new arcs wait for a quiet set").isZero();
            assertThat(cluster.tick()).isZero();
            assertThat(cluster.tick()).as("quiet again: the %d new arcs are minted", PARTITIONS).isEqualTo(PARTITIONS);
        }

        /// The ceiling is per unsettled RUN: once the set settles the pass count and the announcement re-arm, so a later
        /// run gets its own full ceiling and its own warning. The non-committing applier keeps arcs unminted across runs.
        @Test
        void ceiling_rearmsAfterTheSetSettles() {
            var cluster = new Cluster(K, M, false);

            cluster.register(N1);
            cluster.register(N2);
            IntStream.rangeClosed(1, M)
                     .forEach(pass -> flap(cluster, pass));

            assertThat(cluster.drainedWarnings()).as("first run announced").hasSize(1);

            IntStream.rangeClosed(1, K + 1)
                     .forEach(_ -> cluster.tick());

            IntStream.rangeClosed(1, M - 1)
                     .forEach(pass -> assertThat(flap(cluster, pass)).as("second run, pass %d: under its own ceiling", pass).isZero());

            assertThat(flap(cluster, M)).as("second run reaches its own ceiling").isEqualTo(PARTITIONS);
            assertThat(cluster.drainedWarnings()).as("second run announced again").hasSize(2);
        }

        /// One pass of a hosting set that never settles: N3's registration alternates every pass, absent on even passes —
        /// so at the ceiling pass N3 is not a host and owns nothing, and its later flaps are not failovers.
        private static int flap(Cluster cluster, int pass) {
            if (pass % 2 == 0) {
                cluster.unregister(N3);
            } else {
                cluster.register(N3);
            }

            return cluster.tick();
        }

        private static Cluster settledOn(NodeId... hosts) {
            var cluster = new Cluster(K, M);

            List.of(hosts).forEach(cluster::register);
            IntStream.rangeClosed(1, K + 1)
                     .forEach(_ -> cluster.tick());

            assertThat(cluster.owners()).as("precondition: the initial set was minted").hasSize(PARTITIONS);

            return cluster;
        }

        private static List<NodeId> hrwOver(Cluster cluster, List<NodeId> hosts) {
            var store = emptyStore();

            hosts.forEach(host -> seedRegistration(store, KEYSPACE, host, PARTITIONS));

            return IntStream.range(0, PARTITIONS)
                            .mapToObj(partition -> ownerOf(store, cluster.members.get(), partition))
                            .toList();
        }
    }

    /// A reconciler over a seeded store with the REAL basic writer; the applier commits ownership Puts into the store.
    private static final class Cluster {
        final KVStore<AetherKey, AetherValue> store = emptyStore();
        final AtomicReference<List<NodeId>> members = new AtomicReference<>(MEMBERS);
        private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
        private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warnings::add);
        private final List<KVCommand<AetherKey>> applied = new ArrayList<>();
        private final EntityOwnershipReconciler reconciler;

        Cluster(int settleTicks, int ceilingTicks) {
            this(settleTicks, ceilingTicks, true);
        }

        Cluster(int settleTicks, int ceilingTicks, boolean commits) {
            this.reconciler = EntityOwnershipReconciler.entityOwnershipReconciler(store,
                                                                                  N1,
                                                                                  members::get,
                                                                                  () -> false,
                                                                                  hrwOwner -> writer(hrwOwner),
                                                                                  commands -> apply(commands, commits),
                                                                                  Runnable::run,
                                                                                  settleTicks,
                                                                                  ceilingTicks,
                                                                                  sink);
        }

        private StreamPartitionOwnershipWriter writer(StreamPartitionOwnershipWriter.HrwOwner hrwOwner) {
            return StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> true,
                                                                                 () -> Epoch.epoch(0L, 1L, 0L),
                                                                                 HlcClock.hlcClock(N1),
                                                                                 (stream, partition) -> committed(stream, partition),
                                                                                 hrwOwner);
        }

        private Option<StreamPartitionOwnershipValue> committed(String stream, int partition) {
            return store.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream, partition),
                                  StreamPartitionOwnershipValue.class);
        }

        private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands, boolean commits) {
            applied.addAll(commands);

            if (commits) {
                store.process(store.createBatch(commands));
            }

            return Promise.success(List.of());
        }

        void register(NodeId node) {
            seedRegistration(store, KEYSPACE, node, PARTITIONS);
        }

        void unregister(NodeId node) {
            store.process(store.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Remove<>(registrationKey(KEYSPACE, node)))));
        }

        /// One pass; the number of ownership Puts it handed to the applier.
        int tick() {
            var before = applied.size();

            reconciler.tick();

            return applied.size() - before;
        }

        /// The committed owner of every arc, by partition; an absent record fails loudly.
        List<NodeId> owners() {
            return IntStream.range(0, PARTITIONS)
                            .mapToObj(partition -> committed(ARC, partition).map(StreamPartitionOwnershipValue::owner))
                            .flatMap(Option::stream)
                            .toList();
        }

        long maxTerm() {
            return IntStream.range(0, PARTITIONS)
                            .mapToObj(partition -> committed(ARC, partition))
                            .flatMap(Option::stream)
                            .mapToLong(StreamPartitionOwnershipValue::ownershipTerm)
                            .max()
                            .orElse(0L);
        }

        /// True when every arc whose owner was not `departed` kept its owner.
        boolean ownersOtherThan(List<NodeId> before, NodeId departed) {
            var now = owners();

            return IntStream.range(0, PARTITIONS)
                            .filter(partition -> !before.get(partition).equals(departed))
                            .allMatch(partition -> before.get(partition).equals(now.get(partition)));
        }

        /// Everything the sink has delivered so far. The sink is a FIFO hand-off drained by one thread, so a sentinel
        /// that arrives proves every earlier warning has too — no sleep, no timeout guess.
        List<OperatorWarning> drainedWarnings() {
            var sentinel = OperatorWarning.operatorWarning(OperatorWarningCode.DEPLOY_WARNING, "sentinel", "sentinel-" + System.nanoTime());

            sink.accept(sentinel);
            await().until(() -> warnings.contains(sentinel));

            return warnings.stream()
                           .filter(warning -> !warning.code().equals(sentinel.code()))
                           .toList();
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
