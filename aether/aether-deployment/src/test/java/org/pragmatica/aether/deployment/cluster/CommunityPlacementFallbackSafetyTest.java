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

class CommunityPlacementFallbackSafetyTest {
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
        [source.backup]
        type = "forge"
        zones = ["alternate"]
        [source.backup.worker]
        count = 1
        [community.stable]
        target_size = 1
        [community.stable.placement.destination]
        source = "pool"
        zone = "new"
        weight = 10
        [community.stable.placement.fallback]
        source = "backup"
        zone = "alternate"
        weight = 1
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

    private CommunityPlacementOperationValue current() {
        return store.getTyped(new AetherKey.CommunityPlacementOperationKey("stable"), CommunityPlacementOperationValue.class).unwrap();
    }

    private void createRefused() {
        initialize();
        reconciler.reconcile().await().unwrap();
        createOutcome = org.pragmatica.aether.environment.EnvironmentError.capacityUnavailable("new",
            new IllegalStateException("provider confirms no allocation")).promise();
        reconciler.reconcile().await().unwrap();
        assertThat(providerOperations).hasSize(1);
        assertThat(providerOperations.getFirst().targetSource()).isEqualTo("pool");
    }

    @Test
    void restartAfterExplicitRefusalUsesOnlyAllowedAlternateWithoutDuplicatingUnknownCreate() {
        createRefused();
        var refused = providerOperations.getFirst();
        createOutcome = Promise.unitPromise();
        var successor = new NodeId("successor");
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(successor, 2)));
        for (int pass = 0; pass < 4 && providerOperations.size() < 2; pass++) {
            controller(successor, 10, 60_000).reconcile().await().unwrap();
        }
        assertThat(providerOperations).hasSize(2);
        var fallback = providerOperations.getLast();
        assertThat(fallback.targetSource()).isEqualTo("backup");
        assertThat(fallback.targetZone()).isEqualTo(Option.some("alternate"));
        assertThat(fallback.targetNode()).isNotEqualTo(refused.targetNode());
        assertThat(fallback.previousNode()).isEqualTo(refused.previousNode());
        assertThat(effects).doesNotContain("drain", "terminate");
    }

    @Test
    void ambiguousCreateNeverFallsBackAcrossRestart() {
        initialize();
        reconciler.reconcile().await().unwrap();
        createOutcome = org.pragmatica.lang.utils.Causes.cause("provider timed out; allocation unknown").promise();
        reconciler.reconcile().await().unwrap();
        var identity = current().targetNode();
        createOutcome = Promise.unitPromise();
        for (int pass = 0; pass < 4; pass++) {
            controller(10, 60_000).reconcile().await().unwrap();
        }
        assertThat(providerOperations).hasSize(1);
        assertThat(current().targetNode()).isEqualTo(identity);
        assertThat(effects).containsExactly("create");
    }

    @Test
    void exhaustedSharedCapacityCannotDispatchAlternateAfterKnownRefusal() {
        createRefused();
        createOutcome = Promise.unitPromise();
        for (int pass = 0; pass < 4; pass++) {
            controller(2, 60_000).reconcile().await().unwrap();
        }
        assertThat(providerOperations).hasSize(1);
        assertThat(effects).containsExactly("create");
    }
    @Test
    void unavailableLocationMinimumCannotMoveToFallbackSource() {
        initialize();
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(CONFIG.replace("weight = 10", "weight = 10\nminimum = 1"),
                "test", "1.0.0", List.of(), 3, 3, "forge", 2, 0)));
        reconciler.reconcile().await().unwrap();
        createOutcome = org.pragmatica.aether.environment.EnvironmentError.capacityUnavailable("new",
            new IllegalStateException("provider confirms no allocation")).promise();
        reconciler.reconcile().await().unwrap();
        createOutcome = Promise.unitPromise();
        for (int pass = 0; pass < 4; pass++) {
            controller(10, 60_000).reconcile().await().unwrap();
        }
        assertThat(providerOperations).hasSize(1);
        assertThat(providerOperations).allSatisfy(operation -> assertThat(operation.targetSource()).isEqualTo("pool"));
        assertThat(effects).doesNotContain("drain", "terminate");
    }

    @Test
    void staleLeaderRefusalCallbackCannotAuthorizeFallback() {
        initialize();
        reconciler.reconcile().await().unwrap();
        createOutcome = Promise.promise();
        var inFlight = reconciler.reconcile();
        var successor = new NodeId("successor");
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(successor, 2)));
        createOutcome.fail(org.pragmatica.aether.environment.EnvironmentError.capacityUnavailable("new",
            new IllegalStateException("late explicit refusal")));
        inFlight.await().unwrap();
        createOutcome = Promise.unitPromise();
        for (int pass = 0; pass < 4; pass++) {
            controller(successor, 10, 60_000).reconcile().await().unwrap();
        }
        assertThat(providerOperations).hasSize(1);
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.CREATE_UNCERTAIN);
        assertThat(effects).containsExactly("create");
    }

}
