// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
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

/// #1117 — what the leader's "deployed" verdict asserts.
///
/// The verdict fires on the FIRST ACTIVE instance of each slice (`trackBlueprintSliceActive`, and the SUCCEEDED
/// outcome by the same rule). It used to read "fully deployed — all N slices active", which operators read as "every
/// instance ACTIVE" while two or four other instances were still activating (Heavy runs 35647686220, 35552814436).
/// The text now states the part it asserts ("at least one ACTIVE instance") and the part it does not ("n of m").
class DeployedVerdictTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();

    private KVStore<AetherKey, AetherValue> leaderStore;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness;
    private ExpandedBlueprint expanded;
    private RecordingClusterNode recorder;
    private final List<String> infoLines = java.util.Collections.synchronizedList(new ArrayList<>());
    private Runnable detachLog = () -> {};

    @BeforeEach
    void setUp() {
        leaderStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        recorder = new RecordingClusterNode(SELF, leaderStore);
        leaderHarness = leaderHarness(recorder, leaderStore);
        detachLog = capturingInfo(infoLines);
        expanded = blueprint();
        KVCommand<AetherKey> command = new KVCommand.Put<>(AppBlueprintKey.appBlueprintKey(expanded.id()),
                                                           AppBlueprintValue.appBlueprintValue(expanded));
        KVCommand<AetherKey> started = new KVCommand.Put<>(DeploymentOutcomeKey.deploymentOutcomeKey(expanded.id()),
                                                           AetherValue.DeploymentOutcomeValue.inProgress(1L));

        leaderStore.process(leaderStore.createBatch(List.of(command, started)));
        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
    }

    @AfterEach
    void tearDown() {
        detachLog.run();
    }

    @Test
    void theLeaderLogsTheVerdictOnTheFirstActiveInstance_withTheCountAndNotTheOldWording() {
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        assertThat(infoLines)
                .as("the call site logs the verdict, not the old 'fully deployed — all N slices active'")
                .anySatisfy(line -> assertThat(line).contains("deployed: each of its 1 slices has at least one ACTIVE instance")
                                                    .contains(SLICE.asString() + ": 1 of 3 instances ACTIVE"))
                .noneSatisfy(line -> assertThat(line).contains("fully deployed"));
    }

    @Test
    void noInstanceActive_noSucceededOutcome_andTheFirstActiveWritesIt() {
        assertThat(recorder.outcomeFor(expanded.id()).stream().filter(o -> o.status() == DeploymentOutcomeStatus.SUCCEEDED))
                .as("nothing is ACTIVE yet: the apply has not completed")
                .isEmpty();

        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        assertThat(recorder.outcomeFor(expanded.id()).stream().filter(o -> o.status() == DeploymentOutcomeStatus.SUCCEEDED))
                .as("the first ACTIVE instance of the only slice completes the apply")
                .hasSize(1);
    }

    @Test
    void anInstanceThatIsOnlyActivating_isNotCountedAsActive() {
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVATING))));

        assertThat(active().deployedVerdict(expanded.id(), Set.of(SLICE)))
                .contains(SLICE.asString() + ": 1 of 3 instances ACTIVE");
    }

    @Test
    void theVerdictCountsTheSlicesItIsGiven() {
        assertThat(active().deployedVerdict(expanded.id(), Set.of(SLICE))).contains("each of its 1 slices");
        assertThat(active().deployedVerdict(expanded.id(), Set.of())).contains("each of its 0 slices");
    }

    @Test
    void oneOfThreeInstancesActive_verdictStatesAtLeastOneAndTheCount_andNeverClaimsAll() {
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));

        var verdict = active().deployedVerdict(expanded.id(), Set.of(SLICE));

        assertThat(verdict)
                .as("#1117: one ACTIVE instance of a 3-instance slice must be reported as exactly that")
                .contains("at least one ACTIVE instance")
                .contains(SLICE.asString() + ": 1 of 3 instances ACTIVE")
                .contains("other instances may still be activating")
                .doesNotContain("fully deployed")
                .doesNotContain("all 1 slices active");
    }

    @Test
    void secondInstanceActive_countFollowsTheLeadersMirror() {
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(SELF, activeInstance())));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOn(NODE_A, activeInstance())));

        assertThat(active().deployedVerdict(expanded.id(), Set.of(SLICE)))
                .contains(SLICE.asString() + ": 2 of 3 instances ACTIVE");
    }

    private static Runnable capturingInfo(List<String> sink) {
        var context = (LoggerContext) LogManager.getContext(false);
        var loggerConfig = context.getConfiguration().getLoggerConfig(ClusterDeploymentState.Active.class.getName());
        var previous = loggerConfig.getLevel();
        var appender = new AbstractAppender("deployed-verdict-capture", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (ClusterDeploymentState.Active.class.getName().equals(event.getLoggerName())) {
                    sink.add(event.getMessage().getFormattedMessage());
                }
            }
        };

        appender.start();
        loggerConfig.addAppender(appender, Level.INFO, null);
        loggerConfig.setLevel(Level.INFO);
        context.updateLoggers();

        return () -> {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(previous);
            context.updateLoggers();
            appender.stop();
        };
    }

    private ClusterDeploymentState.Active active() {
        return (ClusterDeploymentState.Active) leaderHarness.state();
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

    private static ValuePut<AppBlueprintKey, AppBlueprintValue> appBlueprintPut(ExpandedBlueprint expanded) {
        var key = AppBlueprintKey.appBlueprintKey(expanded.id());

        return new ValuePut<>(new KVCommand.Put<>(key, AppBlueprintValue.appBlueprintValue(expanded)), Option.none());
    }

    private static ExpandedBlueprint blueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, 3, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                                        KVStore<AetherKey, AetherValue> kvStore) {
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
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        private RecordingClusterNode(NodeId self, KVStore<AetherKey, AetherValue> kvStore) {
            this.self = self;
            this.kvStore = kvStore;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        /// Records what the leader submits AND applies it, because a real `ClusterNode.apply`
        /// reaches consensus and lands in every replica's local `KVStore`, the leader's own
        /// included. A recorder that only records leaves the store empty, so the durable apply
        /// marker could never be written by the production path and could not be read back.
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            kvStore.process(kvStore.createBatch(batch));

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

        /// Every LOAD or ACTIVATE the leader issued for the given artifact — a re-drive. UNLOAD and
        /// Remove are teardown and deliberately excluded (#1068's orphan sweep issues them).
        private List<AetherKey> startCommandsFor(Artifact artifact) {
            synchronized (commands) {
                return commands.stream()
                               .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                  && put.value() instanceof NodeArtifactValue value
                                                  && (value.state() == SliceState.LOAD || value.state() == SliceState.ACTIVATE))
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
