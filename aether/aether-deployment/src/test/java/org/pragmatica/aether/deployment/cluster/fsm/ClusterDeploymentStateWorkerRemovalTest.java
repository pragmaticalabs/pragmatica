// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.ActivationDirectivePutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.WorkerJoinReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.WorkerLeaveReceived;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcTimestamp;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #731 (symmetric sibling of #728): a worker's REMOVED edge never reaches
/// [ClusterDeploymentEvents.MembershipDecisionReceived] (workers never enter the CORE `announced`
/// baseline — the Wave-2 core-delta invariant #728 preserved), so before this fix
/// `handleNodeRemoval` was unreachable for a dead worker and its allocation-pool slot
/// (`workerNodes`) plus CDM-local KV footprint (`sliceStates`/`NodeArtifactKey`/`NodeRoutesKey`)
/// lingered forever. [ClusterDeploymentEvents.WorkerLeaveReceived] is the new non-core leave
/// channel, routed straight into the same `handleNodeRemoval` a CORE removal already uses.
///
/// Drives the real FSM through the full #728 join + #731 leave + rejoin cycle and inspects both
/// the in-memory `Active` state and the KV store the stub cluster commits into — mirrors
/// `ClusterDeploymentStateCommunityMintTest`'s fixture (the only existing variant that wires KV
/// commits back into a readable store, needed here to assert `NodeArtifactKey`/`NodeRoutesKey`
/// cleanup).
class ClusterDeploymentStateWorkerRemovalTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId WORKER_1 = new NodeId("node-worker-1");
    private static final NodeId CORE_1 = new NodeId("node-core-1");
    private static final NodeId CORE_2 = new NodeId("node-core-2");
    private static final String SOURCE = "eu-west";
    private static final String COMMUNITY_ID = SOURCE + "-w-0";
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-worker-removal:1.0.0").unwrap();

    private RecordingClusterNode cluster;
    private InMemoryKvStore kvStore;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF, kvStore);
        Function<NodeId, Option<String>> memberSourceSupplier = nodeId -> Option.some(SOURCE);

        // coreMax=1 with SELF already the sole core-counted/ready member forces any joining node
        // down the WORKER branch of assignNodeRole (mirrors CommunityMintTest's setup) and makes
        // SELF the only allocatableNode for the post-departure reconcile assertion.
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF),
                                                    () -> Set.of(SELF),
                                                    Set::of,
                                                    Set.of(SELF),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    1,
                                                    timeSpan(300).seconds(),
                                                    System::currentTimeMillis,
                                                    memberSourceSupplier).dormant();
        harness = FsmTestHarness.harness("worker-removal-" + SELF.id(), factory);
        harness.dispatch(new Activate());
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    /// Drives the full #728 non-core join channel end to end: `WorkerJoinReceived` triggers
    /// `assignNodeRole` -> `assignWorkerRole`, which submits a WORKER `ActivationDirectiveKey` Put
    /// through the stub cluster (committed into the KV store by `RecordingClusterNode.apply`).
    /// `workerNodes` itself is populated only when that Put round-trips back into the FSM as a
    /// SEPARATE `ActivationDirectivePutReceived` notification (`processActivationDirectivePut` ->
    /// `addWorkerNode`) — the harness has no live KV-notification route wired, so the round-trip is
    /// dispatched explicitly here rather than assumed.
    private void joinWorker(NodeId nodeId) {
        harness.dispatch(new WorkerJoinReceived(WorkerJoinDecision.workerJoinDecision(nodeId, "worker", HlcTimestamp.ZERO)));
        harness.dispatch(new ActivationDirectivePutReceived(
                new ValuePut<>(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(nodeId),
                                                   ActivationDirectiveValue.worker(COMMUNITY_ID, "")),
                              Option.empty())));
    }

    private void leaveWorker(NodeId nodeId) {
        harness.dispatch(new WorkerLeaveReceived(WorkerLeaveDecision.workerLeaveDecision(nodeId, HlcTimestamp.ZERO)));
    }

    @Nested
    class WorkerLifecycle {
        @Test
        void workerJoin_activationDirectiveObserved_registersInAllocationPool() {
            joinWorker(WORKER_1);

            assertThat(activeState().workerNodes())
                    .as("#728: a WORKER activation directive round-trip must register the node in the allocation pool")
                    .contains(WORKER_1);
        }

        @Test
        void workerLeave_departedWorker_clearsAllocationPoolAndKvFootprint() {
            joinWorker(WORKER_1);
            assertThat(activeState().workerNodes()).contains(WORKER_1);

            var artifactKey = NodeArtifactKey.nodeArtifactKey(WORKER_1, ARTIFACT);
            var routesKey = NodeRoutesKey.nodeRoutesKey(WORKER_1, ARTIFACT);
            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, WORKER_1);

            // handleNodeRemoval rebuilds sliceStates from live NodeArtifactKey KV entries FIRST
            // (additive-only), so seeding the NodeArtifactKey entry is sufficient for the in-memory
            // sliceStates-removal assertion. No production path ever PUTs a SliceNodeKey row directly
            // (slice-state persistence goes exclusively through NodeArtifactKey), so the SliceNodeKey
            // row is seeded here purely as test setup, to make the KV-removal assertion meaningful.
            kvStore.put(artifactKey, NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
            kvStore.put(routesKey, NodeRoutesValue.empty());
            kvStore.put(sliceKey, AetherValue.SliceNodeValue.sliceNodeValue(SliceState.ACTIVE));

            leaveWorker(WORKER_1);

            assertThat(activeState().workerNodes())
                    .as("#731: a departed worker must leave the allocation-pool roster (was: kept forever)")
                    .doesNotContain(WORKER_1);
            assertThat(activeState().sliceStates())
                    .as("#731: the departed worker's slice-node view must be cleared")
                    .doesNotContainKey(sliceKey);
            assertThat(kvStore.get(artifactKey))
                    .as("#731: the departed worker's NodeArtifactKey KV entry must be removed")
                    .isEqualTo(Option.empty());
            assertThat(kvStore.get(routesKey))
                    .as("#731: the departed worker's NodeRoutesKey KV entry must be removed")
                    .isEqualTo(Option.empty());
            assertThat(kvStore.get(sliceKey))
                    .as("#731: the departed worker's SliceNodeKey KV entry must be removed directly, "
                        + "not left to the stale sweep")
                    .isEqualTo(Option.empty());
        }

        @Test
        void workerLeave_outstandingBlueprintShortfall_reconcileRePlacesOntoRemainingPool() {
            joinWorker(WORKER_1);
            kvStore.put(NodeArtifactKey.nodeArtifactKey(WORKER_1, ARTIFACT), NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
            // #699: unowned blueprint, so schemaRequired stays at the historical default (true).
            activeState().blueprints().put(ARTIFACT, Blueprint.blueprint(ARTIFACT, 1, 1, Option.empty(), true));
            cluster.commands.clear();

            leaveWorker(WORKER_1);

            var reallocatedOntoSelf = cluster.commands.stream()
                                                      .anyMatch(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                                           && put.key() instanceof NodeArtifactKey nak
                                                                           && nak.artifact().equals(ARTIFACT)
                                                                           && nak.nodeId().equals(SELF));

            assertThat(reallocatedOntoSelf)
                    .as("#731: handleNodeRemoval(...).onSuccess(_ -> reconcile()) must re-place an outstanding "
                        + "blueprint shortfall onto the remaining allocatable node after a worker departs")
                    .isTrue();
        }

        @Test
        void workerRejoin_afterDeparture_reRegistersInAllocationPool() {
            joinWorker(WORKER_1);
            leaveWorker(WORKER_1);
            assertThat(activeState().workerNodes()).doesNotContain(WORKER_1);

            joinWorker(WORKER_1);

            assertThat(activeState().workerNodes())
                    .as("#731: a departed worker must be able to rejoin and be re-registered")
                    .contains(WORKER_1);
        }

        /// #731 refinement (BLOCKING 1 + SHOULD-FIX 3, one mechanism): a leader that never locally
        /// observed this worker's JOIN — a freshly booted leader, or an asymmetric connection window
        /// — restores it purely from its durable `ActivationDirectiveKey` on activation, with no
        /// `MembershipDeltaProjector` REMOVED edge ever able to fire for it (its `everJoined` gate
        /// filters exactly this case upstream). Liveness, observed independently of local join
        /// history, is the only signal left to catch a worker that is dead on arrival.
        @Test
        void leaderActivation_restoredWorkerObservedAbsentFromLiveness_isRemoved() {
            var deadWorker = new NodeId("node-worker-dead");
            var artifactKey = NodeArtifactKey.nodeArtifactKey(deadWorker, ARTIFACT);
            var routesKey = NodeRoutesKey.nodeRoutesKey(deadWorker, ARTIFACT);
            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, deadWorker);
            var directiveKey = ActivationDirectiveKey.activationDirectiveKey(deadWorker);

            kvStore.put(directiveKey, ActivationDirectiveValue.worker(COMMUNITY_ID, ""));
            kvStore.put(artifactKey, NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
            kvStore.put(routesKey, NodeRoutesValue.empty());
            kvStore.put(sliceKey, AetherValue.SliceNodeValue.sliceNodeValue(SliceState.ACTIVE));

            activeState().ctx().setCommunityLiveness(node -> node.equals(deadWorker));

            activeState().rebuildStateFromKVStore();

            assertThat(activeState().workerNodes())
                    .as("#731: a restored worker the leader observes absent from liveness must not survive activation")
                    .doesNotContain(deadWorker);
            assertThat(kvStore.get(directiveKey))
                    .as("#731: the dead restored worker's ActivationDirectiveKey must be removed so it cannot "
                        + "resurrect the pool slot on the next activation")
                    .isEqualTo(Option.empty());
            assertThat(kvStore.get(artifactKey)).isEqualTo(Option.empty());
            assertThat(kvStore.get(routesKey)).isEqualTo(Option.empty());
            assertThat(kvStore.get(sliceKey)).isEqualTo(Option.empty());
        }
    }

    /// Net-new regression: no existing test exercises `handleNodeRemoval` at this FSM layer for a
    /// CORE node. Confirms the #731 WORKER-channel addition left the pre-existing CORE
    /// `MembershipDecisionReceived(NodeRemoved)` arm's cleanup behavior unchanged.
    @Nested
    class CoreRemovalRegression {
        @Test
        void coreNodeRemoved_membershipDecision_clearsSliceStateAndKvFootprint() {
            var artifactKey = NodeArtifactKey.nodeArtifactKey(CORE_2, ARTIFACT);
            var routesKey = NodeRoutesKey.nodeRoutesKey(CORE_2, ARTIFACT);
            var sliceKey = SliceNodeKey.sliceNodeKey(ARTIFACT, CORE_2);

            kvStore.put(artifactKey, NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
            kvStore.put(routesKey, NodeRoutesValue.empty());

            harness.dispatch(new MembershipDecisionReceived(MembershipDecision.nodeRemoved(CORE_2, List.of(SELF, CORE_1))));

            assertThat(activeState().sliceStates())
                    .as("core removal cleanup must remain unaffected by the #731 worker-leave addition")
                    .doesNotContainKey(sliceKey);
            assertThat(kvStore.get(artifactKey)).isEqualTo(Option.empty());
            assertThat(kvStore.get(routesKey)).isEqualTo(Option.empty());
        }
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
                return 1;
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
        final List<List<KVCommand<AetherKey>>> batches = Collections.synchronizedList(new ArrayList<>());
        private final InMemoryKvStore committed;

        RecordingClusterNode(NodeId self, InMemoryKvStore committed) {
            this.self = self;
            this.committed = committed;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        // Mirror the real consensus -> KV replication loop: an applied batch is recorded AND
        // committed into the shared KV store, so a subsequent read (community existence, KV-cleanup
        // assertions) observes it.
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            batches.add(List.copyOf(batch));
            committed.commit(batch);
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

        void commit(List<KVCommand<AetherKey>> batch) {
            process(createBatch(batch));
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
