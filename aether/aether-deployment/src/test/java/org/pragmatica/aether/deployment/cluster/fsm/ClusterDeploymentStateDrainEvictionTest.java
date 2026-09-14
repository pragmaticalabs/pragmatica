// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import java.net.SocketAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #688 — the leader's half of a node drain. `reconcileBlueprint` SKIPS every blueprint with an
/// instance on a draining node, deferring to the drain-eviction loop (`startDrainEviction` →
/// `evictNextSliceFromNode`: deploy a replacement elsewhere, unload the original once it is ACTIVE).
/// That loop had two entry points and neither fired in production: the `NodeDraining` decision is
/// never emitted (membership-v2 finale), and `resumeDrainEvictions` runs only when a leader
/// activates. So a draining node froze its blueprints until it halted or the leader changed.
///
/// Two entry points are pinned here, each showing the replacement LOAD issued for the other node:
/// the drain report the leader really receives (`onNodeDraining`, fed from the DRAINING pong), and
/// the periodic reconcile, which resumes an eviction nothing else started.
class ClusterDeploymentStateDrainEvictionTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_D = new NodeId("node-drain");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;
    private final AtomicReference<Set<NodeId>> draining = new AtomicReference<>(Set.of());

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, NODE_A, NODE_D),
                                                    () -> Set.of(SELF, NODE_A),
                                                    draining::get,
                                                    Set.of(SELF, NODE_A, NODE_D),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    System::currentTimeMillis).dormant();
        harness = FsmTestHarness.harness("drain-eviction-test-" + SELF.id(), factory);
        seedActiveSliceOn(NODE_D);
        harness.dispatch(new Activate());
        cluster.commands.clear();
    }

    /// Finding 2: a drain the leader never got a report for (or one that stalled) is resumed by the
    /// periodic reconcile, not only by a leader change.
    @Test
    void reconcile_withADrainingNodeHoldingASlice_issuesTheReplacementLoad() {
        draining.set(Set.of(NODE_D));

        activeState().reconcile();

        assertThat(replacementLoads()).as("#688: the periodic reconcile must resume the drain eviction — a replacement "
                                          + "LOAD for the other node — instead of skipping the blueprint forever")
                                      .hasSize(1);
    }

    /// Control for the fixture: the eviction arm itself works when driven by the legacy decision,
    /// so a red above is about the ENTRY POINT, not the loop.
    @Test
    void legacyNodeDrainingDecision_issuesTheReplacementLoad() {
        draining.set(Set.of(NODE_D));

        harness.dispatch(new MembershipDecisionReceived(MembershipDecision.nodeDraining(NODE_D, List.of(SELF, NODE_A, NODE_D))));

        assertThat(replacementLoads()).hasSize(1);
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private void seedActiveSliceOn(NodeId nodeId) {
        var artifactBase = ArtifactBase.artifactBase("org.example:slice-a").unwrap();
        var version = Version.version("1.0.0").unwrap();

        kvStore.put(SliceTargetKey.sliceTargetKey(artifactBase), SliceTargetValue.sliceTargetValue(version, 1));
        kvStore.put(NodeArtifactKey.nodeArtifactKey(nodeId, ARTIFACT), NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE, 0L));
    }

    /// A LOAD for the artifact on any node other than the draining one (the allocation engine
    /// iterates a Set, so SELF or NODE_A may be picked; NODE_D is excluded by the eviction itself).
    private List<KVCommand<AetherKey>> replacementLoads() {
        return cluster.commands.stream()
                               .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                  && put.key() instanceof NodeArtifactKey key
                                                  && key.artifact().equals(ARTIFACT)
                                                  && !key.nodeId().equals(NODE_D)
                                                  && put.value() instanceof NodeArtifactValue value
                                                  && value.state() == SliceState.LOAD)
                               .toList();
    }

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
