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
/// `placement = CORE_ONLY`. `ResolvedSlice` has no placement, and neither has the blueprint format
/// (`known-limitations.md`: "the blueprint has no placement key"), so the blueprint is silent about
/// placement by construction and the only source of a non-default placement is the committed value
/// (`POST /api/slices/scale`, which refuses slices that are not blueprint-owned). The republish
/// must therefore carry the committed placement through.
///
/// Every other component of the value (`currentVersion`, `targetInstances`, `minInstances`, owner,
/// `maxInstances`, both thresholds) IS declared by the blueprint's `ResolvedSlice`, so a republish
/// replaces those by contract; `updatedAt` is a clock. The tests below pin both halves: placement
/// survives, declared components follow the blueprint.
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
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
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

    /// The components the blueprint declares follow the blueprint on a republish — only placement
    /// is carried. A fix that kept the whole committed value would pass the placement tests and
    /// fail here.
    @Test
    void republish_replacesEveryComponentTheBlueprintDeclares() {
        newHarness();
        commit(SliceTargetValue.sliceTargetValue(V1.version(), 3, 2, Option.none(), Option.some(9), Option.some(0.9), Option.some(0.1))
                               .withPlacement("WORKERS_ONLY"));

        var proposed = republish(slice(V1, 5, 4, Option.some(7), Option.some(0.8), Option.some(0.2)));

        assertThat(proposed.targetInstances()).isEqualTo(5);
        assertThat(proposed.minInstances()).isEqualTo(4);
        assertThat(proposed.owningBlueprint()).isEqualTo(Option.some(OWNER));
        assertThat(proposed.maxInstances()).isEqualTo(Option.some(7));
        assertThat(proposed.scaleUpThreshold()).isEqualTo(Option.some(0.8));
        assertThat(proposed.scaleDownThreshold()).isEqualTo(Option.some(0.2));
        assertThat(proposed.effectivePlacement()).isEqualTo("WORKERS_ONLY");
    }

    /// A blueprint that stops declaring an override removes it — the declaration is authoritative
    /// for the components it can express.
    @Test
    void republish_withoutDeclaredOverrides_clearsCommittedOverrides() {
        newHarness();
        commit(SliceTargetValue.sliceTargetValue(V1.version(), 3, 2, Option.none(), Option.some(9), Option.some(0.9), Option.some(0.1)));

        var proposed = republish(slice(V1, 3, 2));

        assertThat(proposed.maxInstances()).isEqualTo(Option.none());
        assertThat(proposed.scaleUpThreshold()).isEqualTo(Option.none());
        assertThat(proposed.scaleDownThreshold()).isEqualTo(Option.none());
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
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
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
