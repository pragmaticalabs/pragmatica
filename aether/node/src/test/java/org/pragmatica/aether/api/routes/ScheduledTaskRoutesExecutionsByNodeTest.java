// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.ScheduledTaskExecutionsByNodeResponse;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Covers `GET /api/scheduled-tasks/executions-by-node` (P-NEW-H, 2026-05-21; per-node state
/// keys, #841). Since #841, ALL-mode tasks write their execution counter under a per-node-scoped
/// `ScheduledTaskStateKey` (one row per executing node, keyed by `ctx.self()`) instead of a single
/// cluster-wide counter — concurrent executions on different nodes would otherwise clobber the
/// same row. The route now aggregates by scanning the live `KVStore` for entries matching the
/// task's identity with a node component present ([ScheduledTaskRoutes#buildExecutionsByNode]),
/// replacing the old RC1 stub that attributed every execution to `task.registeredBy()`. A
/// pre-#841 global-shaped entry (no node component) and another task's per-node entry must both
/// be excluded from the aggregation — see `InvalidEntriesExcluded`, which pins the rolling-upgrade
/// safety property that the old global key can never be misread as a node's row.
class ScheduledTaskRoutesExecutionsByNodeTest {

    private static final String SECTION = "demo.section";
    private static final String ARTIFACT = "org.example:demo:1.0.0";
    private static final String METHOD = "tick";
    private static final String EXECUTOR_NODE = "node-1";

    private StubRegistry registry;
    private StubStateRegistry stateRegistry;
    private KVStore<AetherKey, AetherValue> store;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        stateRegistry = new StubStateRegistry();
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private ScheduledTaskRoutes buildRoutes() {
        return ScheduledTaskRoutes.scheduledTaskRoutes(registry,
                                                        stubManager(),
                                                        () -> nodeOver(store),
                                                        stubInvoker(),
                                                        stateRegistry,
                                                        () -> true);
    }

