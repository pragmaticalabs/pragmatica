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
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeDrainingReported;
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
    /// `Active.onEntry` schedules a ONE-SHOT `deferredTopologyRecheck` 2s out; 3.5s waits it out.
    private static final long DEFERRED_RECHECK_WINDOW_MS = 3_500L;
    /// `deployReplacementForDrain` parks `checkReplacementAndUnload` 3s out on the real
    /// `SharedScheduler`, and it re-parks itself every 3s while it proceeds; 6s covers two firings.
    private static final long PARKED_CHECK_WINDOW_MS = 6_000L;

    private final Set<NodeId> ready = new java.util.HashSet<>(Set.of(SELF, NODE_A, NODE_D));
    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;
    private final AtomicReference<Set<NodeId>> draining = new AtomicReference<>(Set.of());
    /// The counted (effective) membership. Mutable so a test can model the COMMANDED drain, whose
    /// target is `Departing` and therefore uncounted from t0 — see the SF-2 scope control below.
    private final AtomicReference<Set<NodeId>> counted = new AtomicReference<>(Set.of(SELF, NODE_A, NODE_D));

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
            new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1)))));
        cluster = new RecordingClusterNode(SELF);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    counted::get,
                                                    () -> ready,
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

    /// Finding 1: the DRAINING report the leader really receives starts the eviction — and starts it
    /// ONCE per drain episode, because the pong repeats every ping interval and the reconcile tick
    /// repeats every interval; each repeat must find the loop already running.
    @Test
    void drainingReport_startsTheEvictionOnce_andRepeatsDoNotDoubleIssue() {
        draining.set(Set.of(NODE_D));

        harness.dispatch(new NodeDrainingReported(NODE_D));
        harness.dispatch(new NodeDrainingReported(NODE_D));
        activeState().reconcile();

        assertThat(replacementLoads()).as("#688: one replacement LOAD for one drain, however many times the report "
                                          + "and the tick repeat while the loop is running")
                                      .hasSize(1);
    }

    /// The guard is per drain EPISODE: once the node is no longer reported draining (halted or
    /// withdrawn), a later drain of the same node starts a fresh loop.
    @Test
    void drainWithdrawn_thenReported_again_startsAFreshEviction() {
        draining.set(Set.of(NODE_D));
        harness.dispatch(new NodeDrainingReported(NODE_D));
        assertThat(replacementLoads()).hasSize(1);

        draining.set(Set.of());
        activeState().reconcile();
        // The withdrawn episode's pending replacement was cancelled/removed. Retaining its
        // LOAD would correctly satisfy the next episode's planned deficit and require no new LOAD.
        var cancelled = activeState().sliceStates().entrySet().stream()
            .filter(entry -> entry.getKey().artifact().equals(ARTIFACT) && !entry.getKey().nodeId().equals(NODE_D))
            .filter(entry -> entry.getValue() == SliceState.LOAD).map(java.util.Map.Entry::getKey).toList();
        assertThat(cancelled).as("episode one still has an uncompleted replacement to cancel").hasSize(1);
        cancelled.forEach(activeState().sliceStates()::remove);
        seedActiveSliceOn(NODE_D);
        activeState().sliceStates().put(org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_D), SliceState.ACTIVE);
        cluster.commands.clear();
        draining.set(Set.of(NODE_D));

        harness.dispatch(new NodeDrainingReported(NODE_D));

        assertThat(replacementLoads()).as("a new drain episode must not be swallowed by the previous episode's guard")
                                      .hasSize(1);
    }

    /// #688 round 2, SF-1 — the guard leaked from the OTHER abandon path. The loop parks for 3s in
    /// `checkReplacementAndUnload` waiting for the replacement to go ACTIVE; if the drain is withdrawn
    /// while it is parked, that early return used to leave `drainEvictionsInProgress` holding the node.
    /// `startDrainEviction`'s `add` is then what REFUSES the node's whole SECOND drain episode, and
    /// nothing clears the guard except a reconcile tick that observes the node absent from the
    /// draining set — up to `reconciliationInterval` (30s) away, and never at all if the node
    /// re-drains first.
    ///
    /// Two masking paths have to be kept out of the window, and both were found by measurement rather
    /// than by reading:
    ///
    ///  1. `reconcile()` — `resumeDrainEvictions`' `retainAll(draining)` clears the guard silently
    ///     (no log line). `drainWithdrawn_thenReported_again_startsAFreshEviction` above goes through
    ///     it, which is precisely why that test stayed green with this defect present. This one calls
    ///     `reconcile()` nowhere.
    ///  2. `Active.onEntry`'s ONE-SHOT `deferredTopologyRecheck`, 2s out, which calls `reconcile()`
    ///     on its own timer. A first draft withdrew the drain immediately and was masked by it: the
    ///     probe read `guard=[]` after the window with the fix REVERTED. It is waited out here while
    ///     the node is still draining, where `retainAll` is a no-op.
    ///
    /// Overshooting either window only costs wall time. Undershooting the parked-check window fails
    /// RED (the loop legitimately still holds the guard): with no ACTIVE replacement in `sliceStates`
    /// a late-firing check only re-parks itself, so it cannot manufacture the LOAD asserted below.
    @Test
    void drainWithdrawnWhileTheReplacementCheckIsParked_thenRedrained_startsAFreshEviction() throws InterruptedException {
        draining.set(Set.of(NODE_D));
        harness.dispatch(new NodeDrainingReported(NODE_D));
        assertThat(replacementLoads()).as("arming: episode one issued its replacement and parked the 3s check")
                                      .hasSize(1);

        Thread.sleep(DEFERRED_RECHECK_WINDOW_MS);
        assertThat(activeState().drainEvictionsInProgress())
                .as("arming: the deferred recheck fired while the node was still draining, so it cleared "
                    + "nothing, and the loop is genuinely parked holding the guard")
                .containsExactly(NODE_D);

        draining.set(Set.of());
        Thread.sleep(PARKED_CHECK_WINDOW_MS);

        // The withdrawn episode's pending replacement was cancelled/removed. Retaining its
        // LOAD would correctly satisfy the next episode's planned deficit and require no new LOAD.
        var cancelled = activeState().sliceStates().entrySet().stream()
            .filter(entry -> entry.getKey().artifact().equals(ARTIFACT) && !entry.getKey().nodeId().equals(NODE_D))
            .filter(entry -> entry.getValue() == SliceState.LOAD).map(java.util.Map.Entry::getKey).toList();
        assertThat(cancelled).as("episode one still has an uncompleted replacement to cancel").hasSize(1);
        cancelled.forEach(activeState().sliceStates()::remove);
        seedActiveSliceOn(NODE_D);
        activeState().sliceStates().put(org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_D), SliceState.ACTIVE);
        cluster.commands.clear();
        draining.set(Set.of(NODE_D));

        harness.dispatch(new NodeDrainingReported(NODE_D));

        assertThat(replacementLoads()).as("a drain withdrawn while the replacement check was parked must not leave a "
                                          + "guard that swallows the node's next drain episode")
                                      .hasSize(1);
    }

    /// #688 round 2, SF-2 — the SCOPE control for every other test in this class. They supply a
    /// COUNTED drainee, which models the self-initiated drain (`QUORUM_LOSS`), where the leader's FSM
    /// never saw a request and the node stays in the effective set until it halts. A COMMANDED drain
    /// is not that shape: `requestDrainThroughFsm` → `MembershipFsm.onDrainRequested` puts the target
    /// in `Departing`, whose `countsTowardEffective()` is false, so it leaves the counted set at t0 —
    /// before any DRAINING pong. `cleanupStaleSliceEntries` then drops its slice entries at the end of
    /// each `reconcile()` and the blueprint is re-placed from zero instances, by reconcile rather than
    /// by the eviction loop, which afterwards finds nothing on the node at all.
    ///
    /// Without this, the fixture's counted drainee could silently drift from the producers and the
    /// class would read as a statement about drains in general.
    @Test
    void commandedDrain_uncountedDrainee_isReplacedByReconcileAndLeavesTheEvictionLoopNothingToDo() {
        counted.set(Set.of(SELF, NODE_A));
        draining.set(Set.of(NODE_D));

        activeState().reconcile();

        assertThat(removalsFor(NODE_D)).as("arming: reconcile cleaned the uncounted node's slice entries — the "
                                           + "mechanism that makes this path differ from the counted one")
                                       .isNotEmpty();
        assertThat(replacementLoads()).as("the replacement for a commanded drain comes from reconcile's re-placement, "
                                          + "not from the drain-eviction loop")
                                      .hasSize(1);

        cluster.commands.clear();
        harness.dispatch(new NodeDrainingReported(NODE_D));

        assertThat(replacementLoads()).as("by the time the DRAINING pong lands there is nothing left on the node to "
                                          + "evict, so the loop issues nothing")
                                      .isEmpty();
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

    @Test
    void workerDrainRequiresAllDesiredActiveReplicasInCurrentEligibleAudience() {
        var first = new NodeId("worker-first");
        var second = new NodeId("worker-second");
        activeState().workerNodes().addAll(Set.of(NODE_D, first, second));
        ready.addAll(Set.of(first, second));
        draining.set(Set.of(NODE_D));
        kvStore.put(SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
            AetherValue.SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 2, 1, "WORKERS_ONLY"));
        assertThat(activeState().drainReplacementNodes(ARTIFACT)).isEmpty();
        assertThat(activeState().hasDrainReplacement(ARTIFACT)).isFalse();
        kvStore.put(new AetherKey.CommunityKey("workers"), new AetherValue.CommunityValue("source", "WORKER", 100,
            org.pragmatica.aether.slice.kvstore.CommunityState.ACTIVE, 1L, Option.none()));
        kvStore.put(AetherKey.GovernorAnnouncementKey.forCommunity("workers"),
            AetherValue.GovernorAnnouncementValue.governorAnnouncementValue(first, 3, List.of(NODE_D, first, second), "", 1L));
        assertThat(activeState().drainReplacementNodes(ARTIFACT)).containsExactlyInAnyOrder(first, second);
        var firstKey = new AetherKey.SliceNodeKey(ARTIFACT, first);
        var secondKey = new AetherKey.SliceNodeKey(ARTIFACT, second);
        activeState().sliceStates().put(new AetherKey.SliceNodeKey(ARTIFACT, SELF), SliceState.ACTIVE);
        activeState().sliceStates().put(firstKey, SliceState.ACTIVE);
        activeState().sliceStates().put(secondKey, SliceState.LOADING);
        assertThat(activeState().hasDrainReplacement(ARTIFACT)).as("one active worker plus an out-of-policy core is insufficient").isFalse();
        activeState().sliceStates().put(secondKey, SliceState.ACTIVE);
        assertThat(activeState().hasDrainReplacement(ARTIFACT)).isTrue();
        ready.remove(second);
        assertThat(activeState().hasDrainReplacement(ARTIFACT)).as("stale readiness is not a replacement").isFalse();
        ready.add(second);
        draining.set(Set.of(NODE_D, second));
        assertThat(activeState().hasDrainReplacement(ARTIFACT)).as("another draining worker is not a replacement").isFalse();
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

    /// Every KV removal the leader issued for a key naming `nodeId`.
    private List<KVCommand<AetherKey>> removalsFor(NodeId nodeId) {
        return cluster.commands.stream()
                               .filter(command -> command instanceof KVCommand.Remove<AetherKey> remove
                                                  && remove.key()
                                                           .toString()
                                                           .contains(nodeId.id()))
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
            if (value instanceof org.pragmatica.cluster.state.kvstore.LeaderAuthorized) {
                var leader = getTyped(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
                    org.pragmatica.cluster.state.kvstore.LeaderValue.class).unwrap();
                process(createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                    java.util.UUID.randomUUID().toString(), leader, List.of(),
                    List.of(new KVCommand.Mutation<>(key, get(key), Option.some(value)))))));
            } else {
                process(createBatch(List.of(new KVCommand.Put<>(key, value))));
            }
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
