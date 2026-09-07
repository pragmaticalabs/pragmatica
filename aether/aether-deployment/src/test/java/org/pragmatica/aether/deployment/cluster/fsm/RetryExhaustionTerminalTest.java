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
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
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

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #922 — retry exhaustion on the transient deployment path must reach a TERMINAL state.
///
/// Before this fix it did not. `logMaxRetriesExceeded` cleared the retry counter and routed
/// `DeploymentFailed` but never added the artifact to `permanentlyFailed`, and that is not a
/// resting state:
///
///   1. `handleSliceFailure` issues an unload on EVERY failure;
///   2. the node's removal of the `NodeArtifactKey` reaches `handleSliceNodeRemoval`, which —
///      finding the artifact not permanently failed — schedules a reconcile;
///   3. `reconcileBlueprint` is gated on `permanentlyFailed` and nothing else, so it redeploys the
///      artifact and `retryCounters.merge` restarts at 1.
///
/// An intermittent cause that never settles was therefore re-driven at roughly 1 Hz for the life of
/// the cluster: no terminal state, no rollback, no record, consensus round-trips forever. The
/// ticket's own framing ("abandons the blueprint, leaves it in place") was one step milder than the
/// truth.
///
/// **The cause driven here is `CoreError.Timeout`, deliberately.** It is one of the two causes the
/// ticket names as already legitimately `Intermittent` (the other being resource capacity), it
/// predates and is independent of #916's typing work, and using it keeps this pin honest: the
/// livelock is reachable through causes nobody reclassified, so the fix must not depend on #916.
///
/// Only the leader FSM is driven. The node half is irrelevant to this defect — what matters is what
/// the leader does with a repeated non-fatal failure report, so the report is replayed directly.
class RetryExhaustionTerminalTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();

    /// `ClusterDeploymentState.Active.MAX_RETRIES` is private; 5 retries means the SIXTH reported
    /// failure is the one that spends the budget.
    private static final int TERMINAL_ON_REPORT = 6;

    /// Twice the budget. The pre-fix behaviour was unbounded, so any finite cap distinguishes it.
    private static final int REPORT_CAP = 12;

    private RecordingClusterNode leaderSideCluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness;

    @BeforeEach
    void setUp() {
        leaderSideCluster = new RecordingClusterNode(SELF);
        leaderHarness = leaderHarness(leaderSideCluster);
    }

    /// The assertion is the ATTEMPT COUNT at which the terminal is reached, not merely that a
    /// failure eventually happened. Both halves are load-bearing:
    ///
    ///   - a test asserting only "the blueprint is rolled back" would pass against a fix that rolled
    ///     back on the FIRST failure, destroying the bounded retry this path exists to provide;
    ///   - a test asserting only "it failed eventually" cannot distinguish a bounded terminal from
    ///     an unbounded loop at all, which is the whole defect.
    ///
    /// Reverting the production hunk leaves `reportsUntilTerminal` at 0 after `REPORT_CAP` reports —
    /// the pre-fix behaviour reported as itself.
    @Test
    void intermittentFailureThatNeverSettles_reachesTerminalRollback_withinTheRetryBudget() {
        var expanded = blueprint();
        var blueprintKey = AppBlueprintKey.appBlueprintKey(expanded.id());
        var intermittent = intermittentFailure();

        assertThat(intermittent.fatal())
                .as("precondition: the cause driven below must be INTERMITTENT, so every report takes "
                    + "handleTransientFailure's branch. If this flips, the test silently becomes a "
                    + "test of the deterministic branch, which already had a terminal")
                .isFalse();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));

        var reportsUntilTerminal = 0;

        for (var report = 1; report <= REPORT_CAP; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOf(intermittent)));
            if (leaderSideCluster.removeKeys().contains(blueprintKey)) {
                reportsUntilTerminal = report;
                break;
            }
        }

        assertThat(reportsUntilTerminal)
                .as("#922: an intermittent cause that never settles must reach a terminal state. At 0 "
                    + "the deployment was still being re-driven after %d reported failures — "
                    + "unbounded, no rollback, no terminal record, which is the livelock",
                    REPORT_CAP)
                .isNotZero();
        assertThat(reportsUntilTerminal)
                .as("the terminal must arrive when the retry budget is spent and NOT before — rolling "
                    + "back earlier would destroy the bounded retry this path exists to provide")
                .isEqualTo(TERMINAL_ON_REPORT);

        leaderSideCluster.commands.clear();
        ((ClusterDeploymentState.Active) leaderHarness.state()).reconcile();

        assertThat(leaderSideCluster.commandKeysFor(SLICE))
                .as("and the terminal must HOLD: a reconcile after exhaustion must not re-drive the "
                    + "artifact, which is the step that turned the old exhaustion into a loop")
                .isEmpty();
    }

    /// #922 acceptance, second half: the operator must be able to SEE that the deployment did not
    /// apply. A rollback that leaves no record is silence of a different shape, so this asserts the
    /// explicit `DeploymentOutcomeValue` rather than only the blueprint's removal.
    @Test
    void intermittentFailureThatNeverSettles_recordsAnExplicitFailedOutcome() {
        var expanded = blueprint();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));

        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOf(intermittentFailure())));
        }

        assertThat(leaderSideCluster.outcomeFor(expanded.id()))
                .as("#922: exhaustion must leave an explicit FAILED deployment outcome the operator "
                    + "can read, not an unrecorded disappearance")
                .isNotEmpty()
                .allSatisfy(outcome -> assertThat(outcome.failingSlices()).contains(SLICE.asString()));
    }

    /// #924 review, BLOCKING — the reverse risk, and the reason the terminal needed a scope.
    ///
    /// `settleAsPermanentlyFailed` was lifted from the DETERMINISTIC branch, where a cluster-wide
    /// inference is sound: a deterministic failure is node-independent, so failing on one node does
    /// mean the artifact is bad everywhere. That inference does not transfer to the transient
    /// branch — a transient failure on node A says nothing about node B. Carrying it across is the
    /// actual defect: `retryCounters` is per artifact AND node, while `permanentlyFailed` is a
    /// cluster-wide `Set<Artifact>`.
    ///
    /// Its consequence is worse than the livelock it replaced. The livelock was noisy and kept
    /// trying; this is silent and terminal on a deployment that had been healthy. The blueprint has
    /// already left `inFlightBlueprints`, so `rollbackBlueprintForArtifact` matches nothing, no
    /// FAILED outcome is written, and the artifact's last recorded outcome still reads SUCCEEDED
    /// while reconcile quietly refuses to replace the lost instance forever.
    ///
    /// **This asserts the RECOVERY, not the absence of a rollback.** "No rollback happened" is
    /// equally true of a system that has silently stopped doing anything, which is precisely the
    /// bug — so it would pin nothing. What is asserted instead is that reconciliation still
    /// re-drives the artifact after exhaustion, and that the lost instance actually comes back.
    @Test
    void exhaustionOnOneNode_whileTheArtifactIsActiveElsewhere_stillRecoversTheLostInstance() {
        var expanded = blueprint();
        var active = deployOnBothNodesThenExhaustSelf(expanded);

        assertThat(active.getCurrentInstances(SLICE))
                .as("the instance on NODE_A never failed and must survive a sibling node's exhausted "
                    + "budget — a cluster-wide terminal would have condemned the whole artifact")
                .extracting(SliceNodeKey::nodeId)
                .contains(NODE_A);

        leaderSideCluster.commands.clear();
        active.reconcile();

        assertThat(leaderSideCluster.commandKeysFor(SLICE))
                .as("#924: after the per-node budget is spent on ONE node, reconciliation must still "
                    + "act on the artifact. Empty here is the wedge: auto-heal, rebalancing and "
                    + "scale-up dead forever, with the standing outcome record still reading "
                    + "SUCCEEDED and nothing written to say otherwise")
                .isNotEmpty();

        // The transient clears and the node reports the slice up again.
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        assertThat(active.getCurrentInstances(SLICE))
                .as("recovery, stated positively: once the transient clears the artifact is deployable "
                    + "again and runs on BOTH nodes. This is the assertion a silently-wedged cluster "
                    + "cannot satisfy")
                .extracting(SliceNodeKey::nodeId)
                .containsExactlyInAnyOrder(SELF, NODE_A);
    }

    /// Consistency check accompanying the pin above rather than a second pin: the standing outcome
    /// record must not be contradicted. Deliberately NOT load-bearing — a wedged cluster also writes
    /// no FAILED record, so this cannot discriminate on its own and is not relied on to.
    @Test
    void exhaustionOnOneNode_whileTheArtifactIsActiveElsewhere_leavesTheSucceededOutcomeStanding() {
        var expanded = blueprint();

        deployOnBothNodesThenExhaustSelf(expanded);

        assertThat(leaderSideCluster.outcomeFor(expanded.id()))
                .as("the blueprint genuinely did deploy, so its outcome record must still say so")
                .isNotEmpty()
                .allSatisfy(outcome -> assertThat(outcome.status())
                        .isEqualTo(DeploymentOutcomeStatus.SUCCEEDED));
    }

    /// Drives the blueprint to fully deployed on both nodes — which retires it from
    /// `inFlightBlueprints` and writes SUCCEEDED — then spends the entire retry budget on SELF with
    /// an intermittent cause, leaving NODE_A untouched.
    private ClusterDeploymentState.Active deployOnBothNodesThenExhaustSelf(ExpandedBlueprint expanded) {
        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        var active = (ClusterDeploymentState.Active) leaderHarness.state();

        assertThat(active.getCurrentInstances(SLICE))
                .as("precondition: the artifact must be RUNNING on both nodes before the transient. "
                    + "Without it this degenerates into the never-succeeded case the pins above "
                    + "already cover, and would pass against the very defect it exists to catch")
                .hasSize(2);

        for (var report = 1; report <= TERMINAL_ON_REPORT; report++) {
            leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, intermittentFailure())));
        }

        return active;
    }

    /// A slice instance reported ACTIVE. `methods` is empty deliberately — these tests care that the
    /// state is ACTIVE, not that the instance published endpoints.
    private static NodeArtifactValue activeInstance() {
        return NodeArtifactValue.activeNodeArtifactValue(0, List.of());
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> replayOn(NodeId node, NodeArtifactValue value) {
        var key = NodeArtifactKey.nodeArtifactKey(node, SLICE);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    /// A failure the leader is REQUIRED to treat as retryable, built from a cause that was already
    /// `Intermittent` before #916 and is untouched by it.
    private static NodeArtifactValue intermittentFailure() {
        return NodeArtifactValue.failedNodeArtifactValue(new CoreError.Timeout("consensus stalled"));
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> replayOf(NodeArtifactValue value) {
        var key = NodeArtifactKey.nodeArtifactKey(SELF, SLICE);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValuePut<AppBlueprintKey, AppBlueprintValue> appBlueprintPut(ExpandedBlueprint expanded) {
        var key = AppBlueprintKey.appBlueprintKey(expanded.id());

        return new ValuePut<>(new KVCommand.Put<>(key, AppBlueprintValue.appBlueprintValue(expanded)), Option.none());
    }

    private static ExpandedBlueprint blueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, 3, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness(ClusterNode<KVCommand<AetherKey>> cluster) {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
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
        var harness = FsmTestHarness.<ClusterDeploymentState, ClusterFsmEvent>harness("retry-exhaustion-leader-" + SELF.id(),
                                                                                      factory);

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

        private List<AetherKey> removeKeys() {
            synchronized (commands) {
                return commands.stream()
                               .filter(command -> command instanceof KVCommand.Remove<AetherKey>)
                               .map(KVCommand::key)
                               .toList();
            }
        }

        /// Every recorded command — Put or Remove — whose key names the given artifact. Used to
        /// assert that a reconcile did NOT re-drive an artifact that has reached its terminal.
        private List<AetherKey> commandKeysFor(Artifact artifact) {
            synchronized (commands) {
                return commands.stream()
                               .map(KVCommand::key)
                               .filter(key -> key.asString()
                                                 .contains(artifact.asString()))
                               .toList();
            }
        }

        /// Every `DeploymentOutcomeValue` written for the given blueprint.
        private List<DeploymentOutcomeValue> outcomeFor(BlueprintId blueprintId) {
            var key = DeploymentOutcomeKey.deploymentOutcomeKey(blueprintId);

            synchronized (commands) {
                return commands.stream()
                               .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                  && put.key().equals(key))
                               .map(command -> ((KVCommand.Put<AetherKey, ?>) command).value())
                               .filter(value -> value instanceof DeploymentOutcomeValue)
                               .map(value -> (DeploymentOutcomeValue) value)
                               .toList();
            }
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
