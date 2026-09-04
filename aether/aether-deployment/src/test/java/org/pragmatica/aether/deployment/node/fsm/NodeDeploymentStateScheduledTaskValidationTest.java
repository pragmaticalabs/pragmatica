// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Covers #273's validation gate: an invalid interval/cron string must be rejected at
/// activation time — before the [KVCommand.Put] that publishes [AetherValue.ScheduledTaskValue]
/// — not merely WARN-logged at timer start. Blueprint-DSL validation runs strictly earlier and
/// has no access to the per-node [ScheduleConfig] (it is node-local config, resolved only here),
/// so [NodeDeploymentState.Active#buildValidatedScheduledTaskPutCommand] is the earliest real
/// gate. That method is `private`, so it is reached the same way
/// [ScheduledTaskRoutesExecutionsByNodeTest] reaches its private route handler: a one-time
/// reflection bridge on the live `Active` state (built via [FsmTestHarness], same scaffolding
/// as the sibling pause-preservation suite), rather than adding a `*ForTest` accessor to
/// production code.
///
/// The failure's onward propagation (composite [Result#allOf], `.withFailure` into
/// `handleActivationFailure`, landing in `NodeArtifactValue#failureReason` and surfaced by
/// `ClusterEventAggregator.handleDeploymentFailed` as a WARNING `DeploymentFailed` event) is
/// EXISTING, unmodified plumbing — not re-verified here; see the doc comment on
/// `NodeDeploymentState.Active#doPublishScheduledTasks` [mechanism: composite Result.allOf
/// propagates through the existing activation-failure path unchanged].
class NodeDeploymentStateScheduledTaskValidationTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final String SECTION = "click-events";
    private static final MethodName METHOD = MethodName.methodName("onTick").unwrap();

    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        ConfigService.setInstance(new StubConfigService());

        var router = MessageRouter.mutable();
        KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        SliceStore sliceStore = stubSliceStore();
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, sliceStore);
        harness = FsmTestHarness.harness("ndm-scheduled-task-validation-test-" + SELF.id(), factory);
        harness.dispatch(new QuorumEstablished());
    }

    @AfterEach
    void tearDown() {
        // `sliceConfigService` falls back to this process-global singleton whenever the stub
        // SliceStore reports no slice composite (as it always does here) — must not leak into
        // other test classes sharing the JVM.
        ConfigService.clear();
    }

    @Nested
    class InvalidScheduleRejection {
        @Test
        void invalidInterval_rejectedBeforeKvPut_withNamedCause() {
            var config = new ScheduleConfig("5x", "", ExecutionMode.ALL);

            var result = buildCommand(config);

            assertThat(result.isFailure())
                    .as("an invalid interval string must never reach the KV Put")
                    .isTrue();

            result.onFailure(cause -> assertThat(cause.message())
                    .as("rejection must name the task, the offending string, and the parser's message")
                    .contains(SECTION + "." + METHOD.name())
                    .contains("5x")
                    .contains("Invalid interval format"));
        }

        @Test
        void invalidCron_rejectedBeforeKvPut_withNamedCause() {
            var config = new ScheduleConfig("", "not-a-cron", ExecutionMode.ALL);

            var result = buildCommand(config);

            assertThat(result.isFailure())
                    .as("an invalid cron string must never reach the KV Put")
                    .isTrue();

            result.onFailure(cause -> assertThat(cause.message())
                    .as("rejection must name the task, the offending string, and the parser's message")
                    .contains(SECTION + "." + METHOD.name())
                    .contains("not-a-cron")
                    .contains("exactly 5 fields"));
        }
    }

    @Nested
    class ValidScheduleAcceptance {
        @Test
        void validInterval_buildsPutCommandForTheScheduledTaskKey() {
            var config = new ScheduleConfig("30s", "", ExecutionMode.ALL);

            var result = buildCommand(config);

            assertThat(result.isSuccess())
                    .as("a valid interval must build the KV Put command")
                    .isTrue();

            result.onSuccess(command -> {
                assertThat(command).isInstanceOf(KVCommand.Put.class);
                assertThat(command.key()).isEqualTo(ScheduledTaskKey.scheduledTaskKey(SECTION, ARTIFACT, METHOD));
            });
        }

        @Test
        void validCron_buildsPutCommandForTheScheduledTaskKey() {
            var config = new ScheduleConfig("", "0 * * * *", ExecutionMode.ALL);

            var result = buildCommand(config);

            assertThat(result.isSuccess())
                    .as("a valid cron expression must build the KV Put command")
                    .isTrue();

            result.onSuccess(command -> assertThat(command.key())
                    .isEqualTo(ScheduledTaskKey.scheduledTaskKey(SECTION, ARTIFACT, METHOD)));
        }
    }

    /// SHOULD-FIX from the #841 review: the per-entry gate above ([InvalidScheduleRejection]) only
    /// proves a SINGLE bad entry is rejected. The actual composition risk lives one level up, in
    /// `doPublishScheduledTasks`'s loop-and-[Result#allOf] — a regression there (e.g. `findFirst`
    /// short-circuiting, or only the last entry surviving the loop) would stay invisible to a test
    /// that calls `buildValidatedScheduledTaskPutCommand` directly and re-aggregates with a
    /// hand-built `Result.allOf` in the test itself, since that re-tests the library primitive, not
    /// the production loop that feeds it. This suite instead drives `doPublishScheduledTasks(Artifact,
    /// Slice)` for real, through the classpath-manifest mechanism it actually reads
    /// ([TwoScheduleStubSlice] + `META-INF/slice/TwoScheduleMarkerSlice.manifest` under
    /// `src/test/resources`), with two manifest entries bound (via [TwoSectionConfigService]) to two
    /// distinct invalid [ScheduleConfig]s — one bad interval, one bad cron.
    @Nested
    class MultiEntryScheduleValidation {
        @Test
        void twoInvalidScheduleEntriesInOneSlice_bothReportedByResultAllOf() {
            ConfigService.setInstance(new TwoSectionConfigService(Map.of(
                    "bad-interval-section", new ScheduleConfig("5x", "", ExecutionMode.ALL),
                    "bad-cron-section", new ScheduleConfig("", "not-a-cron", ExecutionMode.ALL))));

            var result = invokeDoPublishScheduledTasks(activeState(), new TwoScheduleStubSlice()).await();

            assertThat(result.isFailure())
                    .as("two invalid schedule entries in one slice must both fail activation, not just one")
                    .isTrue();

            result.onFailure(cause -> assertThat(cause.message())
                    .as("Result.allOf must aggregate BOTH invalid entries, not short-circuit on the first")
                    .contains("bad-interval-section.onTickA")
                    .contains("5x")
                    .contains("Invalid interval format")
                    .contains("bad-cron-section.onTickB")
                    .contains("not-a-cron")
                    .contains("exactly 5 fields"));
        }
    }

    private Result<KVCommand<AetherKey>> buildCommand(ScheduleConfig config) {
        return invokeBuildValidatedScheduledTaskPutCommand(activeState(), config);
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    /// Package-private bridge onto `Active#buildValidatedScheduledTaskPutCommand` — a `private`
    /// method taking a `private` nested record (`ScheduledTaskManifestEntry`) as one of its
    /// arguments. Mirrors `ScheduledTaskRoutesExecutionsByNodeTest#invokeViaBridge`: one-time
    /// reflection call site, low risk, avoids exposing a `*ForTest` accessor on production code.
    @SuppressWarnings("unchecked")
    private static Result<KVCommand<AetherKey>> invokeBuildValidatedScheduledTaskPutCommand(NodeDeploymentState.Active active,
                                                                                             ScheduleConfig config) {
        try {
            Class<?> entryClass = Arrays.stream(NodeDeploymentState.Active.class.getDeclaredClasses())
                                         .filter(c -> c.getSimpleName().equals("ScheduledTaskManifestEntry"))
                                         .findFirst()
                                         .orElseThrow(() -> new AssertionError("ScheduledTaskManifestEntry nested record not found"));

            Constructor<?> entryCtor = entryClass.getDeclaredConstructor(String.class, MethodName.class);
            entryCtor.setAccessible(true);
            Object entry = entryCtor.newInstance(SECTION, METHOD);

            Method m = NodeDeploymentState.Active.class.getDeclaredMethod("buildValidatedScheduledTaskPutCommand",
                                                                          Artifact.class,
                                                                          entryClass,
                                                                          ScheduleConfig.class);
            m.setAccessible(true);

            return (Result<KVCommand<AetherKey>>) m.invoke(active, ARTIFACT, entry, config);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke buildValidatedScheduledTaskPutCommand: " + e.getMessage(), e);
        }
    }

    /// Package-private bridge onto `Active#doPublishScheduledTasks` — same one-time reflection
    /// rationale as [#invokeBuildValidatedScheduledTaskPutCommand], but reaching the multi-entry
    /// loop itself rather than the per-entry validator, so [MultiEntryScheduleValidation] proves
    /// the actual production aggregation rather than re-implementing it in the test.
    @SuppressWarnings("unchecked")
    private static Promise<Unit> invokeDoPublishScheduledTasks(NodeDeploymentState.Active active, Slice slice) {
        try {
            Method m = NodeDeploymentState.Active.class.getDeclaredMethod("doPublishScheduledTasks", Artifact.class, Slice.class);
            m.setAccessible(true);

            return (Promise<Unit>) m.invoke(active, ARTIFACT, slice);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke doPublishScheduledTasks: " + e.getMessage(), e);
        }
    }

    /// Marker interface with no members — its only purpose is to be a NON-`Slice` interface that
    /// [TwoScheduleStubSlice] implements, so `readReactiveBindingsFromManifest`'s
    /// `slice.getClass().getInterfaces()` scan (which explicitly skips `Slice.class` itself) has a
    /// name to resolve `META-INF/slice/TwoScheduleMarkerSlice.manifest` against. There is no
    /// test-only seam for feeding manifest entries directly, so this is the classpath-resource
    /// route real generated slices use, minus the annotation processor.
    private interface TwoScheduleMarkerSlice {}

    private static final class TwoScheduleStubSlice implements Slice, TwoScheduleMarkerSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    /// Binds two distinct config sections to two distinct (invalid) [ScheduleConfig]s.
    /// [StubConfigService]'s single fixed binding cannot express this — that stub exists for tests
    /// that hand a [ScheduleConfig] straight to the per-entry validator, whereas this test drives
    /// BOTH manifest entries through `doPublishScheduledTasks`'s real `resolveScheduleConfig` calls,
    /// one per section.
    private static final class TwoSectionConfigService implements ConfigService {
        private final Map<String, ScheduleConfig> bindings;

        TwoSectionConfigService(Map<String, ScheduleConfig> bindings) {
            this.bindings = bindings;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Result<T> config(String section, Class<T> configClass) {
            var config = bindings.get(section);

            return config == null
                   ? (Result<T>) Causes.cause("TwoSectionConfigService has no binding for " + section).result()
                   : (Result<T>) Result.success(config);
        }

        @Override public boolean hasSection(String section) {
            return bindings.containsKey(section);
        }

        @Override public Option<String> getString(String key) {
            return Option.none();
        }

        @Override public Option<Integer> getInt(String key) {
            return Option.none();
        }

        @Override public Option<Boolean> getBoolean(String key) {
            return Option.none();
        }
    }

    /// Minimal stub returning a caller-supplied [ScheduleConfig] for any section — the tests
    /// build the config inline and pass it straight through, so a single fixed binding per test
    /// is enough; failing lookups (unused here) return a synthetic cause.
    private static final class StubConfigService implements ConfigService {
        @SuppressWarnings("unchecked")
        @Override
        public <T> Result<T> config(String section, Class<T> configClass) {
            return (Result<T>) Causes.cause("StubConfigService has no static binding for " + section).result();
        }

        @Override public boolean hasSection(String section) {
            return false;
        }

        @Override public Option<String> getString(String key) {
            return Option.none();
        }

        @Override public Option<Integer> getInt(String key) {
            return Option.none();
        }

        @Override public Option<Boolean> getBoolean(String key) {
            return Option.none();
        }
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> store,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             SliceStore sliceStore) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                sliceStore,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                store,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    private static SliceStore stubSliceStore() {
        return new SliceStore() {
            @Override public List<LoadedSlice> loaded() {
                return List.of();
            }

            @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<Unit> unloadSlice(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
                // No slice composite → `sliceConfigService` falls back to the global
                // `ConfigService.instance()` singleton this test installs in `setUp`.
                return Option.none();
            }
        };
    }

    private static ClusterNode<KVCommand<AetherKey>> stubClusterNode(NodeId self) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return self;
            }

            @Override public TopologyManager topologyManager() {
                return stubTopologyManager(self);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return Promise.success(Collections.emptyList());
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

    private static org.pragmatica.aether.invoke.InvocationHandler stubInvocationHandler() {
        return new org.pragmatica.aether.invoke.InvocationHandler() {
            @Override public void onInvokeRequest(org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, org.pragmatica.aether.slice.SliceBridge bridge) {}

            @Override public void unregisterSlice(Artifact artifact) {}

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> localSlice(Artifact artifact) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector> metricsCollector() {
                return Option.none();
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
