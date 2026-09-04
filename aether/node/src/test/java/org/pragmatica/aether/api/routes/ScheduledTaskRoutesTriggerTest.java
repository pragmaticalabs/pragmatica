// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
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
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Covers `POST /api/scheduled-tasks/trigger`'s coordination with automatic fires (#273 review,
/// item 1): the manual trigger claims the SAME `ScheduledTaskManager.tryClaim`/`release` guard
/// `TaskOps` uses for fixed-rate ticks and cron, so it can never run concurrently with one. When
/// the claim is already held, the route answers a typed 409 naming the task instead of invoking
/// it — an operator action refused honestly beats a silent double run. The guard's own atomicity
/// (real `ctx.inFlight`, real fixed-rate/cron fires) is pinned in `ScheduledTaskManagerTest`'s
/// `TriggerGuard` nested class; this file pins only the ROUTE's behavior at each of `tryClaim`'s
/// two outcomes, via a manager stub whose `tryClaim`/`release` are backed by a real `Set`.
class ScheduledTaskRoutesTriggerTest {

    private static final String SECTION = "demo.section";
    private static final String ARTIFACT = "org.example:demo:1.0.0";
    private static final String METHOD = "tick";

    private StubRegistry registry;
    private StubStateRegistry stateRegistry;
    private RecordingInvoker invoker;
    private ClaimTrackingManager manager;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        stateRegistry = new StubStateRegistry();
        invoker = new RecordingInvoker();
        manager = new ClaimTrackingManager();
    }

    /// `triggerTask` (the route under test here) never calls `nodeSupplier.get()` — only
    /// `handleInject`/pause/resume do (#273 review item 1's scope is strictly the trigger path) —
    /// so the supplier below is typed but never invoked; there is no `ManageableNode` value that
    /// belongs here, and this test file should not carry a second throwing dynamic-proxy stub
    /// alongside `RecordingInvoker`'s just to give the unused slot a value.
    private static final Supplier<ManageableNode> UNUSED_NODE_SUPPLIER = () -> null;

    private ScheduledTaskRoutes routes() {
        return ScheduledTaskRoutes.scheduledTaskRoutes(registry,
                                                       manager,
                                                       UNUSED_NODE_SUPPLIER,
                                                       invoker.asSliceInvoker(),
                                                       stateRegistry);
    }

    @Nested
    class HappyPath {
        @Test
        void trigger_claimFree_invokesTask_andReleasesClaimAfterSettling() {
            registry.addTask(SECTION, ARTIFACT, METHOD);
            var routes = routes();

            var response = routes.triggerForTest(SECTION, ARTIFACT, METHOD)
                                 .onFailure(cause -> fail("Trigger must succeed when no claim is held: " + cause.message()))
                                 .await()
                                 .or((ScheduledTaskRoutes.TaskActionResult) null);

            assertThat(response).isNotNull();
            assertThat(response.success()).isTrue();
            assertThat(response.action()).isEqualTo("triggered");
            assertThat(invoker.invocations).hasSize(1);

            var key = ScheduledTaskKey.scheduledTaskKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD));
            assertThat(manager.claimed).as("claim must be released once the invocation settles").doesNotContain(key);
        }
    }

    @Nested
    class ConflictGuard {
        @Test
        void trigger_claimAlreadyHeld_returns409_withoutInvokingTask() {
            registry.addTask(SECTION, ARTIFACT, METHOD);
            var key = ScheduledTaskKey.scheduledTaskKey(SECTION, artifact(ARTIFACT), new MethodName(METHOD));
            manager.claimed.add(key); // simulates an automatic fire already in flight

            var routes = routes();

            var result = routes.triggerForTest(SECTION, ARTIFACT, METHOD)
                               .onSuccess(_ -> fail("Trigger must be refused while an automatic fire is in flight"))
                               .await();

            assertTrue(result.isFailure(), "Result must be failure when the claim is already held");
            result.onFailure(cause -> {
                assertTrue(cause.message().contains(SECTION) && cause.message().contains(ARTIFACT),
                           "Failure must name the task: " + cause.message());
                assertTrue(cause instanceof HttpStatusAware, "Cause must carry an HTTP status: " + cause);
                assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
            assertThat(invoker.invocations).as("a refused trigger must never reach the invoker").isEmpty();
        }
    }

    // --- helpers ---

    private static Artifact artifact(String s) {
        return Artifact.artifact(s).unwrap();
    }

    /// Manager stub whose `tryClaim`/`release` are backed by a real `Set`, so tests can pre-claim
    /// a key (simulating an in-flight automatic fire) or observe a claim being released after the
    /// route's invocation settles — the same contract the real `ctx.inFlight` guard provides.
    private static final class ClaimTrackingManager implements ScheduledTaskManager {
        final Set<ScheduledTaskKey> claimed = ConcurrentHashMap.newKeySet();

        @Override public void onLeaderChange(LeaderChange leaderChange) {}
        @Override public void onQuorumStateChange(ClusterStateNotification notification) {}
        @Override public int activeTimerCount() { return 0; }
        @Override public void stop() {}

        @Override
        public boolean tryClaim(ScheduledTaskKey key) {
            return claimed.add(key);
        }

        @Override
        public void release(ScheduledTaskKey key) {
            claimed.remove(key);
        }
    }

    private static final class StubRegistry implements ScheduledTaskRegistry {
        private final List<ScheduledTask> tasks = new ArrayList<>();

        void addTask(String section, String artifactStr, String methodStr) {
            tasks.add(new ScheduledTask(section,
                                        artifact(artifactStr),
                                        new MethodName(methodStr),
                                        new NodeId("node-1"),
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

    private static final class StubStateRegistry implements ScheduledTaskStateRegistry {
        private final java.util.Map<ScheduledTaskStateKey, ScheduledTaskStateValue> map = new java.util.HashMap<>();

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

        SliceInvoker asSliceInvoker() {
            return (SliceInvoker) Proxy.newProxyInstance(
                SliceInvoker.class.getClassLoader(),
                new Class[]{SliceInvoker.class},
                (_, method, args) -> {
                    if ("invoke".equals(method.getName()) && args != null && args.length == 3) {
                        var slice = (Artifact) args[0];
                        var methodName = (MethodName) args[1];
                        invocations.add(new Invocation(slice.asString(), methodName.name()));
                        return Promise.success(Unit.unit());
                    }
                    throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                }
            );
        }
    }
}
