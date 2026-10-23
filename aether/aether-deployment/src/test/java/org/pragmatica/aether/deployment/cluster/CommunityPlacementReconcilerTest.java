// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityPlacementReconcilerTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId OLD = new NodeId("old");
    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.pool]
        type = "forge"
        zones = ["old", "new"]
        [source.pool.core]
        count = 3
        [source.pool.worker]
        count = 1
        [community.stable]
        target_size = 1
        [community.stable.placement.destination]
        source = "pool"
        zone = "new"
        """;
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final Set<NodeId> ready = new HashSet<>();
    private final List<String> effects = new ArrayList<>();
    private final List<NodeId> retirementRefusals = new ArrayList<>();
    private final List<CommunityPlacementOperationValue> escalations = new ArrayList<>();
    private final List<CommunityPlacementOperationValue> providerOperations = new ArrayList<>();
    private String binding = "binding";
    private final Set<String> unavailableSources = new HashSet<>();
    private boolean oldExists = true;
    private boolean safeToRetire = true;
    private Promise<Unit> createOutcome = Promise.unitPromise();
    private Runnable beforeCommit = () -> {};
    /// Every batch the reconciler submitted, in order.
    private final List<List<KVCommand<AetherKey>>> committed = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final CommunityPlacementReconciler.Actuator actuator = new CommunityPlacementReconciler.Actuator() {
        @Override public org.pragmatica.lang.Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source) {
            return unavailableSources.contains(source.value())
                   ? Causes.cause("source " + source.value() + " is unavailable").result()
                   : org.pragmatica.lang.Result.success(binding);
        }
        @Override public Promise<Unit> create(CommunityPlacementOperationValue operation) {
            assertThat(current().phase()).isEqualTo(PlacementOperationPhase.CREATE_REQUESTED);
            providerOperations.add(operation);
            effects.add("create");
            return createOutcome;
        }
        @Override public Promise<Boolean> retirementSafe(CommunityPlacementOperationValue operation) { return Promise.success(safeToRetire); }
        @Override public Promise<Unit> drain(CommunityPlacementOperationValue operation) {
            assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_REQUESTED);
            effects.add("drain");
            return Promise.unitPromise();
        }
        @Override public Promise<Unit> terminate(CommunityPlacementOperationValue operation) {
            assertThat(current().phase()).isEqualTo(PlacementOperationPhase.TERMINATING);
            providerOperations.add(operation);
            effects.add("terminate");
            oldExists = false;
            return Promise.unitPromise();
        }
        @Override public Promise<Boolean> previousInstanceExists(CommunityPlacementOperationValue operation) {
            return Promise.success(oldExists);
        }
    };
    private final List<String> wallClockStamps = new ArrayList<>();
    private final CommunityPlacementReconciler reconciler = controller(10, 60_000);

    /// The clock the reconciler reads. A subclass swaps in a clock far from the wall clock, which reruns every
    /// scenario here and fails on any placement stamp that did not come from it (#1535 item 7).
    java.util.function.LongSupplier clock() { return System::currentTimeMillis; }

    /// True when [#clock] is not the wall clock, so the audit applies.
    boolean injectedClock() { return false; }

    @org.junit.jupiter.api.AfterEach
    void everyStampComesFromTheInjectedClock() {
        if (injectedClock() && !wallClockStamps.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail("stamps not read from the injected clock: " + wallClockStamps);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private CommunityPlacementReconciler controller(int limit, long timeout) {
        return controller(CORE, limit, timeout);
    }

    private CommunityPlacementReconciler controller(NodeId self, int limit, long timeout) {
        return controller(self, limit, timeout, clock());
    }

    private CommunityPlacementReconciler controller(NodeId self, int limit, long timeout, java.util.function.LongSupplier clock) {
        return CommunityPlacementReconciler.communityPlacementReconciler(self, store,
            commands -> {
                committed.add(commands);
                wallClockStamps.addAll(PlacementStampAudit.wallClockStamps(commands));
                var hook = beforeCommit;
                beforeCommit = () -> {};
                hook.run();
                return Promise.success(store.process(store.createBatch(commands)));
            }, () -> true,
            ready::contains, () -> Set.of(CORE, OLD), () -> limit, actuator, escalations::add, (node, cause) -> retirementRefusals.add(node), org.pragmatica.lang.io.TimeSpan.timeSpan(timeout).millis(), clock);
    }

    private void initialize() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(CONFIG), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(OLD), new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(OLD), new AetherValue.NodePlacementValue("pool", Option.some("old"), "old-instance")));
        ready.add(OLD);
    }

    @Test
    void stalePlacementsDoNotConsumeFleetCapacityWhenLedgerExists() {
        initialize();
        var ledger = AetherKey.CapacityLedgerKey.INSTANCE;
        seed(new KVCommand.LeaderTransaction<>(ledger, "initialize-ledger", new LeaderValue(CORE, 1), List.of(),
            List.of(new KVCommand.Mutation<>(ledger, Option.none(), Option.some(new AetherValue.CapacityLedgerValue(2, 1, true))))));
        for (var name : List.of("dead-one", "dead-two")) {
            seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(new NodeId(name)),
                new AetherValue.NodePlacementValue("pool", Option.some("old"), name)));
        }
        var bounded = controller(3, 60_000);
        bounded.reconcile().await().unwrap();
        assertThat(store.get(new AetherKey.CommunityPlacementOperationKey("stable")).isPresent())
            .as("two stale placement keys must not consume the one available fleet slot")
            .isTrue();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.RESERVED);
        bounded.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
        assertThat(effects).containsExactly("create");
        assertThat(store.get(new AetherKey.NodePlacementKey(new NodeId("dead-one"))).isPresent()).isTrue();
        assertThat(store.get(new AetherKey.NodePlacementKey(new NodeId("dead-two"))).isPresent()).isTrue();
    }

    /// F6 (s25), the counting half: once the ledger exists the fleet check counts its reservations, not stale placement keys. A reservation
    /// is capacity whether or not its worker ever placed.
    @Test
    void reservationsConsumeFleetCapacityWhenLedgerExists() {
        initialize();
        var ledger = AetherKey.CapacityLedgerKey.INSTANCE;
        seed(new KVCommand.LeaderTransaction<>(ledger, "initialize-ledger", new LeaderValue(CORE, 1), List.of(),
            List.of(new KVCommand.Mutation<>(ledger, Option.none(), Option.some(new AetherValue.CapacityLedgerValue(1, 1, true))))));
        var reserved = new AetherKey.CapacityReservationKey(new NodeId("reserved-elsewhere"));
        seed(new KVCommand.LeaderTransaction<>(reserved, "reserve", new LeaderValue(CORE, 1), List.of(),
            List.of(new KVCommand.Mutation<AetherKey, AetherValue>(reserved, Option.none(), Option.some(
                new AetherValue.CapacityReservationValue("pool", "binding", "worker", AetherValue.CapacityReservationPhase.OBSERVED))))));

        controller(3, 60_000).reconcile().await().unwrap();

        assertThat(store.get(new AetherKey.CommunityPlacementOperationKey("stable")).isEmpty())
            .as("the core, the old worker and the reservation fill the fleet of three")
            .isTrue();
    }

    /// F3 (s25): a BLOCKED operation is not stuck for ever. Once its target is observed the operation proceeds from where it was,
    /// without a second create; until then it stays BLOCKED.
    @Test
    void blockedOperation_exitsOnceItsTargetIsObserved() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        var before = current();
        var blocked = before.withPhase(PlacementOperationPhase.BLOCKED, before.issuer(), "blocked by an earlier conflict");
        var key = new AetherKey.CommunityPlacementOperationKey("stable");
        seed(new KVCommand.LeaderTransaction<>(key, "block", before.issuer(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(blocked)))));

        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).as("target not observed: still blocked").isEqualTo(PlacementOperationPhase.BLOCKED);

        replacementReady();
        reconciler.reconcile().await().unwrap();

        assertThat(current().phase()).isNotEqualTo(PlacementOperationPhase.BLOCKED);
        assertThat(current().targetNode()).isEqualTo(before.targetNode());
        assertThat(effects).as("no second create").containsExactly("create");
    }

    /// Communities reconcile in isolated lanes: one whose source is unavailable reports its failure and leaves its durable state for the
    /// next pass, and does not stop the communities after it.
    @Test
    void communityWithUnavailableSource_doesNotStopTheNextCommunity() {
        initialize();
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(CONFIG + """
                [source.bad]
                type = "forge"
                zones = ["z"]
                [source.bad.worker]
                count = 1
                [community.aaa]
                target_size = 1
                [community.aaa.placement.destination]
                source = "bad"
                zone = "z"
                """), "test", "1.0.0", List.of(), 3, 3, "forge", 2, 0)));
        unavailableSources.add("bad");

        reconciler.reconcile().await().unwrap();

        assertThat(store.get(new AetherKey.CommunityPlacementOperationKey("aaa")).isEmpty()).as("the unavailable source's community waits").isTrue();
        assertThat(store.get(new AetherKey.CommunityPlacementOperationKey("stable")).isPresent()).as("the community after it still progressed").isTrue();
    }

    @Test
    void missingAssignmentEscalatesOnceWithoutProviderEffects() {
        initialize();
        var unknown = new NodeId("unassigned");
        var entry = new AetherValue.TopologyEntry("pool", "worker", 0);
        assertThat(reconciler.requestRetirement(unknown, entry).await().isFailure()).isTrue();
        assertThat(reconciler.requestRetirement(unknown, entry).await().isFailure()).isTrue();
        assertThat(retirementRefusals).containsExactly(unknown);
        assertThat(effects).isEmpty();
    }

    private void seedSurgeWorker(NodeId surge) {
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(surge), new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(surge), new AetherValue.NodePlacementValue("pool", Option.some("old"), "surge-instance")));
        ready.add(surge);
    }

    /// Control: with NO pairing a second member of a size-1 community is an excess, and the reduction removes whichever
    /// member sorts first, here the newcomer. This is what a surge replacement would suffer without the pairing.
    @Test
    void unpairedExtraMember_isReducedAway_theNewcomerSortingFirst() {
        initialize();
        var surge = new NodeId("a-surge");

        seedSurgeWorker(surge);
        reconciler.reconcile().await();

        assertThat(current().previousNode().unwrap()).isEqualTo(surge);
    }

    /// #1543 E2: mid-replacement BOTH nodes are protected (the original until it retires, the replacement throughout), so no
    /// reduction may name either of them as the node to remove.
    @Test
    void pairedOriginalAndReplacement_areBothProtected_noReductionTakesEither() {
        initialize();
        var replacement = new NodeId("a-replacement");

        seedSurgeWorker(replacement);
        reconciler.protectReplacements(() -> Set.of(OLD, replacement), () -> Set.of(replacement));
        reconciler.reconcile().await();

        assertThat(store.getTyped(new AetherKey.CommunityPlacementOperationKey("stable"), CommunityPlacementOperationValue.class)
                        .flatMap(operation -> operation.previousNode())
                        .isEmpty()).as("no operation removes a protected node").isTrue();
    }

    /// When the original is retiring (so the replacement is counted again), the reduction must take the ORIGINAL, never the
    /// protected replacement, even though the replacement sorts first.
    @Test
    void protectedReplacement_isNeverTheNodeAReductionRemoves() {
        initialize();
        var replacement = new NodeId("a-replacement");

        seedSurgeWorker(replacement);
        reconciler.protectReplacements(() -> Set.of(replacement), () -> Set.of(replacement));
        reconciler.reconcile().await();

        assertThat(current().previousNode().unwrap()).isEqualTo(OLD);
    }

    /// v-2042: a community of size 2 already holds its two members; a live pairing adds a surge replacement as the THIRD. The
    /// shield keeps the pairing's nodes from being removed, but the third member still counted as an excess, so the reduction
    /// removed whichever UNSHIELDED member sorted first: an innocent one. While a pairing is live the surge is not an excess.
    @Test
    void surgeReplacement_isNotAnExcess_theReductionNeverRemovesAnInnocentMember() {
        var original = new NodeId("a-original");
        var innocent = new NodeId("b-innocent");
        var replacement = new NodeId("c-replacement");
        var sizeTwoInOldZone = CONFIG.replace("target_size = 1", "target_size = 2").replace("zone = \"new\"", "zone = \"old\"");

        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(sizeTwoInOldZone), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        for (var member : List.of(original, innocent, replacement)) {
            seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(member), new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
            seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(member), new AetherValue.NodePlacementValue("pool", Option.some("old"), member.id() + "-instance")));
            ready.add(member);
        }
        reconciler.protectReplacements(() -> Set.of(original, replacement), () -> Set.of(replacement));
        reconciler.reconcile().await();

        var operation = store.getTyped(new AetherKey.CommunityPlacementOperationKey("stable"), CommunityPlacementOperationValue.class);

        assertThat(operation.flatMap(value -> value.previousNode()).map(NodeId::id).or("none"))
            .as("no member is removed while the third is a pairing's surge replacement").isEqualTo("none");
    }

    /// #1535 item 7 support: members beyond the target are reduced by a scale-down operation naming the first
    /// unprotected member (no pairing is live, so nothing shields any of the three).
    @Test
    void excessMembers_areReducedByAScaleDownOperation() {
        var sizeTwoInOldZone = CONFIG.replace("target_size = 1", "target_size = 2").replace("zone = \"new\"", "zone = \"old\"");

        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(sizeTwoInOldZone), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        for (var member : List.of(new NodeId("a-first"), new NodeId("b-second"), new NodeId("c-third"))) {
            seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(member), new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
            seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(member), new AetherValue.NodePlacementValue("pool", Option.some("old"), member.id() + "-instance")));
            ready.add(member);
        }

        reconciler.reconcile().await().unwrap();

        assertThat(current().detail()).isEqualTo("Scale down");
        assertThat(current().previousNode().unwrap()).isEqualTo(new NodeId("a-first"));
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
    }

    /// A community that starts empty has no previous node to drain: once the new member is ready the operation completes.
    @Test
    void firstMemberOfAnEmptyCommunity_completesWithoutADrain() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(CONFIG), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));

        reconciler.reconcile().await().unwrap();
        assertThat(current().previousNode().isEmpty()).isTrue();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        reconciler.reconcile().await().unwrap();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        assertThat(effects).containsExactly("create");
    }

    /// A create the capacity ledger cannot admit (no definitive refusal) returns the operation to RESERVED with the
    /// reason and escalates once; no provider effect follows.
    @Test
    void capacityNotAdmitted_defersTheOperationBackToReserved() {
        initialize();
        createOutcome = CapacityControlledLifecycle.AdmissionFailure.CAPACITY_UNAVAILABLE.promise();

        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.RESERVED);
        assertThat(current().detail()).isNotEmpty();
        assertThat(escalations).hasSize(1);
    }

    /// The factory production wires (no clock argument) stamps with the wall clock.
    @Test
    void factoryWithoutAClock_stampsWithTheWallClock() {
        var production = CommunityPlacementReconciler.communityPlacementReconciler(CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true,
            ready::contains, () -> Set.of(CORE, OLD), () -> 10, actuator, escalations::add, (node, cause) -> {},
            org.pragmatica.lang.io.TimeSpan.timeSpan(60_000).millis());
        var before = System.currentTimeMillis();

        initialize();
        production.reconcile().await().unwrap();

        assertThat(current().startedAt()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    void implicitSurplusWaitsForQuiescenceAcknowledgementWithoutExplicitPolicy() {
        initialize();
        var entry = new AetherValue.TopologyEntry("pool", "worker", 0);
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(CONFIG.substring(0, CONFIG.indexOf("[community.stable]"))),
                "test", "1.0.0", List.of(entry), 3, 3, "forge", 2, 0)));
        assertThat(reconciler.requestRetirement(OLD, entry).await().isSuccess()).isTrue();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
        assertThat(effects).isEmpty();
        reconciler.reconcile().await();
        reconciler.reconcile().await();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_REQUESTED);
        assertThat(effects).doesNotContain("terminate");
        assertThat(reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isTrue();
        reconciler.reconcile().await();
        reconciler.reconcile().await();
        assertThat(effects).contains("terminate");
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CapacityControlledLifecycle.AdmissionFailure.class)
    void preDispatchRefusalRetriesTheSameIdentity(CapacityControlledLifecycle.AdmissionFailure refusal) {
        initialize();
        reconciler.reconcile().await().unwrap();
        var target = current().targetNode();
        createOutcome = refusal.promise();
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.RESERVED);
        assertThat(current().targetNode()).isEqualTo(target);
        createOutcome = Promise.unitPromise();
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
        assertThat(current().targetNode()).isEqualTo(target);
    }

    @Test
    void readyDestinationCannotDrainBeforeEvacuationAndDelayedProofEscalates() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        safeToRetire = false;
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
        assertThat(effects).containsExactly("create");
        var before = current();
        var aged = new CommunityPlacementOperationValue(before.operationId(), before.communityId(), before.targetNode(),
            before.targetSource(), before.targetZone(), before.sourceBinding(), before.previousNode(), before.previousSource(),
            before.phase(), before.issuer(), before.startedAt(), 0, "");
        var key = new AetherKey.CommunityPlacementOperationKey("stable");
        seed(new KVCommand.LeaderTransaction<>(key, "age", before.issuer(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(aged)))));
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.READINESS_DELAYED);
        assertThat(escalations).hasSize(1);
        assertThat(effects).containsExactly("create");
        safeToRetire = true;
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create", "drain");
    }

    /// F3 (s25): a replacement that misses its readiness deadline is DELAYED, not BLOCKED: the reservation and its identity are retained,
    /// the operation escalates once, and no second create is issued.
    @Test
    void readinessDeadlineMissed_delaysTheMovementAndKeepsItsReservation() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        var before = current();
        assertThat(before.phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
        var aged = new CommunityPlacementOperationValue(before.operationId(), before.communityId(), before.targetNode(),
            before.targetSource(), before.targetZone(), before.sourceBinding(), before.previousNode(), before.previousSource(),
            before.phase(), before.issuer(), before.startedAt(), 0, "");
        var key = new AetherKey.CommunityPlacementOperationKey("stable");
        seed(new KVCommand.LeaderTransaction<>(key, "age", before.issuer(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(aged)))));

        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.READINESS_DELAYED);
        assertThat(current().targetNode()).isEqualTo(before.targetNode());
        assertThat(escalations).hasSize(1);
        assertThat(effects).containsExactly("create");
    }

    @Test
    void delayedReadinessResumesWithoutReplacingTheReservedIdentity() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        var before = current();
        var delayed = before.withPhase(PlacementOperationPhase.READINESS_DELAYED, before.issuer(), "readiness delayed");
        var key = new AetherKey.CommunityPlacementOperationKey("stable");
        seed(new KVCommand.LeaderTransaction<>(key, "delay", before.issuer(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(delayed)))));
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.READINESS_DELAYED);
        replacementReady();
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_REQUESTED);
        assertThat(current().targetNode()).isEqualTo(before.targetNode());
        assertThat(effects).containsExactly("create", "drain");
    }

    @Test
    void implicitSurplusCannotCommitAcrossCapacityIntentChange() {
        initialize();
        var entry = new AetherValue.TopologyEntry("pool", "worker", 0);
        var toml = CONFIG.substring(0, CONFIG.indexOf("[community.stable]"));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(entry), 3, 3, "forge", 2, 0)));
        beforeCommit = () -> seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0",
                List.of(new AetherValue.TopologyEntry("pool", "worker", 1)), 3, 3, "forge", 3, 0)));
        assertThat(reconciler.requestRetirement(OLD, entry).await().isFailure()).isTrue();
        assertThat(store.getTyped(new AetherKey.CommunityPlacementOperationKey("stable"), CommunityPlacementOperationValue.class).isEmpty()).isTrue();
        assertThat(effects).isEmpty();
    }

    private CommunityPlacementOperationValue current() {
        return store.getTyped(new AetherKey.CommunityPlacementOperationKey("stable"), CommunityPlacementOperationValue.class).unwrap();
    }

    private void replacementReady() {
        var target = current().targetNode();
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(target), new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(target), new AetherValue.NodePlacementValue("pool", Option.some("new"), "new-instance")));
        ready.add(target);
    }

    @Test
    void movement_ordersReservationCreateReadinessDrainAcknowledgementAndConfirmedAbsence() {
        initialize();
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.RESERVED);
        assertThat(effects).isEmpty();
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create");
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create");
        replacementReady();
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create", "drain");
        assertThat(reconciler.onDrainCompleted(new NodeId("wrong"), current().operationId()).await().unwrap()).isFalse();
        assertThat(reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isTrue();
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create", "drain", "terminate");
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.TERMINATING);
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        assertThat(store.get(new AetherKey.ActivationDirectiveKey(OLD)).isEmpty()).isTrue();
    }

    private static final AetherValue.NodeDepartureValue DEPARTED = new AetherValue.NodeDepartureValue(AetherValue.DepartureState.DEPARTED,
                                                                                                      1L,
                                                                                                      new AetherValue.ProcessIncarnation(1L, 7L),
                                                                                                      Option.some(AetherValue.DepartureCause.NODE_REMOVED),
                                                                                                      1L);

    private void seedRecord(NodeId node, AetherValue.NodeDepartureValue record) {
        var key = new AetherKey.NodeDepartureKey(node);

        seed(new KVCommand.LeaderTransaction<>(key,
                                               "record-" + record.epoch(),
                                               new LeaderValue(CORE, 1),
                                               List.of(),
                                               List.of(new KVCommand.Mutation<>(key, store.get(key), Option.some(record)))));
    }

    /// Up to the step whose commit is COMPLETE, the drained OLD holding its directive, its placement and a departure record.
    private void movedUpToTheCompletingStep() {
        initialize();
        seedRecord(OLD, DEPARTED);
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        reconciler.reconcile().await().unwrap();
        reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap();
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.TERMINATING);
    }

    private List<Boolean> identityHeld(NodeId node) {
        return List.of(store.get(new AetherKey.ActivationDirectiveKey(node)).isPresent(),
                       store.get(new AetherKey.NodePlacementKey(node)).isPresent(),
                       store.get(new AetherKey.NodeDepartureKey(node)).isPresent());
    }

    /// Phase-3 design 12 item 4b (CPR COMPLETE): the directive, the placement and the departure record leave in the COMPLETE transaction, all three.
    @Test
    void complete_removesTheDirectiveThePlacementAndTheDepartureRecord_together() {
        movedUpToTheCompletingStep();

        reconciler.reconcile().await().unwrap();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        assertThat(identityHeld(OLD)).as("directive, placement, record").containsExactly(false, false, false);
        assertThat(((KVCommand.LeaderTransaction<?, ?>) committed.getLast().getFirst()).mutations()
                                                                          .stream()
                                                                          .map(mutation -> (Object) mutation.key())
                                                                          .toList()).as("the record leaves first: the cluster FSM never sees a directive removed under it")
                                                                                    .containsSubsequence(new AetherKey.NodeDepartureKey(OLD),
                                                                                                         new AetherKey.ActivationDirectiveKey(OLD));
    }

    /// The record moved between the read and the commit: the COMPLETE transaction is refused whole, so none of the three leaves.
    @Test
    void complete_whoseDepartureRecordMoved_isRefusedWhole_andAllThreeStay() {
        movedUpToTheCompletingStep();
        beforeCommit = () -> seedRecord(OLD, new AetherValue.NodeDepartureValue(AetherValue.DepartureState.ADMITTED,
                                                                                2L,
                                                                                new AetherValue.ProcessIncarnation(2L, 8L),
                                                                                Option.none(),
                                                                                2L));

        reconciler.reconcile().await();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.TERMINATING);
        assertThat(identityHeld(OLD)).as("directive, placement, record").containsExactly(true, true, true);
    }

    @Test
    void destinationLosesReadinessAfterDrainAcknowledgement_doesNotTerminateUntilRecovered() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        reconciler.reconcile().await().unwrap();
        assertThat(reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isTrue();
        ready.remove(current().targetNode());
        controller(10, 60_000).reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.TERMINATING);
        assertThat(effects).containsExactly("create", "drain");
        ready.add(current().targetNode());
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create", "drain", "terminate");
    }

    @Test
    void retirementProofChangesAfterDrainAcknowledgement_doesNotTerminate() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        reconciler.reconcile().await().unwrap();
        assertThat(reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isTrue();
        safeToRetire = false;
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create", "drain");
        safeToRetire = true;
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create", "drain", "terminate");
    }

    @Test
    void newLeaderAdoptsDrainBeforeRetransmission_andOldLeaderCannotAcceptCompletion() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        reconciler.reconcile().await().unwrap();
        var successor = new NodeId("next-core");
        var authority = new LeaderValue(successor, 2);
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, authority));
        var replacement = controller(successor, 10, 60_000);
        replacement.reconcile().await().unwrap();
        assertThat(current().issuer()).isEqualTo(authority);
        assertThat(effects).containsExactly("create", "drain", "drain");
        assertThat(reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isFalse();
        assertThat(replacement.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isTrue();
        ready.remove(current().targetNode());
        replacement.reconcile().await().unwrap();
        assertThat(effects).doesNotContain("terminate");
        ready.add(current().targetNode());
        replacement.reconcile().await().unwrap();
        assertThat(effects).containsExactly("create", "drain", "drain", "terminate");
    }

    @Test
    void leaderChangesBeforeCreateCommit_noProviderEffectEscapesRejectedTransaction() {
        initialize();
        reconciler.reconcile().await().unwrap();
        beforeCommit = () -> seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(new NodeId("next-core"), 2)));
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.RESERVED);
        assertThat(effects).isEmpty();
    }

    @Test
    void leaderChangesDuringCreate_lateCompletionCannotAdvanceAndSuccessorDoesNotDuplicateCreate() {
        initialize();
        reconciler.reconcile().await().unwrap();
        createOutcome = Promise.promise();
        var outstanding = reconciler.reconcile();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.CREATE_REQUESTED);
        var successor = new NodeId("next-core");
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(successor, 2)));
        createOutcome.succeed(Unit.unit());
        outstanding.await().unwrap();
        var replacement = controller(successor, 10, 60_000);
        replacement.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.CREATE_UNCERTAIN);
        assertThat(effects).containsExactly("create");
        replacementReady();
        replacement.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
        assertThat(effects).containsExactly("create");
    }

    @Test
    void failedLeaderCreateOutcome_doesNotDispatchAnotherCreate() {
        initialize();
        reconciler.reconcile().await().unwrap();
        var reserved = current();
        seed(new KVCommand.LeaderTransaction<>(new AetherKey.CommunityPlacementOperationKey("stable"), java.util.UUID.randomUUID().toString(), reserved.issuer(), List.of(),
            List.of(new KVCommand.Mutation<>(new AetherKey.CommunityPlacementOperationKey("stable"), Option.some(reserved),
                Option.some(reserved.withPhase(PlacementOperationPhase.CREATE_REQUESTED, reserved.issuer(), "", clock().getAsLong()))))));
        controller(10, 60_000).reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.CREATE_UNCERTAIN);
        assertThat(effects).isEmpty();
        assertThat(escalations).hasSize(1);
    }

    @Test
    void capacityReservation_deniesOverlapBeforeProviderEffect() {
        initialize();
        controller(2, 60_000).reconcile().await().unwrap();
        assertThat(store.get(new AetherKey.CommunityPlacementOperationKey("stable")).isEmpty()).isTrue();
        assertThat(effects).isEmpty();
    }
    @Test
    void zeroTarget_retiresWithoutReplacementAndDissolvesOnlyAfterConfirmedAbsence() {
        initialize();
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(CONFIG.replace("target_size = 1", "target_size = 0")),
                "test", "1.0.0", List.of(), 3, 3, "forge", 2, 0)));
        reconciler.reconcile().await().unwrap();
        assertThat(current().targetNode()).isEqualTo(OLD);
        assertThat(effects).isEmpty();
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("drain");
        assertThat(reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isTrue();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        assertThat(effects).containsExactly("drain", "terminate");
        assertThat(store.getTyped(new AetherKey.CommunityKey("stable"), AetherValue.CommunityValue.class).unwrap().state())
            .isEqualTo(org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED);
    }

    @Test
    void expiredDrain_remainsUncertainUntilLateMatchingAcknowledgement() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        reconciler.reconcile().await().unwrap();
        var draining = current();
        var expired = new CommunityPlacementOperationValue(draining.operationId(), draining.communityId(),
            draining.targetNode(), draining.targetSource(), draining.targetZone(), draining.sourceBinding(),
            draining.previousNode(), draining.previousSource(), PlacementOperationPhase.DRAIN_REQUESTED,
            draining.issuer(), draining.startedAt(), 0, "");
        seed(new KVCommand.LeaderTransaction<>(new AetherKey.CommunityPlacementOperationKey("stable"), java.util.UUID.randomUUID().toString(), draining.issuer(), List.of(),
            List.of(new KVCommand.Mutation<>(new AetherKey.CommunityPlacementOperationKey("stable"), Option.some(draining), Option.some(expired)))));
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_UNCERTAIN);
        assertThat(effects).doesNotContain("terminate");
        assertThat(reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap()).isTrue();
        reconciler.reconcile().await().unwrap();
        assertThat(effects).contains("terminate");
    }

    /// #1535 item 7: the drain deadline reads the injected clock, not the wall clock, so it can be stepped exactly.
    @Test
    void drainDeadline_followsTheInjectedClock() {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        var clocked = controller(CORE, 10, 1_000, now::get);

        initialize();
        clocked.reconcile().await().unwrap();
        clocked.reconcile().await().unwrap();
        replacementReady();
        clocked.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_REQUESTED);
        now.addAndGet(900);
        clocked.reconcile().await().unwrap();
        assertThat(current().phase()).as("inside the 1000 ms drain window").isEqualTo(PlacementOperationPhase.DRAIN_REQUESTED);
        now.addAndGet(200);
        clocked.reconcile().await().unwrap();
        assertThat(current().phase()).as("past the window by the injected clock alone").isEqualTo(PlacementOperationPhase.DRAIN_UNCERTAIN);
    }

    /// The retirement-safety delay reads the injected clock too: a readiness wait inside its window stays AWAITING_READY, and only the
    /// injected clock passing the window delays it. Read from the wall clock, the first pass would already be far past the window.
    @Test
    void retirementDelay_followsTheInjectedClock() {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        var clocked = controller(CORE, 10, 1_000, now::get);

        initialize();
        clocked.reconcile().await().unwrap();
        clocked.reconcile().await().unwrap();
        replacementReady();
        safeToRetire = false;
        clocked.reconcile().await().unwrap();
        assertThat(current().phase()).as("evacuation unproven, window not yet passed").isEqualTo(PlacementOperationPhase.AWAITING_READY);
        now.addAndGet(900);
        clocked.reconcile().await().unwrap();
        assertThat(current().phase()).as("inside the 1000 ms window").isEqualTo(PlacementOperationPhase.AWAITING_READY);
        now.addAndGet(200);
        clocked.reconcile().await().unwrap();
        assertThat(current().phase()).as("past the window by the injected clock alone").isEqualTo(PlacementOperationPhase.READINESS_DELAYED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PlacementOperationPhase.class,
        names = {"RESERVED", "CREATE_REQUESTED", "CREATE_UNCERTAIN", "AWAITING_READY", "DRAIN_REQUESTED",
                 "DRAIN_UNCERTAIN", "DRAINED", "TERMINATING", "BLOCKED", "READINESS_DELAYED"})
    void freshLeaderResumesEveryPersistedWindowWithoutUnsafeProviderEffects(PlacementOperationPhase phase) {
        initialize();
        reconciler.reconcile().await().unwrap();
        var reserved = current();
        var interrupted = reserved.withPhase(phase, reserved.issuer(), "interrupted", clock().getAsLong());
        var key = new AetherKey.CommunityPlacementOperationKey("stable");
        seed(new KVCommand.LeaderTransaction<>(key, java.util.UUID.randomUUID().toString(), reserved.issuer(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(reserved), Option.some(interrupted)))));
        var successor = new NodeId("restarted-core");
        var authority = new LeaderValue(successor, 2);
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, authority));
        reconciler.reconcile().await().unwrap();
        assertThat(effects).as("stale controller at %s", phase).isEmpty();
        var restarted = controller(successor, 10, 60_000);
        restarted.reconcile().await().unwrap();
        assertThat(current().issuer()).isEqualTo(authority);
        assertThat(effects).doesNotContain("terminate", "drain");
        assertThat(effects.stream().filter("create"::equals).count()).isEqualTo(phase == PlacementOperationPhase.RESERVED ? 1 : 0);
        var target = current().targetNode();
        replacementReady();
        controller(successor, 10, 60_000).reconcile().await().unwrap();
        if (phase == PlacementOperationPhase.DRAINED || phase == PlacementOperationPhase.TERMINATING) {
            assertThat(effects).containsExactly("terminate");
            controller(successor, 10, 60_000).reconcile().await().unwrap();
            assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
            assertThat(effects).containsExactly("terminate");
        } else {
            assertThat(effects).doesNotContain("terminate");
        }
        assertThat(effects.stream().filter("create"::equals).count()).isEqualTo(phase == PlacementOperationPhase.RESERVED ? 1 : 0);
        assertThat(current().targetNode()).isEqualTo(target);
        assertThat(providerOperations).allSatisfy(operation -> {
            assertThat(operation.targetSource()).isEqualTo("pool");
            assertThat(operation.sourceBinding()).isEqualTo("binding");
            assertThat(operation.previousSource()).isEqualTo(reserved.previousSource());
            assertThat(operation.operationId()).isEqualTo(reserved.operationId());
        });
    }

    /// D3 (owner, 2026-10-18): DISSOLVED stays the terminal fact `CommunityState` documents, and the gate the operator waits on before
    /// removing a placement policy. Raising the target of a dissolved community does not reopen it; who owns the worker lifecycle of
    /// such a community is decided in deployment-FSM phase 3, against C9 and C11.
    @Test
    void positiveTargetDoesNotReopenADissolvedCommunity() {
        initialize();
        seed(new KVCommand.Remove<>(new AetherKey.ActivationDirectiveKey(OLD)));
        seed(new KVCommand.Remove<>(new AetherKey.NodePlacementKey(OLD)));
        var dissolved = new AetherValue.CommunityValue("pool", "worker", 0,
            org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED, 1, Option.some(2L));
        seed(new KVCommand.Put<>(new AetherKey.CommunityKey("stable"), dissolved));
        reconciler.reconcile().await().unwrap();
        assertThat(store.getTyped(new AetherKey.CommunityKey("stable"), AetherValue.CommunityValue.class).unwrap())
            .isEqualTo(dissolved);
        assertThat(store.get(new AetherKey.CommunityPlacementOperationKey("stable")).isEmpty()).isTrue();
    }

    @Test
    void restartCannotRebindRecordedCreateToChangedProviderIdentity() {
        initialize();
        reconciler.reconcile().await().unwrap();
        var reserved = current();
        binding = "different-provider-account";
        controller(10, 60_000).reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.RESERVED);
        assertThat(current().sourceBinding()).isEqualTo(reserved.sourceBinding());
        assertThat(effects).isEmpty();
        controller(10, 60_000).reconcile().await().unwrap();
        controller(10, 60_000).reconcile().await().unwrap();
        assertThat(escalations).as("the binding outage escalates once, not once per pass").hasSize(1);
        binding = "binding";
        controller(10, 60_000).reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
        assertThat(effects).containsExactly("create");
    }

    @Test
    void restartAfterProviderTerminationConfirmsAbsenceWithoutDuplicateEffect() {
        initialize();
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        replacementReady();
        reconciler.reconcile().await().unwrap();
        reconciler.onDrainCompleted(OLD, current().operationId()).await().unwrap();
        reconciler.reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.TERMINATING);
        assertThat(effects).containsExactly("create", "drain", "terminate");
        controller(10, 60_000).reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        assertThat(effects).containsExactly("create", "drain", "terminate");
    }

}
