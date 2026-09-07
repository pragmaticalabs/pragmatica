// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintRemoveReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #924 review round 2, BLOCKING — retry exhaustion must discriminate on whether the artifact EVER
/// reached ACTIVE, not on whether it is ACTIVE right now.
///
/// The round-1 fix asked the present-tense question, and `handleSliceFailure` removes the failing
/// key from `sliceStates` before either failure branch runs. So the guard could only ever protect a
/// transient confined to a strict SUBSET of instances. Two shapes escaped it, both previously
/// healthy, both silently condemned cluster-wide and permanently with their last outcome record
/// still reading SUCCEEDED, and both of which self-healed before #922:
///
///   - a shared downstream dependency, which by construction reaches every replica at once;
///   - every `instances = 1` slice, which has no sibling that could ever vote for it and for which
///     the guard is therefore structurally unreachable.
///
/// Assertions here read `Active.permanentlyFailed()` directly. `Active` is a record, so the accessor
/// IS the terminal — not an inference from command counts, which cannot tell "not condemned" from
/// "condemned but the rollback matched nothing".
///
/// The first four tests are the round-2 verification probe adopted verbatim in scenario and
/// assertion. Two of them are POLARITY CONTROLS and they are as load-bearing as the probes: without
/// `control_neverActiveAnywhere` a fix that simply never settles passes everything, and without
/// `control_siblingStaysActive` the round-1 guarantee could silently regress. The remaining three
/// pin the legs and the guard that the probe does not reach.
class RetryExhaustionEverActiveTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();

    /// `ClusterDeploymentState.Active.MAX_RETRIES` is private; 5 retries means the SIXTH reported
    /// failure is the one that spends the budget.
    private static final int TERMINAL_ON_REPORT = 6;

    private static final Supplier<Set<NodeId>> RESOLVED_MEMBERSHIP = () -> Set.of(SELF, NODE_A);

    private RecordingClusterNode leaderSideCluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness;

    @BeforeEach
    void setUp() {
        leaderSideCluster = new RecordingClusterNode(SELF);
        leaderHarness = leaderHarness(leaderSideCluster, freshStore(), RESOLVED_MEMBERSHIP);
    }

    /// CONTROL 1 (negative polarity): a deployment ATTEMPT that never reached ACTIVE anywhere MUST
    /// settle. If this does not settle, the suite cannot observe settling at all and every other
    /// result here is meaningless. This is #922's own population.
    @Test
    void control_neverActiveAnywhere_doesSettlePermanentlyFailed() {
        var expanded = blueprint();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        var active = (ClusterDeploymentState.Active) leaderHarness.state();

        assertThat(active.permanentlyFailed())
                .as("instrument check: the #922 population MUST still settle")
                .contains(SLICE);
    }

    /// CONTROL 2 (positive polarity): the round-1 scenario. A sibling instance stays ACTIVE, so
    /// exhaustion on ONE node must not settle.
    @Test
    void control_siblingStaysActive_doesNotSettle() {
        var expanded = blueprint();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        var active = (ClusterDeploymentState.Active) leaderHarness.state();

        assertThat(active.getCurrentInstances(SLICE)).hasSize(2);
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        assertThat(active.permanentlyFailed())
                .as("a node-local transient must not condemn the artifact")
                .doesNotContain(SLICE);
    }

    /// Same previously-healthy, fully-deployed artifact as CONTROL 2, but the transient is a SHARED
    /// DOWNSTREAM DEPENDENCY — the exact cause round 1 named. Such a cause reaches EVERY instance by
    /// construction, so node-a's instance fails too and no sibling is left to vote.
    @Test
    void clusterWideTransient_onPreviouslyHealthyDeployment_doesNotSettle() {
        var expanded = blueprint();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        var active = (ClusterDeploymentState.Active) leaderHarness.state();

        assertThat(active.getCurrentInstances(SLICE))
                .as("precondition: RUNNING on both nodes, blueprint retired, SUCCEEDED written")
                .hasSize(2);

        // The shared dependency goes down. node-a's instance reports the same intermittent cause.
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, intermittentFailure())));
        // SELF's instance keeps reporting it until its per-node budget is spent.
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        leaderSideCluster.commands.clear();
        active.reconcile();

        assertThat(active.permanentlyFailed())
                .as("a previously-healthy, fully-deployed artifact hit by a SHARED transient must not "
                    + "be condemned cluster-wide and permanently")
                .doesNotContain(SLICE);
        assertThat(leaderSideCluster.commandKeysFor(SLICE))
                .as("and reconciliation must still be emitting work for it — a silent terminal is the "
                    + "harm, not merely the permanentlyFailed entry")
                .isNotEmpty();
    }

    /// No shared dependency needed. A SINGLE-INSTANCE slice — the most common shape there is —
    /// deployed and ACTIVE on its one node, whose own node suffers a transient longer than the
    /// budget. There is no sibling to vote for it, so a present-tense guard is structurally
    /// unreachable for this configuration.
    @Test
    void singleInstanceSlice_transientOnItsOnlyNode_doesNotSettle() {
        var expanded = singleInstanceBlueprint();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        var active = (ClusterDeploymentState.Active) leaderHarness.state();

        assertThat(active.getCurrentInstances(SLICE))
                .as("precondition: the single desired instance is RUNNING")
                .hasSize(1);

        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        leaderSideCluster.commands.clear();
        active.reconcile();

        assertThat(active.permanentlyFailed())
                .as("a healthy single-instance workload must not be permanently condemned by a "
                    + "transient on its own node")
                .doesNotContain(SLICE);
        assertThat(leaderSideCluster.commandKeysFor(SLICE))
                .as("and reconciliation must still be emitting work for it")
                .isNotEmpty();
    }

    /// The DURABLE leg, in isolation, on a leader that has never watched this artifact run.
    ///
    /// This is the failover case, and it is why the discriminator is not merely in-memory
    /// bookkeeping. Nothing is ACTIVE in the rebuilt slice state and `everActiveArtifacts` starts
    /// empty on a new leader, so the ONLY evidence available is the `SUCCEEDED` outcome record the
    /// previous leader wrote — read back through `blueprintDeploymentSucceeded`. Deleting the
    /// durable leg leaves the other two unable to answer, and the artifact settles.
    @Test
    void durableSucceededOutcome_isEnoughOnANewLeaderWithNoInMemoryEvidence() {
        var expanded = blueprint();
        var newLeaderCluster = new RecordingClusterNode(SELF);
        var newLeaderStore = freshStore();

        // Exactly what a new leader finds in the KV-Store: the slice target, which is what
        // repopulates the artifact -> owning-blueprint map, and the terminal outcome of the
        // deployment that already completed under the previous leader.
        seed(newLeaderStore,
             new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(SLICE.base()),
                                 SliceTargetValue.sliceTargetValue(SLICE.version(), 3, Option.some(expanded.id()))),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.succeeded(1L)));

        var harness = leaderHarness(newLeaderCluster, newLeaderStore, RESOLVED_MEMBERSHIP);
        var active = (ClusterDeploymentState.Active) harness.state();

        // `getCurrentInstances` is the WRONG instrument for this precondition: it filters on
        // `isLiveState`, so the instances `Active.onEntry`'s own reconcile has just allocated count
        // toward it while being nowhere near ACTIVE. Both in-memory legs are asserted directly.
        assertThat(active.everActiveArtifacts())
                .as("precondition: this leader has never watched the artifact run")
                .isEmpty();
        assertThat(statesOf(active, SLICE))
                .as("precondition: nothing is ACTIVE in the rebuilt slice state either")
                .doesNotContain(SliceState.ACTIVE);

        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            harness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        assertThat(active.permanentlyFailed())
                .as("the durable SUCCEEDED record is the only evidence a new leader has that this "
                    + "artifact ever ran, and it must be enough")
                .doesNotContain(SLICE);
    }

    /// The anti-livelock boundary. "Ever reached ACTIVE" must be scoped to the deployment it
    /// describes, or a coordinate that ran once and has since become unfetchable would be re-driven
    /// forever on every LATER deployment — #922 reopened through the guard meant to bound it. The
    /// boundary is the artifact leaving the cluster's desired state, which is
    /// `issueDeallocationCommands`; it is deliberately NOT a re-apply of the same blueprint, since
    /// clearing on re-apply would put a healthy running workload back one transient away from the
    /// silent condemnation the tests above pin.
    @Test
    void aLaterDeploymentOfARemovedCoordinate_stillSettles() {
        var expanded = blueprint();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        leaderHarness.dispatch(new SliceTargetPutReceived(sliceTargetPut(expanded.id())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        var active = (ClusterDeploymentState.Active) leaderHarness.state();

        assertThat(active.sliceStates()).containsEntry(SliceNodeKey.sliceNodeKey(SLICE, SELF), SliceState.ACTIVE);
        assertThat(active.everActiveArtifacts())
                .as("precondition: the artifact reached ACTIVE, so the evidence exists to be forgotten")
                .contains(SLICE);

        // The artifact leaves the cluster's desired state.
        leaderHarness.dispatch(new AppBlueprintRemoveReceived(appBlueprintRemove(expanded.id())));

        assertThat(active.everActiveArtifacts())
                .as("the deallocation seam is where the evidence is forgotten")
                .doesNotContain(SLICE);

        // A later, independent deployment of the same coordinate, which never comes up.
        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        assertThat(active.permanentlyFailed())
                .as("evidence from a removed deployment must not keep a later one alive")
                .contains(SLICE);
    }

    /// #924 review round 2, S2 — the settle must not be taken on an unresolved member set.
    ///
    /// During the boot window the core-membership supplier yields `MEMBERSHIP_NOT_WIRED`, an empty
    /// set distinguished only by reference identity, so every node fails `contains` and every
    /// artifact looks abandoned. The inputs here are IDENTICAL to `control_neverActiveAnywhere`,
    /// which settles; the single difference is the member set, so the pair discriminates the guard
    /// on its own. Refusing to settle costs a re-drive the next exhaustion re-decides; settling on
    /// an unresolved read is irreversible.
    @Test
    void unresolvedCoreMembership_doesNotSettle() {
        var expanded = blueprint();
        var bootCluster = new RecordingClusterNode(SELF);
        var harness = leaderHarness(bootCluster, freshStore(), () -> MembershipFsm.MEMBERSHIP_NOT_WIRED);

        harness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            harness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        var active = (ClusterDeploymentState.Active) harness.state();

        assertThat(active.permanentlyFailed())
                .as("an unresolved member set is not evidence of anything, and this verdict is one-way")
                .doesNotContain(SLICE);
    }

    private static List<SliceState> statesOf(ClusterDeploymentState.Active active, Artifact artifact) {
        return active.sliceStates()
                     .entrySet()
                     .stream()
                     .filter(entry -> entry.getKey().artifact().equals(artifact))
                     .map(Map.Entry::getValue)
                     .toList();
    }

    private static KVStore<AetherKey, AetherValue> freshStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @SafeVarargs
    private static void seed(KVStore<AetherKey, AetherValue> store, KVCommand<AetherKey>... commands) {
        var batch = List.of(commands);

        store.process(store.createBatch(batch));
    }

    private static ExpandedBlueprint singleInstanceBlueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, 1, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static NodeArtifactValue activeInstance() {
        return NodeArtifactValue.activeNodeArtifactValue(0, List.of());
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> replayOn(NodeId node, NodeArtifactValue value) {
        var key = NodeArtifactKey.nodeArtifactKey(node, SLICE);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static NodeArtifactValue intermittentFailure() {
        return NodeArtifactValue.failedNodeArtifactValue(new CoreError.Timeout("downstream dependency restarting"));
    }

    private static ValuePut<AppBlueprintKey, AppBlueprintValue> appBlueprintPut(ExpandedBlueprint expanded) {
        var key = AppBlueprintKey.appBlueprintKey(expanded.id());

        return new ValuePut<>(new KVCommand.Put<>(key, AppBlueprintValue.appBlueprintValue(expanded)), Option.none());
    }

    private static ValueRemove<AppBlueprintKey, AppBlueprintValue> appBlueprintRemove(BlueprintId blueprintId) {
        var key = AppBlueprintKey.appBlueprintKey(blueprintId);

        return new ValueRemove<>(new KVCommand.Remove<>(key), Option.none());
    }

    private static ValuePut<SliceTargetKey, SliceTargetValue> sliceTargetPut(BlueprintId owner) {
        var key = SliceTargetKey.sliceTargetKey(SLICE.base());
        var value = SliceTargetValue.sliceTargetValue(SLICE.version(), 3, Option.some(owner));

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ExpandedBlueprint blueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, 3, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                                        KVStore<AetherKey, AetherValue> kvStore,
                                                                                        Supplier<Set<NodeId>> coreMembers) {
        var router = MessageRouter.mutable();
        LongSupplier clock = () -> 10_000_000L;
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    coreMembers,
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set::of,
                                                    Set.of(SELF, NODE_A),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        var harness = FsmTestHarness.<ClusterDeploymentState, ClusterFsmEvent>harness("ever-active-924-" + SELF.id(), factory);

        harness.dispatch(new Activate());

        return harness;
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        private RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(Collections.emptyList());
        }

        private List<AetherKey> commandKeysFor(Artifact artifact) {
            synchronized (commands) {
                return commands.stream()
                               .map(KVCommand::key)
                               .filter(key -> key.asString().contains(artifact.asString()))
                               .toList();
            }
        }
    }

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> baseline(String datasourceName, int version) {return Promise.success(Unit.unit());}
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));}

            @Override public Option<NodeInfo> get(NodeId id) {return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));}

            @Override public int clusterSize() {return 2;}

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}

            @Override public TimeSpan pingInterval() {return timeSpan(5).seconds();}

            @Override public TimeSpan helloTimeout() {return timeSpan(5).seconds();}

            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}

            @Override public List<NodeId> topology() {return List.of(self);}
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {return null;}
        };
    }
}
