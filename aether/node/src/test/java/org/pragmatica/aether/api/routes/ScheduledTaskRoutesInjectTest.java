// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.ScheduledTaskInjectRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ScheduledTaskInjectResponse;
import org.pragmatica.aether.api.routes.ScheduledTaskRoutes.TaskStateResponse;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/// Covers `POST /api/scheduled-tasks/inject` (dev-mode-only test endpoint).
/// Validates dev-mode gate, task lookup, invocation, and synchronous state
/// advancement (`lastExecutionAt` written via consensus apply before the
/// response is returned). Unblocks RC1-blocker #16 in
/// `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2.
class ScheduledTaskRoutesInjectTest {

    private static final String SECTION = "demo.section";
    private static final String ARTIFACT = "org.example:demo:1.0.0";
    private static final String METHOD = "tick";
    // `RecordingNode#self()` reports this id; `StubRegistry#addTask` defaults every task to
    // ALL-mode, so `/inject`'s state key is now scoped by this node (mirrors the automatic
    // path, #841 follow-up) rather than the pre-#841 unscoped key.
    private static final NodeId SELF_NODE = new NodeId("node-self");

    private StubRegistry registry;
    private StubStateRegistry stateRegistry;
    private RecordingInvoker invoker;
    private RecordingNode node;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        stateRegistry = new StubStateRegistry();
        invoker = new RecordingInvoker();
        node = new RecordingNode();
    }

    private ScheduledTaskRoutes routesWithDevMode(boolean enabled) {
        BooleanSupplier devMode = () -> enabled;
        return ScheduledTaskRoutes.scheduledTaskRoutes(registry,
                                                       stubManager(),
                                                       () -> node.asManageableNode(),
                                                       invoker.asSliceInvoker(),
                                                       stateRegistry,
                                                       devMode);
    }

    @Nested
    class DevModeGate {

        @Test
        void inject_rejected_whenDevModeDisabled() {
            registry.addTask(SECTION, ARTIFACT, METHOD);
            var routes = routesWithDevMode(false);

            var result = invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, ARTIFACT, METHOD))
                          .onSuccess(_ -> fail("Inject must be rejected when AETHER_INSECURE_DEV_MODE is not set"))
                          .await();

            assertTrue(result.isFailure(), "Result must be failure when dev-mode disabled");
            result.onFailure(cause -> assertTrue(cause.message().contains("AETHER_INSECURE_DEV_MODE"),
                                                  "Failure must mention the gate env var: " + cause.message()));
            assertThat(invoker.invocations).isEmpty();
            assertThat(node.commands).isEmpty();
        }
    }

    @Nested
    class HappyPath {

        @Test
        void inject_invokesTask_writesSuccessState_andAdvancesLastExecution() {
            registry.addTask(SECTION, ARTIFACT, METHOD);
            // StubRegistry defaults the task to ALL-mode, so /inject now keys state by this
            // node (SELF_NODE, via RecordingNode#self()) rather than the pre-#841 unscoped key
            // — seed the same per-node key here so priorState is actually found.
            var key = ScheduledTaskStateKey.scheduledTaskStateKey(SECTION,
                                                                   artifact(ARTIFACT),
                                                                   new MethodName(METHOD),
                                                                   SELF_NODE);
            var priorTimestamp = System.currentTimeMillis() - 5_000;
            stateRegistry.put(key, new ScheduledTaskStateValue(priorTimestamp, 0, 0, 7, "", priorTimestamp, 0));

            var routes = routesWithDevMode(true);

            var response = invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, ARTIFACT, METHOD))
                            .onFailure(cause -> fail("Inject must succeed: " + cause.message()))
                            .await()
                            .or((ScheduledTaskInjectResponse) null);

            assertNotNull(response, "Response must be non-null on success");
            assertEquals(SECTION, response.section());
            assertEquals(ARTIFACT, response.artifact());
            assertEquals(METHOD, response.method());
            assertEquals(priorTimestamp, response.previousExecutionMs(),
                          "previousExecutionMs must reflect prior state lastExecutionAt");
            assertTrue(response.currentExecutionMs() > response.previousExecutionMs(),
                       "currentExecutionMs must advance strictly past previous: "
                       + response.previousExecutionMs() + " → " + response.currentExecutionMs());

            assertEquals(1, invoker.invocations.size(), "Task must be invoked exactly once");
            assertEquals(ARTIFACT, invoker.invocations.get(0).artifact);
            assertEquals(METHOD, invoker.invocations.get(0).method);

            assertEquals(1, node.commands.size(), "Exactly one KV apply (success state write) expected");
            var command = node.commands.get(0).get(0);
            assertTrue(command instanceof KVCommand.Put<?, ?>,
                       "State write must be a Put command: " + command);
        }

        @Test
        void inject_noPriorState_returnsZeroPrevious_andStillAdvances() {
            registry.addTask(SECTION, ARTIFACT, METHOD);
            var routes = routesWithDevMode(true);

            var response = invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, ARTIFACT, METHOD))
                            .onFailure(cause -> fail("Inject must succeed: " + cause.message()))
                            .await()
                            .or((ScheduledTaskInjectResponse) null);

            assertNotNull(response, "Response must be non-null on success");
            assertEquals(0L, response.previousExecutionMs(),
                          "previousExecutionMs must be 0 when no prior state entry exists");
            assertTrue(response.currentExecutionMs() > 0,
                       "currentExecutionMs must be a stamped non-zero timestamp");
        }
    }

    /// #841 follow-up (owner ruling: fix in this PR, not just document): `invokeAndAdvanceState`
    /// keyed EVERY execution mode with the pre-#841 unscoped `ScheduledTaskStateKey`. Once the
    /// aggregation fix landed, that made an ALL-mode `/inject` write orphaned — 200 response,
    /// advancing counters, permanently invisible on every Management API surface, because those
    /// surfaces now filter the unscoped key out. `injectStateKeyFor` closes this by mirroring
    /// `ScheduledTaskManager.TaskOps#stateKeyFor`: ALL-mode scoped by this node, SINGLE-mode
    /// unchanged (still races the leader's automatic fire on that key — `/trigger` writes no state
    /// entry, so it is not a racer here — a documented TOCTOU caveat, not addressed here).
    @Nested
    class KeyScoping {

        @Test
        void inject_onSingleModeTask_stillUsesUnscopedKey_unchangedFromBefore() {
            registry.addTask(SECTION, ARTIFACT, METHOD, ExecutionMode.SINGLE);
            var unscopedKey = ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD));
            var priorTimestamp = System.currentTimeMillis() - 5_000;
            stateRegistry.put(unscopedKey, new ScheduledTaskStateValue(priorTimestamp, 0, 0, 3, "", priorTimestamp, 0));
            var routes = routesWithDevMode(true);

            var response = invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, ARTIFACT, METHOD))
                            .onFailure(cause -> fail("Inject must succeed: " + cause.message()))
                            .await()
                            .or((ScheduledTaskInjectResponse) null);

            assertNotNull(response, "Response must be non-null on success");
            assertEquals(priorTimestamp, response.previousExecutionMs(),
                          "SINGLE-mode must still read prior state from the unscoped key, unchanged");
            assertEquals(1, node.commands.size(), "Exactly one KV apply expected");
            var written = (KVCommand.Put<?, ?>) node.commands.get(0).get(0);
            assertEquals(unscopedKey, written.key(), "SINGLE-mode write must still target the unscoped key");
        }

        @Test
        void inject_onAllModeTask_advancesStateVisibleOnStateEndpoint() {
            var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubKvSerializer(), stubKvDeserializer());
            var localRegistry = new StubRegistry();
            localRegistry.addTask(SECTION, ARTIFACT, METHOD, ExecutionMode.ALL);
            var localInvoker = new RecordingInvoker();
            var routes = ScheduledTaskRoutes.scheduledTaskRoutes(localRegistry,
                                                                  stubManager(),
                                                                  () -> nodeOverRealStore(store, SELF_NODE),
                                                                  localInvoker.asSliceInvoker(),
                                                                  new StubStateRegistry(),
                                                                  () -> true);

            invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, ARTIFACT, METHOD))
                .onFailure(cause -> fail("Inject must succeed: " + cause.message()))
                .await();

            var state = routes.getTaskStateForTest(SECTION, ARTIFACT, METHOD)
                               .onFailure(cause -> fail("State read must succeed: " + cause.message()))
                               .await()
                               .or((TaskStateResponse) null);

            assertNotNull(state, "State response must be non-null");
            assertEquals(1, state.totalExecutions(),
                         "Inject's write must land on this node's per-node row so ALL-mode aggregation counts it");
            assertTrue(state.lastExecutionAt() > 0, "lastExecutionAt must advance past zero");
        }
    }

    @Nested
    class TaskLookup {

        @Test
        void inject_taskNotFound_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, ARTIFACT, METHOD))
                          .onSuccess(_ -> fail("Inject must fail when task is not registered"))
                          .await();

            assertTrue(result.isFailure(), "Unknown task must produce failure result");
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("not found"),
                                                  "Failure must mention not-found: " + cause.message()));
            assertThat(invoker.invocations).isEmpty();
        }
    }

    @Nested
    class Validation {

        @Test
        void inject_missingSection_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = invokeInject(routes, new ScheduledTaskInjectRequest("", ARTIFACT, METHOD))
                          .onSuccess(_ -> fail("Blank section must be rejected"))
                          .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("section"),
                                                  "Failure must mention missing section: " + cause.message()));
        }

        @Test
        void inject_missingArtifact_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, "  ", METHOD))
                          .onSuccess(_ -> fail("Blank artifact must be rejected"))
                          .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("artifact"),
                                                  "Failure must mention missing artifact: " + cause.message()));
        }

        @Test
        void inject_missingMethod_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = invokeInject(routes, new ScheduledTaskInjectRequest(SECTION, ARTIFACT, null))
                          .onSuccess(_ -> fail("Null method must be rejected"))
                          .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("method"),
                                                  "Failure must mention missing method: " + cause.message()));
        }
    }

    @Nested
    class RouteRegistration {

        @Test
        void routes_includesInjectRoute() {
            registry.addTask(SECTION, ARTIFACT, METHOD);
            var routes = routesWithDevMode(true);

            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("SCHEDULED_TASK_INJECT");
        }
    }

    /// Validates the inject-rejection hint is derived from CURRENT slice placement (KV-Store),
    /// not registration history. Integration harness follows the hint with bounded retries, so a
    /// circular hint (naming the node that just refused) would dead-loop the retry — exactly the
    /// suite-08 failure this fix addresses.
    @Nested
    class HostingHint {

        private static final NodeId SELF = new NodeId("node-self");
        private static final NodeId REGISTERED_BY = new NodeId("node-registrant");
        private static final NodeId ACTIVE_HOST = new NodeId("node-active-host");

        @Test
        void selectHostingHint_prefersActivePlacement_overRegisteredBy() {
            var kvStore = new TestKVStore();
            // Slice has moved off the registrant onto ACTIVE_HOST (the suite-08 scenario).
            kvStore.putNodeArtifact(ACTIVE_HOST, artifact(ARTIFACT), SliceState.ACTIVE);
            kvStore.putNodeArtifact(REGISTERED_BY, artifact(ARTIFACT), SliceState.UNLOAD);

            var hint = ScheduledTaskRoutes.selectHostingHint(kvStore, SELF, artifact(ARTIFACT), REGISTERED_BY);

            assertEquals(ACTIVE_HOST.id(), hint,
                          "Hint must point at the node holding the ACTIVE placement, not registration history");
        }

        @Test
        void selectHostingHint_excludesSelf_evenWhenSelfIsActive() {
            var kvStore = new TestKVStore();
            // The receiving node has an ACTIVE entry but already refused (hasLocalSlice=false race);
            // it must never hint at itself, otherwise the caller retries the same dead node.
            kvStore.putNodeArtifact(SELF, artifact(ARTIFACT), SliceState.ACTIVE);
            kvStore.putNodeArtifact(ACTIVE_HOST, artifact(ARTIFACT), SliceState.ACTIVE);

            var hint = ScheduledTaskRoutes.selectHostingHint(kvStore, SELF, artifact(ARTIFACT), REGISTERED_BY);

            assertEquals(ACTIVE_HOST.id(), hint, "Hint must exclude the receiving node");
        }

        @Test
        void selectHostingHint_ignoresActivePlacementForOtherArtifact() {
            var kvStore = new TestKVStore();
            var otherArtifact = artifact("org.example:other:1.0.0");
            kvStore.putNodeArtifact(ACTIVE_HOST, otherArtifact, SliceState.ACTIVE);

            var hint = ScheduledTaskRoutes.selectHostingHint(kvStore, SELF, artifact(ARTIFACT), REGISTERED_BY);

            assertEquals(REGISTERED_BY.id(), hint,
                          "ACTIVE placement for a different artifact must not win the hint");
        }

        @Test
        void selectHostingHint_fallsBackToRegisteredBy_whenNoActiveEntry() {
            var kvStore = new TestKVStore();
            // Slice present but not ACTIVE anywhere (mid-rebalance): no usable bridge → fall back.
            kvStore.putNodeArtifact(ACTIVE_HOST, artifact(ARTIFACT), SliceState.ACTIVATING);
            kvStore.putNodeArtifact(REGISTERED_BY, artifact(ARTIFACT), SliceState.LOADED);

            var hint = ScheduledTaskRoutes.selectHostingHint(kvStore, SELF, artifact(ARTIFACT), REGISTERED_BY);

            assertEquals(REGISTERED_BY.id(), hint,
                          "With no ACTIVE placement the hint must fall back to registeredBy");
        }

        @Test
        void selectHostingHint_fallsBackToRegisteredBy_whenStoreEmpty() {
            var kvStore = new TestKVStore();

            var hint = ScheduledTaskRoutes.selectHostingHint(kvStore, SELF, artifact(ARTIFACT), REGISTERED_BY);

            assertEquals(REGISTERED_BY.id(), hint, "Empty placement must never yield an empty hint");
        }
    }

    // --- helpers ---

    private static Promise<ScheduledTaskInjectResponse> invokeInject(ScheduledTaskRoutes routes,
                                                                      ScheduledTaskInjectRequest req) {
        // Drive the handler directly via reflection on the package-private route stream
        // would be brittle; instead exercise the handler by exposing it through a
        // package-private inject() helper would also bloat the production class.
        // We therefore use the public RouteSource surface — the inject route is the
        // only route with a body-bearing JSON handler whose request type matches
        // ScheduledTaskInjectRequest, so its name uniquely identifies it.
        return routes.handleInjectForTest(req);
    }

    private static Artifact artifact(String s) {
        return Artifact.artifact(s).unwrap();
    }

    /// Real `KVStore` (not the command-recording `RecordingNode` double) so `KeyScoping`'s
    /// ALL-mode visibility test can both write (via `apply`) and read back (via the ALL-mode
    /// aggregation's `kvStore().forEach`) through the same backing store — mirrors the fixture
    /// in `ScheduledTaskRoutesAllModeAggregationTest`.
    private static ManageableNode nodeOverRealStore(KVStore<AetherKey, AetherValue> store, NodeId self) {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, args) -> switch (method.getName()) {
                case "kvStore" -> store;
                case "self" -> self;
                case "apply" -> {
                    @SuppressWarnings("unchecked")
                    var commands = (List<KVCommand<AetherKey>>) args[0];

                    store.process(store.createBatch(commands));
                    yield Promise.success(List.of());
                }
                default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            }
        );
    }

    private static Serializer stubKvSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubKvDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static ScheduledTaskManager stubManager() {
        return new ScheduledTaskManager() {
            @Override public void onLeaderChange(LeaderChange leaderChange) {}
            @Override public void onQuorumStateChange(ClusterStateNotification notification) {}
            @Override public int activeTimerCount() { return 0; }
            @Override public void stop() {}
            @Override public boolean tryClaim(org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey key) { return true; }
            @Override public void release(org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey key) {}
        };
    }

    private static final class StubRegistry implements ScheduledTaskRegistry {
        private final List<ScheduledTask> tasks = new ArrayList<>();

        void addTask(String section, String artifactStr, String methodStr) {
            addTask(section, artifactStr, methodStr, ExecutionMode.ALL);
        }

        void addTask(String section, String artifactStr, String methodStr, ExecutionMode mode) {
            tasks.add(new ScheduledTask(section,
                                        artifact(artifactStr),
                                        new MethodName(methodStr),
                                        new NodeId("node-1"),
                                        "1s",
                                        "",
                                        mode,
                                        false));
        }

        @Override public void onScheduledTaskPut(ValuePut<ScheduledTaskKey, AetherValue.ScheduledTaskValue> valuePut) {}
        @Override public void onScheduledTaskRemove(ValueRemove<ScheduledTaskKey, AetherValue.ScheduledTaskValue> valueRemove) {}
        @Override public List<ScheduledTask> allTasks() { return List.copyOf(tasks); }
        @Override public List<ScheduledTask> singleModeTasks() { return List.of(); }
        @Override public List<ScheduledTask> localTasks(NodeId self) { return List.copyOf(tasks); }
        @Override public void setChangeListener(BiConsumer<ScheduledTaskKey, Option<ScheduledTask>> listener) {}
    }

    private static final class StubStateRegistry implements ScheduledTaskStateRegistry {
        private final java.util.Map<ScheduledTaskStateKey, ScheduledTaskStateValue> map = new java.util.HashMap<>();

        void put(ScheduledTaskStateKey key, ScheduledTaskStateValue value) {
            map.put(key, value);
        }

        @Override public void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut) {}
        @Override public void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove) {}
        @Override public Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key) {
            return Option.option(map.get(key));
        }
        @Override public java.util.Map<ScheduledTaskStateKey, ScheduledTaskStateValue> allStates() {
            return java.util.Map.copyOf(map);
        }
    }

    private record Invocation(String artifact, String method) {}

    private static final class RecordingInvoker {
        final List<Invocation> invocations = new CopyOnWriteArrayList<>();
        Cause failure = null;

        SliceInvoker asSliceInvoker() {
            return (SliceInvoker) Proxy.newProxyInstance(
                SliceInvoker.class.getClassLoader(),
                new Class[]{SliceInvoker.class},
                (_, method, args) -> {
                    if ("invoke".equals(method.getName()) && args != null && args.length == 3) {
                        var slice = (Artifact) args[0];
                        var methodName = (MethodName) args[1];
                        invocations.add(new Invocation(slice.asString(), methodName.name()));
                        return failure == null ? Promise.success(Unit.unit()) : failure.promise();
                    }
                    // `hasLocalSlice` is the inject-route's locality gate (added after the
                    // `findSenderBridge` NPE fix). Test stub always reports "local" — the
                    // remote-routing branch is exercised in a dedicated integration test
                    // (test-scheduled-tasks.sh routes inject directly to the hosting node).
                    if ("hasLocalSlice".equals(method.getName())) {
                        return Boolean.TRUE;
                    }
                    throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                }
            );
        }
    }

    private static final class RecordingNode {
        final List<List<KVCommand<AetherKey>>> commands = new CopyOnWriteArrayList<>();

        ManageableNode asManageableNode() {
            return (ManageableNode) Proxy.newProxyInstance(
                ManageableNode.class.getClassLoader(),
                new Class[]{ManageableNode.class},
                (_, method, args) -> {
                    if ("apply".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof List<?> list) {
                        @SuppressWarnings("unchecked")
                        var typedList = (List<KVCommand<AetherKey>>) list;
                        commands.add(typedList);
                        return Promise.success(List.of());
                    }
                    if ("self".equals(method.getName())) {
                        return SELF_NODE;
                    }
                    throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                }
            );
        }
    }

    /// Minimal `KVStore` double exposing only the typed `forEach` that `selectHostingHint`
    /// consults, populated with `NodeArtifactKey → NodeArtifactValue` placement entries. Mirrors
    /// the `TestKVStore` pattern used across the deployment-cluster test suites.
    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final java.util.Map<AetherKey, AetherValue> storage = new java.util.HashMap<>();

        TestKVStore() {
            super(null, null, null);
        }

        void putNodeArtifact(NodeId nodeId, Artifact artifact, SliceState state) {
            storage.put(NodeArtifactKey.nodeArtifactKey(nodeId, artifact),
                        NodeArtifactValue.nodeArtifactValue(state));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }
}
