// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Covers stable response ordering for `GET /api/scheduled-tasks` + by-section variant
/// (08-resources Pause_task regression).
///
/// `registry.allTasks()` is a `ConcurrentHashMap`-backed `List.copyOf(values())` whose iteration
/// order is unspecified and shifts across calls. The Pause_task test reads positional `tasks.0`
/// across separate CLI calls; two tasks differing only by artifact (same section+method) flipped
/// between the pause-capture and the readback, producing a false `paused=false`. The routes now
/// sort by `(configSection, artifact, method)` so positional access is deterministic.
class ScheduledTaskRoutesOrderingTest {

    private static final String SECTION = "scheduling.heartbeat";
    private static final String METHOD = "heartbeat";

    private static Artifact artifact(String s) {
        return Artifact.artifact(s).unwrap();
    }

    private static ScheduledTask task(String section, String artifactStr, String methodStr) {
        return new ScheduledTask(section,
                                 artifact(artifactStr),
                                 new MethodName(methodStr),
                                 new NodeId("node-1"),
                                 "1s",
                                 "",
                                 ExecutionMode.ALL,
                                 false);
    }

    private ScheduledTaskRoutes routes(ShufflingRegistry registry) {
        return ScheduledTaskRoutes.scheduledTaskRoutes(registry,
                                                       stubManager(),
                                                       () -> nodeProxy(),
                                                       stubInvoker(),
                                                       stubStateRegistry(),
                                                       () -> true);
    }

    @Nested
    class Ordering {

        @Test
        void buildTasksResponse_orderStable_acrossShiftingRegistryIteration() {
            var registry = new ShufflingRegistry(List.of(task(SECTION, "org.example:beta:1.0.0", METHOD),
                                                         task(SECTION, "org.example:alpha:1.0.0", METHOD),
                                                         task(SECTION, "org.example:gamma:1.0.0", METHOD)));
            var routes = routes(registry);

            var first = artifactsOf(routes);
            registry.shuffle();
            var second = artifactsOf(routes);
            registry.shuffle();
            var third = artifactsOf(routes);

            assertThat(first).containsExactly("org.example:alpha:1.0.0",
                                              "org.example:beta:1.0.0",
                                              "org.example:gamma:1.0.0");
            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
        }

        @Test
        void buildTasksResponse_sameSectionAndMethod_orderedByArtifact() {
            var registry = new ShufflingRegistry(List.of(task(SECTION, "org.example:zeta:2.0.0", METHOD),
                                                         task(SECTION, "org.example:zeta:1.0.0", METHOD)));
            var routes = routes(registry);

            var positionZero = routes.buildTasksResponseForTest().tasks().getFirst().artifact();
            registry.shuffle();
            var positionZeroAgain = routes.buildTasksResponseForTest().tasks().getFirst().artifact();

            // Two tasks differing only by artifact must always resolve tasks.0 to the same one.
            assertThat(positionZero).isEqualTo("org.example:zeta:1.0.0");
            assertThat(positionZeroAgain).isEqualTo(positionZero);
        }

        @Test
        void buildFilteredResponse_orderStable_acrossShiftingRegistryIteration() {
            var registry = new ShufflingRegistry(List.of(task(SECTION, "org.example:beta:1.0.0", METHOD),
                                                         task(SECTION, "org.example:alpha:1.0.0", METHOD),
                                                         task("other.section", "org.example:delta:1.0.0", METHOD)));
            var routes = routes(registry);

            var first = filteredArtifactsOf(routes);
            registry.shuffle();
            var second = filteredArtifactsOf(routes);

            assertThat(first).containsExactly("org.example:alpha:1.0.0", "org.example:beta:1.0.0");
            assertThat(second).isEqualTo(first);
        }
    }

    private List<String> artifactsOf(ScheduledTaskRoutes routes) {
        return routes.buildTasksResponseForTest()
                     .tasks()
                     .stream()
                     .map(ScheduledTaskRoutes.TaskSummary::artifact)
                     .toList();
    }

    private List<String> filteredArtifactsOf(ScheduledTaskRoutes routes) {
        return routes.buildFilteredResponseForTest(SECTION)
                     .onFailure(cause -> fail("Filtered response must succeed: " + cause.message()))
                     .await()
                     .or(new ScheduledTaskRoutes.FilteredTasksResponse(List.of(), SECTION))
                     .tasks()
                     .stream()
                     .map(ScheduledTaskRoutes.TaskSummary::artifact)
                     .toList();
    }

    // --- stubs ---

    /// Mutable-order registry: `allTasks()` returns a fresh shuffled copy each call, faithfully
    /// reproducing the `ConcurrentHashMap.values()` order instability the fix must absorb.
    private static final class ShufflingRegistry implements ScheduledTaskRegistry {
        private final List<ScheduledTask> tasks;

        private ShufflingRegistry(List<ScheduledTask> tasks) {
            this.tasks = new ArrayList<>(tasks);
        }

        void shuffle() {
            Collections.reverse(tasks);
        }

        @Override public void onScheduledTaskPut(ValuePut<ScheduledTaskKey, ScheduledTaskValue> valuePut) {}
        @Override public void onScheduledTaskRemove(ValueRemove<ScheduledTaskKey, ScheduledTaskValue> valueRemove) {}
        @Override public List<ScheduledTask> allTasks() { return new ArrayList<>(tasks); }
        @Override public List<ScheduledTask> singleModeTasks() { return List.of(); }
        @Override public List<ScheduledTask> localTasks(NodeId self) { return new ArrayList<>(tasks); }
        @Override public void setChangeListener(BiConsumer<ScheduledTaskKey, Option<ScheduledTask>> listener) {}
    }

    private static ScheduledTaskManager stubManager() {
        return new ScheduledTaskManager() {
            @Override public void onLeaderChange(org.pragmatica.consensus.leader.LeaderNotification.LeaderChange leaderChange) {}
            @Override public void onQuorumStateChange(org.pragmatica.consensus.topology.ClusterStateNotification notification) {}
            @Override public int activeTimerCount() { return 0; }
            @Override public void stop() {}
            @Override public boolean tryClaim(org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey key) { return true; }
            @Override public void release(org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey key) {}
        };
    }

    private static ScheduledTaskStateRegistry stubStateRegistry() {
        return new ScheduledTaskStateRegistry() {
            @Override public void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut) {}
            @Override public void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove) {}
            @Override public Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key) { return Option.none(); }
            @Override public java.util.Map<ScheduledTaskStateKey, ScheduledTaskStateValue> allStates() { return java.util.Map.of(); }
        };
    }

    private static SliceInvoker stubInvoker() {
        return (SliceInvoker) Proxy.newProxyInstance(
            SliceInvoker.class.getClassLoader(),
            new Class[]{SliceInvoker.class},
            (_, method, _) -> {
                throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            });
    }

    /// #841: `toSummary`/`buildFilteredResponse` now call `nodeSupplier.get().kvStore()`
    /// unconditionally for every ALL-mode task (this suite's tasks are all `ExecutionMode.ALL`) to
    /// aggregate per-node state. An empty store makes that scan a no-op — `aggregateAllModeState`
    /// falls back to the same zeroed summary this suite already asserted before #841, so the
    /// ordering assertions this file exists for are unaffected.
    private static ManageableNode nodeProxy() {
        var emptyStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, _) -> switch (method.getName()) {
                case "kvStore" -> emptyStore;
                default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            });
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
