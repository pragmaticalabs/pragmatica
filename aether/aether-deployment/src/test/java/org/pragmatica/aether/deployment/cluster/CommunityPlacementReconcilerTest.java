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
    private boolean oldExists = true;
    private boolean safeToRetire = true;
    private Promise<Unit> createOutcome = Promise.unitPromise();
    private Runnable beforeCommit = () -> {};
    private final CommunityPlacementReconciler.Actuator actuator = new CommunityPlacementReconciler.Actuator() {
        @Override public org.pragmatica.lang.Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source) { return org.pragmatica.lang.Result.success(binding); }
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
    private final CommunityPlacementReconciler reconciler = controller(10, 60_000);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private CommunityPlacementReconciler controller(int limit, long timeout) {
        return controller(CORE, limit, timeout);
    }

    private CommunityPlacementReconciler controller(NodeId self, int limit, long timeout) {
        return CommunityPlacementReconciler.communityPlacementReconciler(self, store,
            commands -> {
                var hook = beforeCommit;
                beforeCommit = () -> {};
                hook.run();
                return Promise.success(store.process(store.createBatch(commands)));
            }, () -> true,
            ready::contains, () -> Set.of(CORE, OLD), () -> limit, actuator, escalations::add, (node, cause) -> retirementRefusals.add(node), org.pragmatica.lang.io.TimeSpan.timeSpan(timeout).millis());
    }

    private void initialize() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(CONFIG, "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(OLD), new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(OLD), new AetherValue.NodePlacementValue("pool", Option.some("old"), "old-instance")));
        ready.add(OLD);
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

    @Test
    void implicitSurplusCannotBypassNormalizedCommunityRetirement() {
        initialize();
        var entry = new AetherValue.TopologyEntry("pool", "worker", 0);
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(CONFIG.substring(0, CONFIG.indexOf("[community.stable]")),
                "test", "1.0.0", List.of(entry), 3, 3, "forge", 2, 0)));
        assertThat(reconciler.requestRetirement(OLD, entry).await().isFailure()).isTrue();
        assertThat(effects).isEmpty();
        assertThat(retirementRefusals).containsExactly(OLD);
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
            new AetherValue.ClusterConfigValue(toml, "test", "1.0.0", List.of(entry), 3, 3, "forge", 2, 0)));
        beforeCommit = () -> seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(toml, "test", "1.0.0",
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
                Option.some(reserved.withPhase(PlacementOperationPhase.CREATE_REQUESTED, reserved.issuer(), ""))))));
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
            new AetherValue.ClusterConfigValue(CONFIG.replace("target_size = 1", "target_size = 0"),
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PlacementOperationPhase.class,
        names = {"RESERVED", "CREATE_REQUESTED", "CREATE_UNCERTAIN", "AWAITING_READY", "DRAIN_REQUESTED",
                 "DRAIN_UNCERTAIN", "DRAINED", "TERMINATING", "BLOCKED", "READINESS_DELAYED"})
    void freshLeaderResumesEveryPersistedWindowWithoutUnsafeProviderEffects(PlacementOperationPhase phase) {
        initialize();
        reconciler.reconcile().await().unwrap();
        var reserved = current();
        var interrupted = reserved.withPhase(phase, reserved.issuer(), "interrupted");
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

    @Test
    void positiveTargetReopensAnEmptyDissolvedCommunity() {
        initialize();
        seed(new KVCommand.Remove<>(new AetherKey.ActivationDirectiveKey(OLD)));
        seed(new KVCommand.Remove<>(new AetherKey.NodePlacementKey(OLD)));
        seed(new KVCommand.Put<>(new AetherKey.CommunityKey("stable"),
            new AetherValue.CommunityValue("pool", "worker", 0,
                org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED, 1, Option.some(2L))));
        reconciler.reconcile().await().unwrap();
        assertThat(store.getTyped(new AetherKey.CommunityKey("stable"), AetherValue.CommunityValue.class)
            .unwrap().state()).isEqualTo(org.pragmatica.aether.slice.kvstore.CommunityState.FORMING);
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.RESERVED);
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
