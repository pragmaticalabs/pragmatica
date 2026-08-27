// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Transactional + isolation semantics for ALL_OR_NOTHING cluster deployments:
/// (1) blueprint ownership is keyed by name (artifact base), not version — a version upgrade of
///     the same blueprint must not self-conflict, but a different blueprint name sharing a slice
///     base must still conflict;
/// (2) `capturePreviousBlueprint` recovers the prior ACTIVE version from KV so a failed deploy can
///     be rolled back to the exact pre-deployment state;
/// (3) `restorePreviousBlueprint` re-Puts the prior value under the prior version's key, not the
///     failed deploy's key.
class ClusterDeploymentStateTransactionalTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
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
        harness = FsmTestHarness.harness("transactional-test-" + SELF.id(), factory);
        harness.dispatch(new Activate());
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private static Artifact artifact(String name, Version version) {
        return Artifact.artifact("com.example:" + name + ":" + version.bareVersion()).unwrap();
    }

    private static BlueprintId blueprintId(String name, Version version) {
        return BlueprintId.blueprintId("com.example:" + name + ":" + version.bareVersion()).unwrap();
    }

    private static ExpandedBlueprint blueprint(String name, Version version, String sliceName) {
        var id = blueprintId(name, version);
        var slice = ResolvedSlice.resolvedSlice(artifact(sliceName, version), 3, false).unwrap();
        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    @Nested
    class NameKeyedOwnership {
        @Test
        void hasConflictingOwnership_sameNameDifferentVersion_noConflict() {
            var existing = blueprint("app", V1, "slice-a");
            var ownedSlice = Blueprint.blueprint(artifact("slice-a", V1), 3, 1, Option.some(existing.id()));
            activeState().blueprints().put(ownedSlice.artifact(), ownedSlice);

            var upgrade = blueprint("app", V2, "slice-a");

            assertThat(activeState().hasConflictingOwnershipForTest(upgrade)).isFalse();
        }

        @Test
        void hasConflictingOwnership_differentNameSharedSliceBase_stillConflicts() {
            var owner = blueprint("owner-app", V1, "slice-a");
            var ownedSlice = Blueprint.blueprint(artifact("slice-a", V1), 3, 1, Option.some(owner.id()));
            activeState().blueprints().put(ownedSlice.artifact(), ownedSlice);

            var intruder = blueprint("other-app", V1, "slice-a");

            assertThat(activeState().hasConflictingOwnershipForTest(intruder)).isTrue();
        }
    }

    @Nested
    class CapturePreviousBlueprint {
        @Test
        void capturePreviousBlueprint_priorActiveVersionInKv_returnsPriorExpanded() {
            // The prior AppBlueprintValue is keyed by the BLUEPRINT id (com.example:app:1.0.0),
            // while the SliceTargetValue is keyed by the SLICE base (com.example:slice-a) — these
            // are distinct bases. The capture must read the version from the slice target but
            // derive the prior blueprint id from the blueprint base, not the slice base.
            var prior = blueprint("app", V1, "slice-a");
            var sliceBase = artifact("slice-a", V1).base();
            assertThat(sliceBase).isNotEqualTo(prior.id().base());
            kvStore.put(SliceTargetKey.sliceTargetKey(sliceBase), SliceTargetValue.sliceTargetValue(V1, 3));
            kvStore.put(AppBlueprintKey.appBlueprintKey(prior.id()), AppBlueprintValue.appBlueprintValue(prior));

            var upgrade = blueprint("app", V2, "slice-a");

            var captured = activeState().capturePreviousBlueprintForTest(upgrade);

            assertThat(captured.isPresent()).isTrue();
            captured.onPresent(p -> assertThat(p.id()).isEqualTo(prior.id()));
        }

        @Test
        void capturePreviousBlueprint_noPriorTarget_returnsEmpty() {
            var firstDeploy = blueprint("app", V1, "slice-a");

            assertThat(activeState().capturePreviousBlueprintForTest(firstDeploy).isEmpty()).isTrue();
        }

        @Test
        void capturePreviousBlueprint_sameVersionRedeploy_returnsEmpty() {
            var sliceBase = artifact("slice-a", V1).base();
            kvStore.put(SliceTargetKey.sliceTargetKey(sliceBase), SliceTargetValue.sliceTargetValue(V1, 3));

            var sameVersion = blueprint("app", V1, "slice-a");

            assertThat(activeState().capturePreviousBlueprintForTest(sameVersion).isEmpty()).isTrue();
        }
    }

    @Nested
    class RestorePreviousBlueprint {
        @Test
        void restorePreviousBlueprint_putsUnderPriorVersionKey() {
            var prior = blueprint("app", V1, "slice-a");

            activeState().restorePreviousBlueprintForTest(prior);

            var expectedKey = AppBlueprintKey.appBlueprintKey(prior.id());
            assertThat(cluster.putKeys()).contains(expectedKey);
        }

        @Test
        void restorePreviousBlueprint_doesNotPutUnderFailedVersionKey() {
            var prior = blueprint("app", V1, "slice-a");
            var failedKey = AppBlueprintKey.appBlueprintKey(blueprintId("app", V2));

            activeState().restorePreviousBlueprintForTest(prior);

            assertThat(cluster.putKeys()).doesNotContain(failedKey);
        }
    }

    // --- test fixtures (mirrors ClusterDeploymentStateActiveTest) ---

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

        List<AetherKey> putKeys() {
            synchronized (commands) {
                return commands.stream()
                               .filter(c -> c instanceof KVCommand.Put<AetherKey, ?>)
                               .map(KVCommand::key)
                               .toList();
            }
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
