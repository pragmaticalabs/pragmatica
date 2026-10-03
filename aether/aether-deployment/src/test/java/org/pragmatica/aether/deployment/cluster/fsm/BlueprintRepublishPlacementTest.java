// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #983 — `handleAppBlueprintChange` rebuilt the whole `SliceTargetValue` from the blueprint's
/// `ResolvedSlice` on every republish, through a `sliceTargetValue(...)` factory that fixes
/// `placement = CORE_ONLY` and takes the declared instance count. Two components were lost:
///
/// - `placement`. The blueprint has no placement key (`known-limitations.md`: "the blueprint has no
///   placement key"; `ResolvedSlice` has none), so the committed value is the only source. Carried.
/// - `targetInstances`. The autoscaler's current count is carried (owner ruling: otherwise a
///   redeploy can overload the slice), clamped into the NEW blueprint's `[minAvailable, maxInstances]`
///   (the CTO's reading of that ruling); the declared count applies only to a first deploy. The
///   rollout allocates from this value (`handleSliceTargetChange` reads `value.targetInstances()`),
///   so a version change rolls at the carried scale.
///
/// The remaining components (`currentVersion`, `minInstances`, owner, `maxInstances`, both
/// thresholds) are declared by `ResolvedSlice` and follow the blueprint; `updatedAt` is a clock.
class BlueprintRepublishPlacementTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:orders-api:1.0.0").unwrap();
    private static final Artifact V2 = Artifact.artifact("org.example:orders-api:2.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(V1.base());

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    private void newHarness() {
        newHarness(RestoreOrder.BLUEPRINT_FIRST, _ -> {});
    }

    /// The order a new leader's `rebuildStateFromKVStore` visits the entries in. The real store iterates
    /// `Map.copyOf(storage)`, whose order is salted per JVM, so both orders occur in production.
    enum RestoreOrder {
        BLUEPRINT_FIRST,
        BLUEPRINT_LAST
    }

    /// Seeds the store BEFORE `Activate`, so the leader activation restores from it.
    private void newHarness(RestoreOrder order, java.util.function.Consumer<InMemoryKvStore> seed) {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router, order);
        seed.accept(kvStore);
        cluster = new RecordingClusterNode(SELF);
        LongSupplier clock = () -> 10_000_000L;

        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, NODE_A),
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set::of,
                                                    Set.of(SELF, NODE_A),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        harness = FsmTestHarness.harness("blueprint-republish-placement-" + System.nanoTime(), factory);
        harness.dispatch(new Activate());
    }

    /// Dispatches the FSM event the KV bridge would raise for a blueprint Put (the harness does not
    /// bridge notifications), then returns the SliceTargetValue the republish proposed.
    private SliceTargetValue republish(ResolvedSlice slice) {
        cluster.commands.clear();

        var key = AppBlueprintKey.appBlueprintKey(OWNER);
        var value = AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER,
                                                                                              List.of(slice),
                                                                                              Option.none()));
        harness.dispatch(new AppBlueprintPutReceived(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none())));

        var proposed = cluster.commands.stream()
                                       .filter(KVCommand.Put.class::isInstance)
                                       .map(KVCommand.Put.class::cast)
                                       .filter(put -> TARGET_KEY.equals(put.key()))
                                       .map(put -> (SliceTargetValue) put.value())
                                       .toList();

        assertThat(proposed).as("the republish must propose exactly one SliceTargetValue Put for the slice")
                            .hasSize(1);

        return proposed.getFirst();
    }

    private int inMemoryInstances(Artifact artifact) {
        return ((ClusterDeploymentState.Active) harness.state()).blueprints().get(artifact).instances();
    }

    private static ResolvedSlice slice(Artifact artifact, int instances, int min) {
        return ResolvedSlice.resolvedSlice(artifact, instances, min, false, Set.of()).unwrap();
    }

    private static ResolvedSlice slice(Artifact artifact,
                                       int instances,
                                       int min,
                                       Option<Integer> max,
                                       Option<Double> up,
                                       Option<Double> down) {
        return ResolvedSlice.resolvedSlice(artifact, instances, min, false, Set.of(), max, up, down).unwrap();
    }

    private void commit(SliceTargetValue value) {
        kvStore.put(TARGET_KEY, value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKERS_ONLY", "WORKERS_PREFERRED", "ALL"})
    void republish_preservesCommittedPlacement(String placement) {
        newHarness();
        commit(SliceTargetValue.sliceTargetValue(V1.version(), 3, 2, placement));

        var proposed = republish(slice(V1, 3, 2));

        assertThat(proposed.effectivePlacement()).isEqualTo(placement);
    }

    @Test
    void republish_withNewVersion_preservesPlacementAndAdoptsTheDeclaredVersion() {
        newHarness();
        commit(SliceTargetValue.sliceTargetValue(V1.version(), 3, 2, "WORKERS_ONLY"));

        var proposed = republish(slice(V2, 3, 2));

        assertThat(proposed.effectivePlacement()).isEqualTo("WORKERS_ONLY");
        assertThat(proposed.currentVersion()).isEqualTo(V2.version());
    }

    /// Opposite polarity: with nothing committed the blueprint has no placement to carry, so the
    /// first deploy takes the default. Without this, a fix that hard-coded a non-default value
    /// would pass the tests above.
    @Test
    void firstDeploy_withNothingCommitted_usesDefaultPlacement() {
        newHarness();

        var proposed = republish(slice(V1, 3, 2));

        assertThat(proposed.effectivePlacement()).isEqualTo("CORE_ONLY");
    }

    /// The components the blueprint declares follow the blueprint on a republish. A fix that kept the
    /// whole committed value would pass the placement tests and fail here.
    @Test
    void republish_replacesTheComponentsTheBlueprintDeclares() {
        newHarness();
        commit(SliceTargetValue.sliceTargetValue(V1.version(), 3, 2, Option.none(), Option.some(9), Option.some(0.9), Option.some(0.1)));

        var proposed = republish(slice(V1, 3, 1, Option.some(7), Option.some(0.8), Option.some(0.2)));

        assertThat(proposed.minInstances()).isEqualTo(1);
        assertThat(proposed.owningBlueprint()).isEqualTo(Option.some(OWNER));
        assertThat(proposed.maxInstances()).isEqualTo(Option.some(7));
        assertThat(proposed.scaleUpThreshold()).isEqualTo(Option.some(0.8));
        assertThat(proposed.scaleDownThreshold()).isEqualTo(Option.some(0.2));
    }

    /// A blueprint that stops declaring an override removes it.
    @Test
    void republish_withoutDeclaredOverrides_clearsCommittedOverrides() {
        newHarness();
        commit(SliceTargetValue.sliceTargetValue(V1.version(), 3, 2, Option.none(), Option.some(9), Option.some(0.9), Option.some(0.1)));

        var proposed = republish(slice(V1, 3, 2));

        assertThat(proposed.maxInstances()).isEqualTo(Option.none());
        assertThat(proposed.scaleUpThreshold()).isEqualTo(Option.none());
        assertThat(proposed.scaleDownThreshold()).isEqualTo(Option.none());
    }

    @Test
    void republish_carriesTheAutoscaledCount_andTheRolloutRegistrationAgrees() {
        newHarness();
        commit(autoscaled(V1, 8, 2, Option.some(12)));

        var proposed = republish(slice(V1, 3, 2, Option.some(12), Option.none(), Option.none()));

        assertThat(proposed.targetInstances()).isEqualTo(8);
        assertThat(inMemoryInstances(V1)).as("the in-memory blueprint the reconciler allocates from").isEqualTo(8);
    }

    @Test
    void republish_carriesTheAutoscaledCount_whenNoMaxIsDeclared() {
        newHarness();
        commit(autoscaled(V1, 8, 2, Option.none()));

        assertThat(republish(slice(V1, 3, 2)).targetInstances()).isEqualTo(8);
    }

    @Test
    void republish_clampsTheCarriedCountDownToTheNewMax() {
        newHarness();
        commit(autoscaled(V1, 8, 2, Option.some(12)));

        var proposed = republish(slice(V1, 3, 2, Option.some(6), Option.none(), Option.none()));

        assertThat(proposed.targetInstances()).isEqualTo(6);
        assertThat(inMemoryInstances(V1)).isEqualTo(6);
    }

    @Test
    void republish_clampsTheCarriedCountUpToTheNewMin() {
        newHarness();
        commit(autoscaled(V1, 2, 1, Option.none()));

        var proposed = republish(slice(V1, 5, 4));

        assertThat(proposed.targetInstances()).isEqualTo(4);
        assertThat(inMemoryInstances(V1)).isEqualTo(4);
    }

    @Test
    void republish_withNewVersion_carriesTheCountSoTheRolloutRunsAtTheCurrentScale() {
        newHarness();
        commit(autoscaled(V1, 6, 2, Option.none()));

        var proposed = republish(slice(V2, 3, 2));

        assertThat(proposed.currentVersion()).isEqualTo(V2.version());
        assertThat(proposed.targetInstances()).isEqualTo(6);
        assertThat(inMemoryInstances(V2)).isEqualTo(6);
    }

    /// Opposite polarity of the carry tests: with nothing committed there is no scale to carry, so the
    /// declared count applies. Without it a fix that always kept some constant would pass.
    @Test
    void firstDeploy_usesTheDeclaredCount() {
        newHarness();

        var proposed = republish(slice(V1, 4, 2));

        assertThat(proposed.targetInstances()).isEqualTo(4);
        assertThat(inMemoryInstances(V1)).isEqualTo(4);
    }

    /// v1858 — leader change. A new leader rebuilds `blueprints` from the store; the reconciler then sizes
    /// the slice from that in-memory count (`reconcileBlueprint` reads `blueprint.instances()`), and
    /// `Active.onEntry` runs `reconcile()` right after the rebuild. `restoreAppBlueprint` registered the
    /// DECLARED count, so whichever of the two restore entries the store visited last decided the scale.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RestoreOrder.class)
    void leaderRestore_keepsTheCommittedAutoscaledCount(RestoreOrder order) {
        newHarness(order, store -> {
            store.put(TARGET_KEY, autoscaled(V1, 8, 2, Option.some(12)));
            store.put(AppBlueprintKey.appBlueprintKey(OWNER), blueprintValue(slice(V1, 3, 2, Option.some(12), Option.none(), Option.none())));
        });

        assertThat(inMemoryInstances(V1)).as("the count the reconciler sizes the slice to after a leader change")
                                         .isEqualTo(8);
    }

    /// Mid-rollout leader change: the republish already committed V2 at the carried scale.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RestoreOrder.class)
    void leaderRestore_midRollout_keepsTheCarriedCountForTheNewVersion(RestoreOrder order) {
        newHarness(order, store -> {
            store.put(TARGET_KEY, autoscaled(V2, 6, 2, Option.none()));
            store.put(AppBlueprintKey.appBlueprintKey(OWNER), blueprintValue(slice(V2, 3, 2)));
        });

        assertThat(inMemoryInstances(V2)).isEqualTo(6);
    }

    /// Opposite polarity: nothing committed for the slice, so the declared count is the only source.
    @Test
    void leaderRestore_withNothingCommitted_usesTheDeclaredCount() {
        newHarness(RestoreOrder.BLUEPRINT_LAST, store -> store.put(AppBlueprintKey.appBlueprintKey(OWNER), blueprintValue(slice(V1, 4, 2))));

        assertThat(inMemoryInstances(V1)).isEqualTo(4);
    }

    private static AppBlueprintValue blueprintValue(ResolvedSlice slice) {
        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(slice), Option.none()));
    }

    private static SliceTargetValue autoscaled(Artifact artifact, int count, int min, Option<Integer> max) {
        return SliceTargetValue.sliceTargetValue(artifact.version(), count, min, Option.some(OWNER), max, Option.none(), Option.none());
    }

    // --- test fixtures ---

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 2;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(Collections.emptyList());
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        private final RestoreOrder order;

        InMemoryKvStore(MessageRouter router, RestoreOrder order) {
            super(router, stubSerializer(), stubDeserializer());
            this.order = order;
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }

        @Override
        public synchronized java.util.Map<AetherKey, AetherValue> snapshot() {
            var ordered = new java.util.LinkedHashMap<AetherKey, AetherValue>();
            var all = super.snapshot();
            java.util.function.Predicate<AetherKey> isBlueprint = AppBlueprintKey.class::isInstance;
            var firstGroup = order == RestoreOrder.BLUEPRINT_FIRST ? isBlueprint : isBlueprint.negate();

            all.forEach((k, v) -> {if (firstGroup.test(k)) ordered.put(k, v);});
            all.forEach((k, v) -> {if (!firstGroup.test(k)) ordered.put(k, v);});

            return ordered;
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
