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
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
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
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #972 — every terminal outcome is bound to the publish attempt it closes.
///
/// A publish stamps an attempt id into `AppBlueprintValue`; the FSM stamps the attempt it is closing
/// into every terminal `DeploymentOutcomeValue`; and [Active#applyNotYetTerminal] reads a terminal
/// for a DIFFERENT attempt than the committed blueprint's as "this apply is not yet terminal". The
/// cases below drive the real FSM against the real fenced `KVStore`.
class AttemptBoundOutcomeTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();
    private static final Artifact SLICE_B = Artifact.artifact("com.example:slice-b:1.0.0").unwrap();
    private static final int TERMINAL_ON_REPORT = 6;
    private static final String NO_OUTCOME = "NO-OUTCOME-RECORD";
    private static final String PREVIOUS_CAUSE = "PREVIOUS-ATTEMPT";
    private static final String FIRST_ATTEMPT = "attempt-1";
    private static final String SECOND_ATTEMPT = "attempt-2";
    private static final Supplier<Set<NodeId>> RESOLVED_MEMBERSHIP = () -> Set.of(SELF, NODE_A);

    private KVStore<AetherKey, AetherValue> leaderStore;
    private HoldingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness;

    @BeforeEach
    void setUp() {
        leaderStore = storeWithLeader();
        cluster = new HoldingClusterNode(SELF, leaderStore);
        leaderHarness = leaderHarness(cluster, leaderStore, DeploymentAtomicity.ALL_OR_NOTHING);
    }

    /// #972 row 1, live leader. The publish of the second attempt lost every apply-start write to the
    /// first attempt's FAILED. The leader that saw the publish tracks it, so the apply completing
    /// writes SUCCEEDED for the SECOND attempt.
    @Test
    void liveLeader_publishThatLostTheApplyStartRace_applySucceeds_recordsSucceededForItsOwnAttempt() {
        var expanded = blueprint(3);

        seedPublishThatLostTheApplyStartRace(leaderStore, expanded);
        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded, SECOND_ATTEMPT)));
        bringUp(leaderHarness);

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("a fully ACTIVE apply must not report the previous attempt's FAILED")
                .isEqualTo(DeploymentOutcomeStatus.SUCCEEDED.name());
        assertThat(outcomeAttempt(leaderStore, expanded.id()))
                .as("and the SUCCEEDED is attributed to the attempt that completed")
                .isEqualTo(SECOND_ATTEMPT);
    }

    /// #972 row 1, after failover: the new leader never saw the publish, so only the guarded repair
    /// `recordApplyCompletionFromDurableState` can write the terminal. Before attribution its guard
    /// read the previous attempt's FAILED as this apply's terminal and refused. RED at `40dfc0519`.
    @Test
    void newLeader_publishThatLostTheApplyStartRace_applySucceeds_recordsSucceeded() {
        var expanded = blueprint(3);
        var newLeaderHarness = leaderHarness(new HoldingClusterNode(SELF, leaderStore),
                                             leaderStore,
                                             DeploymentAtomicity.ALL_OR_NOTHING);

        seedPublishThatLostTheApplyStartRace(leaderStore, expanded);
        bringUp(newLeaderHarness);

        assertThat(outcomeStatusName(leaderStore, expanded.id()))
                .as("#972 acceptance after failover: a fully ACTIVE apply must not report the previous FAILED")
                .isEqualTo(DeploymentOutcomeStatus.SUCCEEDED.name());
        assertThat(outcomeAttempt(leaderStore, expanded.id())).isEqualTo(SECOND_ATTEMPT);
    }

    /// #972 row 3: the apply fails transiently until the budget is spent. Before attribution the
    /// previous attempt's FAILED made the apply read as terminal, so exhaustion never settled and the
    /// record kept describing the previous attempt. RED at `40dfc0519`.
    @Test
    void liveLeader_publishThatLostTheApplyStartRace_transientExhaustion_settlesItsOwnAttempt() {
        var expanded = blueprint(3);

        seedPublishThatLostTheApplyStartRace(leaderStore, expanded);
        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded, SECOND_ATTEMPT)));
        exhaustRetryBudgetOn(leaderHarness, SELF, SLICE);

        assertThat(outcomeCause(leaderStore, expanded.id()))
                .as("#972 row 3: the record must stop describing the PREVIOUS attempt")
                .isNotEqualTo(PREVIOUS_CAUSE);
        assertThat(outcomeAttempt(leaderStore, expanded.id()))
                .as("the terminal is this apply's own")
                .isEqualTo(SECOND_ATTEMPT);
        assertThat(activeState(leaderHarness).permanentlyFailed()).contains(SLICE);
    }

    /// CONTROL for the repair. A FAILED for the COMMITTED attempt is a genuine failure, and the repair
    /// must not overwrite it however ACTIVE the slices look — `hasActiveInstance` counts any ACTIVE
    /// instance, including one a predecessor blueprint shares. Attribution only re-reads a terminal for
    /// another attempt.
    @Test
    void control_aFailedForTheCommittedAttempt_isNotOverwrittenByTheRepair() {
        var expanded = blueprint(3);
        var newLeaderHarness = leaderHarness(new HoldingClusterNode(SELF, leaderStore),
                                             leaderStore,
                                             DeploymentAtomicity.ALL_OR_NOTHING);

        seed(leaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded, false, FIRST_ATTEMPT)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.failed(List.of(SLICE.asString()), "genuine", 1L, 6L, FIRST_ATTEMPT)));
        bringUp(newLeaderHarness);

        assertThat(outcomeStatusName(leaderStore, expanded.id())).isEqualTo(DeploymentOutcomeStatus.FAILED.name());
    }

    /// CONTROL for the settle, the polarity opposite to row 3: a SUCCEEDED for the committed attempt
    /// is terminal, so exhaustion re-drives and never condemns.
    @Test
    void control_aSucceededForTheCommittedAttempt_isTerminal_soExhaustionDoesNotSettle() {
        var expanded = blueprint(3);

        seed(leaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded, false, SECOND_ATTEMPT)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.succeeded(1L, 6L, SECOND_ATTEMPT)));
        exhaustRetryBudgetOn(leaderHarness, SELF, SLICE);

        assertThat(activeState(leaderHarness).permanentlyFailed()).doesNotContain(SLICE);
    }

    /// SIBLING 1. A rollback built for the first attempt is still in flight when the second attempt's
    /// publish commits. Before the fence its `Remove(AppBlueprintKey)` deleted the second attempt's
    /// blueprint, leaving its IN_PROGRESS confirmed for a blueprint that no longer existed. RED at
    /// `40dfc0519`.
    @Test
    void aLateRollbackOfThePreviousAttempt_doesNotRemoveTheNewerAttemptsBlueprint() {
        var first = blueprint(3);
        var second = blueprint(2);

        applyBlueprint(leaderHarness, leaderStore, first, FIRST_ATTEMPT, 1L);
        cluster.hold();
        exhaustRetryBudgetOn(leaderHarness, SELF, SLICE);
        assertThat(cluster.heldRemovalOf(AppBlueprintKey.appBlueprintKey(first.id())))
                .as("precondition: the first attempt's rollback is built and in flight")
                .isTrue();

        // The second publish is ordered BEFORE the in-flight rollback.
        applyBlueprint(leaderHarness, leaderStore, second, SECOND_ATTEMPT, 2L);
        cluster.release();

        assertThat(committedBlueprint(leaderStore, second.id()))
                .as("the first attempt's rollback must not delete the blueprint the second publish committed")
                .isEqualTo(Option.some(second));
        assertThat(outcomeStatusName(leaderStore, second.id()))
                .as("and the second attempt stays in progress — the stale rollback wrote nothing")
                .isEqualTo(DeploymentOutcomeStatus.IN_PROGRESS.name());
        assertThat(outcomeAttempt(leaderStore, second.id())).isEqualTo(SECOND_ATTEMPT);
    }

    /// SIBLING 2. A republish while the first attempt is still in flight captures the in-flight
    /// blueprint, under the SAME id, as its `previous`. Rolling the republish back used to Put that id
    /// and Remove it in one batch, deleting the blueprint it meant to restore. RED at `40dfc0519`.
    @Test
    void rollingBackARepublishOfTheSameId_restoresThePreviousBlueprint_ratherThanDeletingIt() {
        var first = blueprint(3);
        var second = blueprint(2);

        applyBlueprint(leaderHarness, leaderStore, first, FIRST_ATTEMPT, 1L);
        applyBlueprint(leaderHarness, leaderStore, second, SECOND_ATTEMPT, 2L);
        exhaustRetryBudgetOn(leaderHarness, SELF, SLICE);

        assertThat(outcomeStatusName(leaderStore, second.id()))
                .as("precondition: the rollback ran and recorded ROLLED_BACK")
                .isEqualTo(DeploymentOutcomeStatus.ROLLED_BACK.name());
        assertThat(committedBlueprint(leaderStore, first.id()))
                .as("a same-id rollback must leave the restored previous blueprint committed")
                .isEqualTo(Option.some(first));
        assertThat(committedAttempt(leaderStore, first.id()))
                .as("restored under the rolled-back attempt, so its ROLLED_BACK attributes to it and closes it")
                .isEqualTo(Option.some(SECOND_ATTEMPT));
    }

    /// Without a committed leader record the rollback cannot be fenced; it still applies, unfenced, as
    /// it did before #972 — a missing leader record must not strand an apply outstanding.
    @Test
    void withoutACommittedLeaderRecord_theRollbackStillApplies_unfenced() {
        var store = freshStore();
        var harness = leaderHarness(new HoldingClusterNode(SELF, store), store, DeploymentAtomicity.ALL_OR_NOTHING);
        var expanded = blueprint(3);

        applyBlueprint(harness, store, expanded, FIRST_ATTEMPT, 1L);
        exhaustRetryBudgetOn(harness, SELF, SLICE);

        assertThat(committedBlueprint(store, expanded.id())).isEqualTo(Option.none());
        assertThat(outcomeStatusName(store, expanded.id())).isEqualTo(DeploymentOutcomeStatus.FAILED.name());
        assertThat(outcomeAttempt(store, expanded.id())).isEqualTo(FIRST_ATTEMPT);
    }

    /// BEST_EFFORT merge: a FAILED left by an EARLIER attempt must not lend its failing slices to this
    /// attempt's record. Before attribution the merge read them as this apply's. RED at `40dfc0519`.
    @Test
    void bestEffortFailure_doesNotMergeThePreviousAttemptsFailingSlices() {
        var harness = leaderHarness(new HoldingClusterNode(SELF, leaderStore), leaderStore, DeploymentAtomicity.BEST_EFFORT);
        var expanded = blueprint(3);

        seed(leaderStore,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded, false, SECOND_ATTEMPT)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.failed(List.of(SLICE_B.asString()), PREVIOUS_CAUSE, 1L, 6L, FIRST_ATTEMPT)));
        harness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded, SECOND_ATTEMPT)));
        exhaustRetryBudgetOn(harness, SELF, SLICE);

        assertThat(outcomeFailingSlices(leaderStore, expanded.id()))
                .as("only this attempt's failing slice")
                .containsExactly(SLICE.asString());
        assertThat(outcomeAttempt(leaderStore, expanded.id())).isEqualTo(SECOND_ATTEMPT);
    }

    /// The store exactly as `BlueprintService.confirmOutcomeStart` exhaustion leaves it: the second
    /// attempt's blueprint committed, and every apply-start write fenced out by the FIRST attempt's
    /// FAILED, which still holds the record.
    private static void seedPublishThatLostTheApplyStartRace(KVStore<AetherKey, AetherValue> store,
                                                             ExpandedBlueprint expanded) {
        seed(store,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded, false, SECOND_ATTEMPT)),
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.failed(List.of(SLICE.asString()), PREVIOUS_CAUSE, 1L, 6L, FIRST_ATTEMPT)));
    }

    private static void bringUp(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness) {
        harness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, SLICE, activeInstance())));
        harness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, SLICE, activeInstance())));
    }

    private static Option<DeploymentOutcomeValue> outcome(KVStore<AetherKey, AetherValue> store, BlueprintId blueprintId) {
        return store.get(DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId))
                    .filter(value -> value instanceof DeploymentOutcomeValue)
                    .map(value -> (DeploymentOutcomeValue) value);
    }

    private static String outcomeCause(KVStore<AetherKey, AetherValue> store, BlueprintId blueprintId) {
        return outcome(store, blueprintId).map(DeploymentOutcomeValue::cause).or(NO_OUTCOME);
    }

    private static String outcomeAttempt(KVStore<AetherKey, AetherValue> store, BlueprintId blueprintId) {
        return outcome(store, blueprintId).map(DeploymentOutcomeValue::attemptId).or(NO_OUTCOME);
    }

    private static List<String> outcomeFailingSlices(KVStore<AetherKey, AetherValue> store, BlueprintId blueprintId) {
        return outcome(store, blueprintId).map(DeploymentOutcomeValue::failingSlices).or(List.of());
    }

    private static Option<String> committedAttempt(KVStore<AetherKey, AetherValue> store, BlueprintId id) {
        return store.get(AppBlueprintKey.appBlueprintKey(id))
                    .filter(value -> value instanceof AppBlueprintValue)
                    .map(value -> ((AppBlueprintValue) value).attemptId());
    }

    private static KVStore<AetherKey, AetherValue> storeWithLeader() {
        var store = freshStore();

        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SELF, 1)))));

        return store;
    }

    private static ClusterDeploymentState.Active activeState(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness) {
        return (ClusterDeploymentState.Active) harness.state();
    }

    /// Returns the outcome status NAME, or the sentinel [#NO_OUTCOME] when no record exists.
    ///
    /// A String rather than an `Option`, because "no record" and "SUCCEEDED" are the two states the
    /// apply marker distinguishes and they must be comparable as VALUES — an assertion that only
    /// checks presence cannot tell a stale terminal from a fresh one.
    private static String outcomeStatusName(KVStore<AetherKey, AetherValue> store, BlueprintId blueprintId) {
        return store.get(DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId))
                    .filter(value -> value instanceof DeploymentOutcomeValue)
                    .map(value -> ((DeploymentOutcomeValue) value).status())
                    .map(DeploymentOutcomeStatus::name)
                    .or(NO_OUTCOME);
    }

    /// Mirrors what a blueprint publish does in production: `BlueprintService` writes
    /// `AppBlueprintKey` through consensus, and the FSM sees the resulting notification. Dispatching
    /// only the notification would leave the store empty and make every apply look un-attributable.
    private static void applyBlueprint(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness,
                                       KVStore<AetherKey, AetherValue> store,
                                       ExpandedBlueprint expanded,
                                       String attemptId,
                                       long outcomeVersion) {
        seed(store,
             new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                 AppBlueprintValue.appBlueprintValue(expanded, false, attemptId)),
             // #963: production writes IN_PROGRESS in the SAME batch as the blueprint Put
             // (`BlueprintService.buildAllCommands` / `storeBlueprintWithKey`). A fixture that seeded
             // only the blueprint would model a state production never produces, and — since the
             // settle is now gated on the PRESENCE of that record — would make every "does settle"
             // test silently unreachable.
             new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                 DeploymentOutcomeValue.inProgress(1L, outcomeVersion, attemptId)));
        harness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded, attemptId)));
    }

    private static void exhaustRetryBudgetOn(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness,
                                             NodeId node,
                                             Artifact artifact) {
        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            harness.dispatch(new NodeArtifactPutReceived(replayOn(node, artifact, intermittentFailure())));
        }
    }

    private static KVStore<AetherKey, AetherValue> freshStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @SafeVarargs
    private static void seed(KVStore<AetherKey, AetherValue> store, KVCommand<AetherKey>... commands) {
        var batch = List.of(commands);

        store.process(store.createBatch(batch));
    }

    /// The same id at every call; `instances` is what tells two attempts' blueprints apart.
    private static ExpandedBlueprint blueprint(int instances) {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, instances, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static Option<ExpandedBlueprint> committedBlueprint(KVStore<AetherKey, AetherValue> store, BlueprintId id) {
        return store.get(AppBlueprintKey.appBlueprintKey(id))
                    .filter(value -> value instanceof AppBlueprintValue)
                    .map(value -> ((AppBlueprintValue) value).blueprint());
    }

    private static NodeArtifactValue activeInstance() {
        return NodeArtifactValue.activeNodeArtifactValue(0, List.of());
    }

    private static NodeArtifactValue intermittentFailure() {
        return NodeArtifactValue.failedNodeArtifactValue(new CoreError.Timeout("downstream dependency restarting"),
                                                         Unrecognised.RETRY);
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> replayOn(NodeId node, Artifact artifact, NodeArtifactValue value) {
        var key = NodeArtifactKey.nodeArtifactKey(node, artifact);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValuePut<AppBlueprintKey, AppBlueprintValue> appBlueprintPut(ExpandedBlueprint expanded, String attemptId) {
        var key = AppBlueprintKey.appBlueprintKey(expanded.id());

        return new ValuePut<>(new KVCommand.Put<>(key, AppBlueprintValue.appBlueprintValue(expanded, false, attemptId)),
                              Option.none());
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                                        KVStore<AetherKey, AetherValue> kvStore,
                                                                                        DeploymentAtomicity atomicity) {
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
                                                    RESOLVED_MEMBERSHIP,
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set::of,
                                                    Set.of(SELF, NODE_A),
                                                    atomicity,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        var harness = FsmTestHarness.<ClusterDeploymentState, ClusterFsmEvent>harness("apply-outstanding-922-" + SELF.id(), factory);

        harness.dispatch(new Activate());

        return harness;
    }

    /// Applies what the leader submits into the store, as consensus does — unless HELD, in which case
    /// batches queue until [#release], so a test can order another writer's batch before them.
    private static final class HoldingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final List<List<KVCommand<AetherKey>>> held = new ArrayList<>();
        private boolean holding;

        private HoldingClusterNode(NodeId self, KVStore<AetherKey, AetherValue> kvStore) {
            this.self = self;
            this.kvStore = kvStore;
        }

        void hold() {
            holding = true;
        }

        void release() {
            holding = false;
            held.forEach(batch -> kvStore.process(kvStore.createBatch(batch)));
            held.clear();
        }

        /// A removal of `key` in a held batch — a bare `Remove`, or a deleting mutation of a transaction.
        boolean heldRemovalOf(AetherKey key) {
            return held.stream()
                       .flatMap(List::stream)
                       .anyMatch(command -> removes(command, key));
        }

        private static boolean removes(KVCommand<AetherKey> command, AetherKey key) {
            return switch (command) {
                case KVCommand.Remove<AetherKey> remove -> remove.key().equals(key);
                case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                          .stream()
                                                                                          .anyMatch(mutation -> mutation.key().equals(key)
                                                                                                                && mutation.replacement().isEmpty());
                default -> false;
            };
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            if (holding) {
                held.add(List.copyOf(batch));
            } else {
                kvStore.process(kvStore.createBatch(batch));
            }

            return Promise.success(Collections.emptyList());
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