    private void seed(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    @Nested
    class TaskNotFound {

        @Test
        void executionsByNode_unknownTask_returnsFailure() {
            var routes = buildRoutes();

            var result = invoke(routes, SECTION, ARTIFACT, METHOD)
                          .onSuccess(_ -> fail("Must fail when task is not registered"))
                          .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toLowerCase()).contains("not found"));
        }
    }

    @Nested
    class NoStateYet {

        @Test
        void executionsByNode_taskRegisteredNoState_returnsEmptyExecutionsList() {
            registry.addTask(SECTION, ARTIFACT, METHOD, EXECUTOR_NODE);
            var routes = buildRoutes();

            var response = invoke(routes, SECTION, ARTIFACT, METHOD)
                            .onFailure(cause -> fail("Must succeed: " + cause.message()))
                            .await()
                            .or((ScheduledTaskExecutionsByNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.section()).isEqualTo(SECTION);
            assertThat(response.artifact()).isEqualTo(ARTIFACT);
            assertThat(response.method()).isEqualTo(METHOD);
            assertThat(response.executions()).isEmpty();
        }
    }

    @Nested
    class WithState {

        @Test
        void executionsByNode_taskWithNodeScopedState_reportsThatNodeWithTotal() {
            registry.addTask(SECTION, ARTIFACT, METHOD, EXECUTOR_NODE);
            var stateKey = ScheduledTaskStateKey.scheduledTaskStateKey(SECTION,
                                                                        artifact(ARTIFACT),
                                                                        new MethodName(METHOD),
                                                                        new NodeId(EXECUTOR_NODE));
            var executionAt = System.currentTimeMillis();
            seed(stateKey, new ScheduledTaskStateValue(executionAt, 0, 0, 7, "", executionAt, 0));

            var routes = buildRoutes();

            var response = invoke(routes, SECTION, ARTIFACT, METHOD)
                            .onFailure(cause -> fail("Must succeed: " + cause.message()))
                            .await()
                            .or((ScheduledTaskExecutionsByNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.executions()).hasSize(1);
            assertThat(response.executions().get(0).nodeId()).isEqualTo(EXECUTOR_NODE);
            assertThat(response.executions().get(0).count()).isEqualTo(7);
            assertThat(response.executions().get(0).lastExecutionMs()).isEqualTo(executionAt);
        }
    }

    @Nested
    class MultipleNodes {

        @Test
        void executionsByNode_twoNodesWithState_reportsOneRowPerNodeSortedById() {
            registry.addTask(SECTION, ARTIFACT, METHOD, EXECUTOR_NODE);

            var nodeB = new NodeId("node-b");
            var nodeA = new NodeId("node-a");
            var atB = System.currentTimeMillis();
            var atA = atB + 1000;

            // Seeded out of node-id order — the route must sort the aggregated rows itself.
            seed(ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD), nodeB),
                 new ScheduledTaskStateValue(atB, 0, 0, 3, "", atB, 0));
            seed(ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD), nodeA),
                 new ScheduledTaskStateValue(atA, 0, 0, 9, "", atA, 0));

            var routes = buildRoutes();

            var response = invoke(routes, SECTION, ARTIFACT, METHOD)
                            .onFailure(cause -> fail("Must succeed: " + cause.message()))
                            .await()
                            .or((ScheduledTaskExecutionsByNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.executions()).hasSize(2);
            assertThat(response.executions().get(0).nodeId()).isEqualTo("node-a");
            assertThat(response.executions().get(0).count()).isEqualTo(9);
            assertThat(response.executions().get(1).nodeId()).isEqualTo("node-b");
            assertThat(response.executions().get(1).count()).isEqualTo(3);
        }
    }

    @Nested
    class InvalidEntriesExcluded {

        @Test
        void executionsByNode_globalKeyAndOtherTaskEntry_bothExcludedFromAggregation() {
            registry.addTask(SECTION, ARTIFACT, METHOD, EXECUTOR_NODE);

            // Pre-#841 global-shaped entry for THIS task's identity — no node component. Must
            // never be misread as a node's row by the new per-node scan.
            var globalKey = ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD));
            var globalAt = System.currentTimeMillis();
            seed(globalKey, new ScheduledTaskStateValue(globalAt, 0, 0, 5, "", globalAt, 0));

            // Node-scoped entry for a DIFFERENT task's identity — must never leak into this
            // task's aggregation.
            var otherTaskKey = ScheduledTaskStateKey.scheduledTaskStateKey(SECTION,
                                                                            artifact("org.example:other:1.0.0"),
                                                                            new MethodName(METHOD),
                                                                            new NodeId(EXECUTOR_NODE));
            var otherAt = System.currentTimeMillis();
            seed(otherTaskKey, new ScheduledTaskStateValue(otherAt, 0, 0, 11, "", otherAt, 0));

            var routes = buildRoutes();

            var response = invoke(routes, SECTION, ARTIFACT, METHOD)
                            .onFailure(cause -> fail("Must succeed: " + cause.message()))
                            .await()
                            .or((ScheduledTaskExecutionsByNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.executions())
                    .as("neither the pre-upgrade global row nor another task's per-node row may surface here")
                    .isEmpty();
        }
    }

    @Nested
    class RouteRegistration {

        @Test
        void routes_includesExecutionsByNodeRoute() {
            registry.addTask(SECTION, ARTIFACT, METHOD, EXECUTOR_NODE);
            var routes = buildRoutes();

            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("SCHEDULED_TASK_EXECUTIONS_BY_NODE");
        }
    }

    // --- helpers ---

    private static org.pragmatica.lang.Promise<ScheduledTaskExecutionsByNodeResponse> invoke(ScheduledTaskRoutes routes,
                                                                                              String section,
                                                                                              String artifactStr,
                                                                                              String method) {
        // Drive the route handler through the public routes() stream — the executions-by-node
        // route is the only path-3 GET route on `/api/scheduled-tasks/executions-by-node`.
        @SuppressWarnings("unchecked")
        var route = (org.pragmatica.http.routing.Route<ScheduledTaskExecutionsByNodeResponse>) routes.routes()
            .filter(r -> "SCHEDULED_TASK_EXECUTIONS_BY_NODE".equals(r.name()))
            .findFirst()
            .orElseThrow();
        // The route's handler is wrapped — we exercise via reflection-free path by calling
        // the package-private helper directly using a small bridge.
        return invokeViaBridge(routes, section, artifactStr, method);
    }

    /// Package-private bridge — calls the same `getExecutionsByNode` private method via a
    /// minimal reflection lookup (one-time call site, low risk). Avoids exposing yet another
    /// `*ForTest` accessor on the production class.
    private static org.pragmatica.lang.Promise<ScheduledTaskExecutionsByNodeResponse> invokeViaBridge(ScheduledTaskRoutes routes,
                                                                                                       String section,
                                                                                                       String artifactStr,
                                                                                                       String method) {
        try {
            var m = ScheduledTaskRoutes.class.getDeclaredMethod("getExecutionsByNode", String.class, String.class, String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            var p = (org.pragmatica.lang.Promise<ScheduledTaskExecutionsByNodeResponse>) m.invoke(routes, section, artifactStr, method);
            return p;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke getExecutionsByNode: " + e.getMessage(), e);
        }
    }

    private static Artifact artifact(String s) {
        return Artifact.artifact(s).unwrap();
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

    private static SliceInvoker stubInvoker() {
        return (SliceInvoker) Proxy.newProxyInstance(
            SliceInvoker.class.getClassLoader(),
            new Class[]{SliceInvoker.class},
            (_, _, _) -> {
                throw new UnsupportedOperationException("Invoker should not be called for executions-by-node");
            }
        );
    }

    private static ManageableNode nodeOver(KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, _) -> switch (method.getName()) {
                case "kvStore" -> store;
                default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            }
        );
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

    private static final class StubRegistry implements ScheduledTaskRegistry {
        private final List<ScheduledTask> tasks = new ArrayList<>();

        void addTask(String section, String artifactStr, String methodStr, String registeredByNodeId) {
            tasks.add(new ScheduledTask(section,
                                        artifact(artifactStr),
                                        new MethodName(methodStr),
                                        new NodeId(registeredByNodeId),
                                        "1s",
                                        "",
                                        ExecutionMode.ALL,
                                        false));
        }

        @Override public void onScheduledTaskPut(ValuePut<ScheduledTaskKey, AetherValue.ScheduledTaskValue> valuePut) {}
        @Override public void onScheduledTaskRemove(ValueRemove<ScheduledTaskKey, AetherValue.ScheduledTaskValue> valueRemove) {}
        @Override public List<ScheduledTask> allTasks() { return List.copyOf(tasks); }
        @Override public List<ScheduledTask> singleModeTasks() { return List.of(); }
        @Override public List<ScheduledTask> localTasks(NodeId self) { return List.copyOf(tasks); }
        @Override public void setChangeListener(BiConsumer<ScheduledTaskKey, Option<ScheduledTask>> listener) {}
    }

    /// No longer read by the executions-by-node route (which now scans the live `KVStore`
    /// instead) — kept only because [ScheduledTaskRoutes#scheduledTaskRoutes] still requires a
    /// `ScheduledTaskStateRegistry` for its OTHER routes (list/summary/single-state).
    private static final class StubStateRegistry implements ScheduledTaskStateRegistry {
        private final Map<ScheduledTaskStateKey, ScheduledTaskStateValue> map = new HashMap<>();

        @Override public void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut) {}
        @Override public void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove) {}
        @Override public Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key) {
            return Option.option(map.get(key));
        }
        @Override public Map<ScheduledTaskStateKey, ScheduledTaskStateValue> allStates() {
            return Map.copyOf(map);
        }
    }
}
