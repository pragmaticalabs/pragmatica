// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.ScheduledTaskRoutes.ScheduledTasksResponse;
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


/// Covers the #680/#841 aggregation gap on the two Management API surfaces that survived the
/// per-node key redesign's write-side fix: `GET /api/scheduled-tasks` (list summary, via
/// [ScheduledTaskRoutes#toSummary]) and `GET /api/scheduled-tasks/state` (single-task, via
/// [ScheduledTaskRoutes#buildStateResponse]). Both read the single-row `ScheduledTaskStateRegistry`
/// mirror unconditionally before this fix — for an ALL-mode task that mirror is stale-or-zero once
/// executions land under per-node keys, since the registry is keyed 1:1 and cannot represent
/// several nodes' rows for the same task. This is the same defect [ScheduledTaskRoutesExecutionsByNodeTest]
/// already pinned on the third surface (`executions-by-node`); these tests close the remaining two.
///
/// Both tests seed two nodes' per-node state deliberately so that the node with the LATEST
/// `updatedAt` does NOT also hold the max `consecutiveFailures` or the min `nextFireAt` — this
/// discriminates [ScheduledTaskRoutes#combineNodeStates]'s independent per-field combine rules
/// (sum / max / min / latest-wins) from a design that happens to pick one "winning" node's row
/// wholesale, which would pass a test where the same node wins every field.
class ScheduledTaskRoutesAllModeAggregationTest {

    private static final String SECTION = "demo.section";
    private static final String ARTIFACT = "org.example:demo:1.0.0";
    private static final String METHOD = "tick";
    private static final String REGISTERED_BY = "node-1";

    private StubRegistry registry;
    private KVStore<AetherKey, AetherValue> store;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        registry.addTask(SECTION, ARTIFACT, METHOD, REGISTERED_BY);
    }

    private ScheduledTaskRoutes buildRoutes() {
        return ScheduledTaskRoutes.scheduledTaskRoutes(registry,
                                                        stubManager(),
                                                        () -> nodeOver(store),
                                                        stubInvoker(),
                                                        stubStateRegistry(),
                                                        () -> true);
    }

    /// Seeds the two per-node rows shared by both tests below. Node A is the LATEST by
    /// `updatedAt` but neither the worst-failures nor the soonest-fire node — see the class
    /// javadoc for why the fixture is shaped this way.
    private void seedTwoNodeState() {
        var nodeA = new NodeId("node-a");
        var nodeB = new NodeId("node-b");
        var atB = 1_000L;
        var atA = 2_000L;

        seed(ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD), nodeA),
             new ScheduledTaskStateValue(atA, 100L, 1, 9, "fail-a", atA, 2));
        seed(ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD), nodeB),
             new ScheduledTaskStateValue(atB, 50L, 5, 3, "fail-b", atB, 1));
    }

    private void seed(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    @Nested
    class ListSummary {

        @Test
        void buildTasksResponse_allModeTaskWithTwoNodesState_reportsCombinedTotals() {
            seedTwoNodeState();
            var routes = buildRoutes();

            ScheduledTasksResponse response = routes.buildTasksResponseForTest();

            assertThat(response.tasks()).hasSize(1);
            var summary = response.tasks().getFirst();
            // totalExecutions: summed across nodes (9 + 3).
            assertThat(summary.totalExecutions()).isEqualTo(12);
            // consecutiveFailures: MAX across nodes (5 from node-b, not node-a's 1, and not
            // node-a despite node-a being the latest-updated row).
            assertThat(summary.consecutiveFailures()).isEqualTo(5);
            // lastExecutionAt: MAX across nodes (node-a's 2000).
            assertThat(summary.lastExecutionAt()).isEqualTo(2_000L);
            // nextFireAt: MIN across nodes (node-b's 50, the soonest upcoming fire).
            assertThat(summary.nextFireAt()).isEqualTo(50L);
        }

        @Test
        void buildTasksResponse_allModeTaskNoNodeStateYet_reportsZeroed() {
            var routes = buildRoutes();

            ScheduledTasksResponse response = routes.buildTasksResponseForTest();

            assertThat(response.tasks()).hasSize(1);
            var summary = response.tasks().getFirst();
            assertThat(summary.totalExecutions()).isZero();
            assertThat(summary.consecutiveFailures()).isZero();
            assertThat(summary.lastExecutionAt()).isZero();
            assertThat(summary.nextFireAt()).isZero();
        }
    }

    @Nested
    class SingleTaskState {

        @Test
        void getTaskState_allModeTaskWithTwoNodesState_reportsCombinedState() {
            seedTwoNodeState();
            var routes = buildRoutes();

            TaskStateResponse response = routes.getTaskStateForTest(SECTION, ARTIFACT, METHOD)
                                                .onFailure(cause -> fail("Must succeed: " + cause.message()))
                                                .await()
                                                .or((TaskStateResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.totalExecutions()).isEqualTo(12);
            assertThat(response.consecutiveFailures()).isEqualTo(5);
            assertThat(response.lastExecutionAt()).isEqualTo(2_000L);
            assertThat(response.nextFireAt()).isEqualTo(50L);
            assertThat(response.skippedOverlaps()).isEqualTo(3);
            // lastFailureMessage/updatedAt: taken together from the row with the highest
            // updatedAt (node-a, 2000) — never an arbitrary pairing of one field from each row.
            assertThat(response.lastFailureMessage()).isEqualTo("fail-a");
            assertThat(response.updatedAt()).isEqualTo(2_000L);
        }

        @Test
        void getTaskState_allModeTaskNoNodeStateYet_reportsEmptyState() {
            var routes = buildRoutes();

            TaskStateResponse response = routes.getTaskStateForTest(SECTION, ARTIFACT, METHOD)
                                                .onFailure(cause -> fail("Must succeed: " + cause.message()))
                                                .await()
                                                .or((TaskStateResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.totalExecutions()).isZero();
            assertThat(response.consecutiveFailures()).isZero();
            assertThat(response.lastFailureMessage()).isEmpty();
        }
    }

    // --- helpers ---

    private static Artifact artifact(String s) {
        return Artifact.artifact(s).unwrap();
    }

    private static ScheduledTaskManager stubManager() {
        return new ScheduledTaskManager() {
            @Override public void onLeaderChange(LeaderChange leaderChange) {}
            @Override public void onQuorumStateChange(ClusterStateNotification notification) {}
            @Override public int activeTimerCount() { return 0; }
            @Override public void stop() {}
            @Override public boolean tryClaim(ScheduledTaskKey key) { return true; }
            @Override public void release(ScheduledTaskKey key) {}
        };
    }

    private static SliceInvoker stubInvoker() {
        return (SliceInvoker) Proxy.newProxyInstance(
            SliceInvoker.class.getClassLoader(),
            new Class[]{SliceInvoker.class},
            (_, method, _) -> {
                throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
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

    /// Read by `toSummary`/`buildStateResponse`'s SINGLE-mode branch only; unused by the ALL-mode
    /// aggregation path under test here (which scans the live `KVStore` instead), but still
    /// required to construct `ScheduledTaskRoutes`.
    private static ScheduledTaskStateRegistry stubStateRegistry() {
        return new ScheduledTaskStateRegistry() {
            private final Map<ScheduledTaskStateKey, ScheduledTaskStateValue> map = new HashMap<>();

            @Override public void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut) {}
            @Override public void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove) {}
            @Override public Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key) {
                return Option.option(map.get(key));
            }
            @Override public Map<ScheduledTaskStateKey, ScheduledTaskStateValue> allStates() {
                return Map.copyOf(map);
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
}
