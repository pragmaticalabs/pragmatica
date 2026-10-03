// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;

/// #1812: a cluster that has only the bootstrap seed (no `cluster apply` yet: Forge, self-bootstrapped
/// clusters) must provision through its explicitly local provider. The seed used to be `tomlContent = ""`,
/// and the bound-source path (binding, fleet inventory, bound resolution) parsed it unguarded, failed with
/// "Persisted config has no config_version" and tripped the provisioning breaker, so auto-heal and scale-up
/// were dead on every seed-only cluster. These tests compose the REAL chain the node wires
/// ([CapacityControlledLifecycle] over [NodeLifecycleManager#nodeLifecycleManager(SourceComputeRegistry, java.util.function.Function, Option, Option)]
/// over [SourceComputeRegistry]); only the compute provider is a fake.
class BootstrapSeedProvisioningTest {
    private static final NodeId CORE = new NodeId("core");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final String CLUSTER = "seeded";

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Promise<List<Object>> process(List<KVCommand<AetherKey>> commands) { return Promise.success(store.process(store.createBatch(commands))); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private void seedBootstrapOnly() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 AetherValue.ClusterConfigValue.bootstrapSeed(CLUSTER,
                                                                              "1.0.0",
                                                                              List.of(new AetherValue.TopologyEntry("", AetherValue.TopologyEntry.CORE_ROLE, 5)),
                                                                              3,
                                                                              9,
                                                                              "bootstrap-seed",
                                                                              1L)));
    }

    private NodeLifecycleManager lifecycle(Option<ComputeProvider> local) {
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> store.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                        AetherValue.ClusterConfigValue.class),
                                                                   local);
        var sourced = NodeLifecycleManager.nodeLifecycleManager(registry,
                                                                node -> EnvironmentError.operationNotSupported("unused").result(),
                                                                Option.none(),
                                                                Option.none());

        return CapacityControlledLifecycle.capacityControlledLifecycle(sourced, CORE, store, this::process, () -> true, () -> 10);
    }

    private static ProvisionSpec spec(SourceName source) {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                           "cx23",
                                           "core",
                                           ProvisionContext.forBootstrap(clusterName(CLUSTER).unwrap(), "core", source, "replacement-1"))
                            .unwrap();
    }

    @Test
    void provisionNode_bootstrapSeedWithLocalProvider_reachesTheProviderAndCompletesInventory() {
        seedBootstrapOnly();
        var provider = new FakeProvider();

        var result = lifecycle(Option.some(provider)).provisionNode(spec(SourceName.DEFAULT)).await();

        result.onFailure(cause -> org.assertj.core.api.Assertions.fail("seed-only provisioning refused: " + cause.message()));
        assertThat(provider.creates.get()).as("the local provider's create ran exactly once").isEqualTo(1);
        assertThat(provider.lists.get()).as("the seed's implicit default source was inventoried").isPositive();
        var ledger = store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, AetherValue.CapacityLedgerValue.class).unwrap();
        assertThat(ledger.inventoryComplete()).isTrue();
        assertThat(ledger.allocated()).isEqualTo(1);
    }

    @Test
    void provisionNode_bootstrapSeedWithoutLocalProvider_refusesWithoutParsingTheSeed() {
        seedBootstrapOnly();

        var result = lifecycle(Option.none()).provisionNode(spec(SourceName.DEFAULT)).await();

        assertThat(result.isFailure()).as("no committed sources and no local provider: nothing may provision").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).doesNotContain("config_version")
                                                             .contains("committed cluster configuration absent"));
    }

    @Test
    void provisionNode_bootstrapSeed_namedSourceOtherThanDefault_isRefusedBeforeAnyCreate() {
        seedBootstrapOnly();
        var provider = new FakeProvider();

        var result = lifecycle(Option.some(provider)).provisionNode(spec(SourceName.sourceName("hetzner-eu").unwrap())).await();

        assertThat(result.isFailure()).as("an undeclared source never falls back to the local provider").isTrue();
        assertThat(provider.creates.get()).isZero();
    }

    @Test
    void resolveBound_bootstrapSeed_rejectsABindingThatIsNotTheLocalOne() {
        seedBootstrapOnly();
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> store.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                        AetherValue.ClusterConfigValue.class),
                                                                   Option.some(new FakeProvider()));
        var local = registry.binding(SourceName.DEFAULT).unwrap();

        assertThat(registry.resolve(SourceName.DEFAULT, local).isSuccess()).isTrue();
        assertThat(registry.resolve(SourceName.DEFAULT, "some-cloud-account-binding").isFailure()).isTrue();
    }

    /// The seed's local binding is persisted in capacity reservations, so it must have the same shape as every
    /// other binding (an opaque SHA-256 hex digest, never a credential and never empty), be the same value from a
    /// fresh registry (a restarted leader must recognise its own reservations), and differ from the binding the
    /// same `default` source gets once an operator commits a configuration declaring it — so a reservation made
    /// before the first `cluster apply` is seen as a different account afterwards rather than silently re-bound.
    @Test
    void binding_bootstrapSeed_isAStableOpaqueDigest_distinctFromTheDeclaredDefaultSource() {
        seedBootstrapOnly();
        var local = Option.<ComputeProvider>some(new FakeProvider());
        var seeded = SourceComputeRegistry.sourceComputeRegistry(() -> store.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                      AetherValue.ClusterConfigValue.class),
                                                                 local);
        var restarted = SourceComputeRegistry.sourceComputeRegistry(() -> store.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                         AetherValue.ClusterConfigValue.class),
                                                                    local);
        var committed = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(AetherValue.ClusterConfigValue.clusterConfigValue(DECLARED_DEFAULT,
                                                                                                                                         CLUSTER,
                                                                                                                                         "1.0.0",
                                                                                                                                         List.of(),
                                                                                                                                         3,
                                                                                                                                         9,
                                                                                                                                         "forge",
                                                                                                                                         2L)),
                                                                    local);

        var seedBinding = seeded.binding(SourceName.DEFAULT).unwrap();

        assertThat(seedBinding).as("an opaque SHA-256 hex digest, like every persisted binding").matches("[0-9a-f]{64}");
        assertThat(restarted.binding(SourceName.DEFAULT).unwrap()).as("stable across registry instances").isEqualTo(seedBinding);
        assertThat(committed.binding(SourceName.DEFAULT).unwrap()).as("the declared default source is a different account")
                                                                  .matches("[0-9a-f]{64}")
                                                                  .isNotEqualTo(seedBinding);
    }

    private static final String DECLARED_DEFAULT = """
        config_version = "1.0.0"
        [cluster]
        name = "seeded"
        version = "1.0.0"
        [source.default]
        type = "forge"
        [source.default.core]
        count = 3
        """;

    private static final class FakeProvider implements ComputeProvider {
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger lists = new AtomicInteger();

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            lists.incrementAndGet();

            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            creates.incrementAndGet();

            return Promise.success(new InstanceInfo(InstanceId.instanceId("local-1").unwrap(),
                                                    InstanceStatus.RUNNING,
                                                    List.of(),
                                                    InstanceType.ON_DEMAND,
                                                    Map.of(),
                                                    Option.some("replacement-1"),
                                                    Option.none()));
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return EnvironmentError.instanceNotFound(instanceId).promise();
        }
    }
}
